package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Denoise backend with two modes:
 * <ul>
 *   <li><b>Hybrid (default)</b>: Official FFX shadow+reflection → prepare NRD inputs → NRD REBLUR</li>
 *   <li><b>NRD-only</b>: skip FFX; pack raw layers → NRD REBLUR (Radiance-style)</li>
 * </ul>
 *
 * @see docs/superpowers/specs/2026-07-14-hybrid-ffx-nrd-pipeline.md
 */
public final class HybridFfxNrdBackend implements CausticaDenoiseBackend {

    private final OfficialFfxDenoiseBackend ffx = new OfficialFfxDenoiseBackend();
    /** When true, skip FFX prepass and feed raw S/R into prepare (config denoise=NRD). */
    private final boolean nrdOnly;
    private boolean ready;
    private int width;
    private int height;
    private boolean nrdRanLogged;
    private boolean nrdFailLogged;
    /** True if last dispatch finished REBLUR + compose (caller uses light residual TAA). */
    private volatile boolean lastNrdOk;
    /** What the last frame actually did, for the debug overlay. */
    private volatile String lastPathLabel = "idle";

    private RtImage shadowHit;
    private RtImage diffuse;
    private RtImage reflection;
    private RtImage diffAlbedo;
    private RtImage specAlbedo;
    private RtImage beautyRawCopy; // pre-FFX beauty (for G recovery)
    private RtImage ffxPlate;      // post-FFX beauty (sky fallback)
    private RtImage nrdDiffuse;
    private RtImage nrdSpecular;
    private RtImage viewZ;
    private RtImage nrdNormalRough; // NRD best-fit packed normal+rough
    private RtImage nrdOutDiff;
    private RtImage nrdOutSpec;
    /**
     * Dummy RG16F shadow-clean target for NRD-only (prepare never reads it when useRawLayers=1).
     * Avoids binding R8 raw shadow into a rg16f descriptor slot.
     */
    private RtImage dummyShadowClean;

    // Camera for NRD common settings (column-major 4x4)
    private final float[] viewToClip = new float[16];
    private final float[] viewToClipPrev = new float[16];
    private final float[] worldToView = new float[16];
    private final float[] worldToViewPrev = new float[16];
    private float jitterX, jitterY, jitterXPrev, jitterYPrev;
    private int nrdFrameIndex;
    private boolean nrdHardReset = true;
    private boolean haveCamera;

    private long prepDsl, prepPool, prepSet, prepLayout, prepPipe;
    private long compDsl, compPool, compSet, compLayout, compPipe;

    public HybridFfxNrdBackend() {
        this(false);
    }

    /**
     * @param nrdOnly if true, skip Official FFX entirely (raw layers → NRD). Prefer this for
     *                A/B against hybrid and for day scenes where FFX+NRD+TAA can over-blur.
     */
    public HybridFfxNrdBackend(boolean nrdOnly) {
        this.nrdOnly = nrdOnly;
    }

    public boolean isNrdOnly() {
        return nrdOnly;
    }

    @Override
    public String name() {
        return nrdOnly ? "nrd-only" : "hybrid-ffx-nrd";
    }

    /** Last frame's resolved path label (e.g. {@code nrd}, {@code ffx→nrd}, {@code ffx-only}). */
    public String lastPathLabel() {
        return lastPathLabel;
    }

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        if (!nrdOnly) {
            ffx.init(vkDevice, vkPhysicalDevice);
            ready = ffx.isReady();
        } else {
            // NRD-only still needs prepare/compose pipelines + native REBLUR; mark ready and
            // complete wiring in ensureSized once RtContext exists.
            ready = true;
        }
    }

    public void setSplitBuffers(RtImage shadowHit, RtImage diffuse, RtImage reflection) {
        this.shadowHit = shadowHit;
        this.diffuse = diffuse;
        this.reflection = reflection;
        ffx.setSplitBuffers(shadowHit, diffuse, reflection);
    }

    public void setSpecMotion(RtImage specMotion) {
        ffx.setSpecMotion(specMotion);
    }

    /** Diffuse + specular albedo for NRD material de/re-modulation. */
    public void setAlbedoGuides(RtImage diffuseAlbedo, RtImage specularAlbedo) {
        this.diffAlbedo = diffuseAlbedo;
        this.specAlbedo = specularAlbedo;
    }

    /**
     * Call once per frame before {@link #dispatch}.
     *
     * <p>Caustica traces in <b>camera-relative</b> world space (origin = current camera). NRD's
     * {@code worldToView} is still required so REBLUR can reconstruct positions from viewZ and
     * reject history. With a pure rotation for both current and previous frames NRD believes the
     * camera never translates — walking/strafing then leaves milky trails (villagers, water edges).
     *
     * <p>Fix: {@code worldToView = R_cur}; {@code worldToViewPrev = R_prev} with translation
     * {@code R_prev * camDelta} so a static world point {@code X} (current cam-relative) maps to
     * previous view as {@code R_prev * (X + camDelta)}.
     *
     * <p>Jitter is converted from <b>render pixels</b> to NRD's UV units in {@code [-0.5, 0.5]}
     * ({@code sampleUv = pixelUv + cameraJitter}).
     *
     * @param camDeltaX/Y/Z current camera world position minus previous (blocks), same as rgen push
     */
    public void setCameraFrame(Matrix4fc viewRotation, Matrix4fc projection,
                               Matrix4fc viewRotationPrev, Matrix4fc projectionPrev,
                               float camDeltaX, float camDeltaY, float camDeltaZ,
                               float jitterPixelsX, float jitterPixelsY,
                               int renderW, int renderH) {
        if (haveCamera) {
            System.arraycopy(viewToClip, 0, viewToClipPrev, 0, 16);
            System.arraycopy(worldToView, 0, worldToViewPrev, 0, 16);
            jitterXPrev = jitterX;
            jitterYPrev = jitterY;
        }

        // Camera-relative world (origin = eye): NRD wants pure orthogonal worldToView so its
        // InvertOrtho path stays valid. Camera translation lives in screen-space MVs (rgen),
        // NOT as a fake translation on worldToViewPrev — that poisoned disocclusion and made
        // REBLUR reject history every frame → full SPP firefly grain.
        viewRotation.get(worldToView);
        projection.get(viewToClip);
        if (viewRotationPrev != null) {
            viewRotationPrev.get(worldToViewPrev);
        } else if (!haveCamera) {
            System.arraycopy(worldToView, 0, worldToViewPrev, 0, 16);
        }
        if (projectionPrev != null) {
            projectionPrev.get(viewToClipPrev);
        } else if (!haveCamera) {
            System.arraycopy(viewToClip, 0, viewToClipPrev, 0, 16);
        }

        // NRD: cameraJitter is UV offset in [-0.5, 0.5], NOT pixels.
        float invW = 1.0f / Math.max(1, renderW);
        float invH = 1.0f / Math.max(1, renderH);
        jitterX = jitterPixelsX * invW;
        jitterY = jitterPixelsY * invH;
        if (!haveCamera) {
            jitterXPrev = jitterX;
            jitterYPrev = jitterY;
            System.arraycopy(worldToView, 0, worldToViewPrev, 0, 16);
            System.arraycopy(viewToClip, 0, viewToClipPrev, 0, 16);
        }

        // Large camera jump → hard-reset REBLUR so history doesn't smear across the cut.
        float d2 = camDeltaX * camDeltaX + camDeltaY * camDeltaY + camDeltaZ * camDeltaZ;
        if (Float.isFinite(d2) && d2 > 4.0f) { // >2 blocks/frame
            nrdHardReset = true;
            nrdFrameIndex = 0;
        }
        haveCamera = true;
    }

    /** @deprecated use the overload with camDelta + render size */
    @Deprecated
    public void setCameraFrame(Matrix4fc worldToViewMat, Matrix4fc viewToClipMat,
                               Matrix4fc worldToViewPrevMat, Matrix4fc viewToClipPrevMat,
                               float jitterPixelsX, float jitterPixelsY) {
        setCameraFrame(worldToViewMat, viewToClipMat, worldToViewPrevMat, viewToClipPrevMat,
                0f, 0f, 0f, jitterPixelsX, jitterPixelsY, 1, 1);
    }

    public void requestNrdReset() {
        nrdHardReset = true;
        nrdFrameIndex = 0;
    }

    /** Whether the last {@link #dispatch} produced an NRD-composited beauty plate. */
    public boolean lastNrdOk() {
        return lastNrdOk;
    }

    @Override
    public void ensureSized(int width, int height) {
        if (!nrdOnly) {
            ffx.ensureSized(width, height);
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || (!nrdOnly && !ffx.isReady())) {
            ready = false;
            return;
        }
        NrdRuntime.INSTANCE.tryLoad();
        if (prepPipe == 0L) {
            try {
                createPreparePipeline(ctx);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Hybrid prepare_nrd_inputs pipeline failed; FFX-only hybrid", t);
            }
        }
        if (compPipe == 0L) {
            try {
                createComposePipeline(ctx);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Hybrid nrd_compose pipeline failed", t);
            }
        }
        if (this.width == width && this.height == height && beautyRawCopy != null && nrdDiffuse != null) {
            ready = true;
            return;
        }
        destroyImages();
        beautyRawCopy = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid beauty raw");
        ffxPlate = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid ffx plate");
        nrdDiffuse = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd diffuse");
        nrdSpecular = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd specular");
        viewZ = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT, "hybrid nrd viewZ");
        nrdNormalRough = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd normal");
        nrdOutDiff = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd out diff");
        nrdOutSpec = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd out spec");
        if (nrdOnly) {
            dummyShadowClean = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT,
                    "nrd-only dummy shadow clean");
        }
        this.width = width;
        this.height = height;
        nrdHardReset = true;
        ready = true;
        // Create NRD context when native is present
        try {
            long dev = ctx.vk().address();
            long phys = ctx.vk().getPhysicalDevice().address();
            NrdRuntime.INSTANCE.ensureContext(dev, phys, width, height);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NRD ensureContext at resize failed", t);
        }
    }

    @Override
    public void dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready || outColor == null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return;
        }

        lastNrdOk = false;
        lastPathLabel = nrdOnly ? "nrd-only (pending)" : "hybrid (pending)";

        // Snapshot raw beauty (caller seeds outColor with raw; FFX hybrid may overwrite outColor).
        if (beautyRawCopy != null && inColor != null) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "hybrid snapshot beauty")) {
                copyImage(stack, cmd, inColor, beautyRawCopy);
            }
            barrier(stack, cmd, beautyRawCopy.image);
        }

        // --- Stage 1: Official FFX shadow + reflection (hybrid only) ---
        RtImage cleanS = null;
        RtImage cleanR = null;
        if (!nrdOnly) {
            ffx.dispatch(stack, cmd, inColor, inNormal, inDepth, inMotion, mvScaleX, mvScaleY, outColor);
            if (ffxPlate != null && outColor != null) {
                copyImage(stack, cmd, outColor, ffxPlate);
                barrier(stack, cmd, ffxPlate.image);
            }
            cleanS = ffx.lastCleanShadow();
            cleanR = ffx.lastCleanReflection();
        }

        // --- Stage 2: pack NRD inputs (YCoCg + norm hitDist; G recovered from pre-FFX beauty) ---
        // NRD-only: useRawLayers=1 so prepare reads Sraw/Rraw; clean slots still need valid
        // format-matching images (RG16F / RGBA16F) for descriptor writes.
        // Hybrid: prefer FFX-cleaned layers when available.
        RtImage shadowForNrd;
        RtImage specForNrd;
        if (nrdOnly) {
            shadowForNrd = dummyShadowClean != null ? dummyShadowClean : shadowHit;
            specForNrd = reflection;
        } else {
            shadowForNrd = cleanS != null ? cleanS : shadowHit;
            specForNrd = cleanR != null ? cleanR : reflection;
        }
        if (prepPipe != 0L && beautyRawCopy != null && diffuse != null && shadowHit != null
                && reflection != null && inDepth != null && shadowForNrd != null && specForNrd != null
                && nrdDiffuse != null && nrdSpecular != null && viewZ != null && nrdNormalRough != null
                && diffAlbedo != null && specAlbedo != null && inNormal != null) {
            try {
                bindPrepare(ctx, beautyRawCopy, diffuse, shadowHit, shadowForNrd, reflection, specForNrd, inDepth,
                        nrdDiffuse, nrdSpecular, viewZ, diffAlbedo, specAlbedo, inNormal, nrdNormalRough);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                        nrdOnly ? "nrd-only prepare inputs" : "hybrid prepare NRD inputs")) {
                    VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, prepPipe);
                    VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, prepLayout, 0,
                            stack.longs(prepSet), null);
                    // 32-byte push: near, hitA/B/C, useRawLayers, pad
                    ByteBuffer push = stack.malloc(32);
                    // Hit-distance norm (A,B,C): slightly tighter B for MC block-scale interiors
                    // so REBLUR kernels adapt better to short indoor rays.
                    push.putFloat(0, 0.05f);
                    push.putFloat(4, 2.5f);
                    push.putFloat(8, 0.12f);
                    push.putFloat(12, 16.0f);
                    push.putFloat(16, nrdOnly ? 1.0f : 0.0f);
                    push.putFloat(20, 0.0f);
                    push.putFloat(24, 0.0f);
                    push.putFloat(28, 0.0f);
                    VK10.vkCmdPushConstants(cmd, prepLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                    VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
                }
                barrier(stack, cmd, nrdDiffuse.image);
                barrier(stack, cmd, nrdSpecular.image);
                barrier(stack, cmd, viewZ.image);
                barrier(stack, cmd, nrdNormalRough.image);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Hybrid prepare_nrd_inputs failed", t);
            }
        }

        // --- Stage 3: NRD REBLUR_DIFFUSE_SPECULAR ---
        boolean nrdOk = false;
        RtImage normalForNrd = nrdNormalRough != null ? nrdNormalRough : inNormal;
        if (NrdRuntime.INSTANCE.isAvailable() && haveCamera && nrdDiffuse != null && nrdSpecular != null
                && viewZ != null && nrdOutDiff != null && nrdOutSpec != null && normalForNrd != null && inMotion != null) {
            try {
                long dev = ctx.vk().address();
                long phys = ctx.vk().getPhysicalDevice().address();
                if (!NrdRuntime.INSTANCE.ensureContext(dev, phys, width, height)) {
                    throw new IllegalStateException("NRD context not ready");
                }
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "hybrid NRD REBLUR")) {
                    int rc = NrdRuntime.INSTANCE.dispatch(cmd.address(),
                            nrdDiffuse.image, nrdDiffuse.view,
                            nrdSpecular.image, nrdSpecular.view,
                            inMotion.image, inMotion.view,
                            normalForNrd.image, normalForNrd.view,
                            viewZ.image, viewZ.view,
                            nrdOutDiff.image, nrdOutDiff.view,
                            nrdOutSpec.image, nrdOutSpec.view,
                            viewToClip, viewToClipPrev, worldToView, worldToViewPrev,
                            jitterX, jitterY, jitterXPrev, jitterYPrev,
                            nrdFrameIndex, nrdHardReset);
                    if (rc != 0) {
                        throw new IllegalStateException("nrd dispatch rc=" + rc);
                    }
                }
                barrier(stack, cmd, nrdOutDiff.image);
                barrier(stack, cmd, nrdOutSpec.image);
                // Radiance: clear radiance = path-traced plate (pre-FFX raw) for sky; surface = NRD
                RtImage clear = beautyRawCopy != null ? beautyRawCopy : ffxPlate;
                if (compPipe != 0L && diffAlbedo != null && specAlbedo != null && clear != null) {
                    bindCompose(ctx, nrdOutDiff, nrdOutSpec, diffAlbedo, specAlbedo, inNormal, clear, outColor);
                    try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "hybrid NRD compose beauty")) {
                        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compPipe);
                        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compLayout, 0,
                                stack.longs(compSet), null);
                        ByteBuffer cpush = stack.malloc(16);
                        // detailMix=0: never re-inject raw path-traced grain over REBLUR.
                        cpush.putFloat(0, 1.0f); // surfaceGain
                        cpush.putFloat(4, 1.05f); // skyGain
                        cpush.putFloat(8, 0.0f); // detailMix OFF
                        cpush.putFloat(12, 0.0f);
                        VK10.vkCmdPushConstants(cmd, compLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, cpush);
                        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
                    }
                    barrier(stack, cmd, outColor.image);
                }
                nrdHardReset = false;
                nrdFrameIndex++;
                nrdOk = true;
                lastNrdOk = true;
                lastPathLabel = nrdOnly ? "nrd" : "ffx→nrd";
                if (!nrdRanLogged) {
                    nrdRanLogged = true;
                    if (nrdOnly) {
                        CausticaMod.LOGGER.info(
                                "Denoise: NRD REBLUR HQ (no FFX prepass; no beauty TAA)");
                    } else {
                        CausticaMod.LOGGER.info(
                                "Hybrid denoise: FFX prepass + NRD REBLUR HQ");
                    }
                }
            } catch (Throwable t) {
                if (!nrdFailLogged) {
                    nrdFailLogged = true;
                    CausticaMod.LOGGER.warn(
                            nrdOnly ? "NRD-only stage failed; keeping raw beauty" : "Hybrid NRD stage failed; keeping FFX beauty",
                            t);
                }
                lastPathLabel = nrdOnly ? "raw (nrd fail)" : "ffx-only (nrd fail)";
            }
        }
        if (!nrdOk && !nrdRanLogged && !nrdFailLogged) {
            // Once: FFX-only path (NRD native missing) or raw if nrd-only
            nrdFailLogged = true;
            if (nrdOnly) {
                lastPathLabel = "raw (nrd unavailable)";
                CausticaMod.LOGGER.info(
                        "Denoise: NRD-only requested but native unavailable; raw beauty + TAA");
            } else {
                lastPathLabel = "ffx-only";
                CausticaMod.LOGGER.info(
                        "Hybrid denoise: FFX prepass only (NRD native unavailable); beauty TAA still applies");
            }
        } else if (!nrdOk && !nrdOnly) {
            lastPathLabel = "ffx-only";
        } else if (!nrdOk) {
            lastPathLabel = "raw (nrd fail)";
        }
    }

    @Override
    public void resetHistory() {
        if (!nrdOnly) {
            ffx.resetHistory();
        }
        requestNrdReset();
    }

    @Override
    public void destroy() {
        if (!nrdOnly) {
            ffx.destroy();
        }
        NrdRuntime.INSTANCE.destroy();
        RtContext ctx = RtContext.get();
        destroyImages();
        if (ctx != null) {
            if (prepPipe != 0L) {
                VK10.vkDestroyPipeline(ctx.vk(), prepPipe, null);
                VK10.vkDestroyPipelineLayout(ctx.vk(), prepLayout, null);
                VK10.vkDestroyDescriptorPool(ctx.vk(), prepPool, null);
                VK10.vkDestroyDescriptorSetLayout(ctx.vk(), prepDsl, null);
                prepPipe = prepLayout = prepPool = prepDsl = prepSet = 0L;
            }
            if (compPipe != 0L) {
                VK10.vkDestroyPipeline(ctx.vk(), compPipe, null);
                VK10.vkDestroyPipelineLayout(ctx.vk(), compLayout, null);
                VK10.vkDestroyDescriptorPool(ctx.vk(), compPool, null);
                VK10.vkDestroyDescriptorSetLayout(ctx.vk(), compDsl, null);
                compPipe = compLayout = compPool = compDsl = compSet = 0L;
            }
        }
        ready = false;
        width = 0;
        height = 0;
        haveCamera = false;
    }

    @Override
    public boolean isReady() {
        return ready && (nrdOnly || ffx.isReady());
    }

    private void destroyImages() {
        if (beautyRawCopy != null) {
            beautyRawCopy.destroy();
            beautyRawCopy = null;
        }
        if (ffxPlate != null) {
            ffxPlate.destroy();
            ffxPlate = null;
        }
        if (nrdDiffuse != null) {
            nrdDiffuse.destroy();
            nrdDiffuse = null;
        }
        if (nrdSpecular != null) {
            nrdSpecular.destroy();
            nrdSpecular = null;
        }
        if (viewZ != null) {
            viewZ.destroy();
            viewZ = null;
        }
        if (nrdNormalRough != null) {
            nrdNormalRough.destroy();
            nrdNormalRough = null;
        }
        if (nrdOutDiff != null) {
            nrdOutDiff.destroy();
            nrdOutDiff = null;
        }
        if (nrdOutSpec != null) {
            nrdOutSpec.destroy();
            nrdOutSpec = null;
        }
        if (dummyShadowClean != null) {
            dummyShadowClean.destroy();
            dummyShadowClean = null;
        }
    }

    private void createComposePipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int BINDINGS = 7;
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer pDsl = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, pDsl),
                    "vkCreateDescriptorSetLayout(hybrid-comp)");
            compDsl = pDsl.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDINGS);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(ctx.vk(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pPool),
                    "vkCreateDescriptorPool(hybrid-comp)");
            compPool = pPool.get(0);
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(compPool).pSetLayouts(stack.longs(compDsl)), pSet),
                    "vkAllocateDescriptorSets(hybrid-comp)");
            compSet = pSet.get(0);
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(ctx.vk(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(compDsl)).pPushConstantRanges(pcr), null, pLayout),
                    "vkCreatePipelineLayout(hybrid-comp)");
            compLayout = pLayout.get(0);
            byte[] spv;
            try (InputStream in = HybridFfxNrdBackend.class.getResourceAsStream(
                    "/caustica/rt/nrd_compose_beauty.comp.spv")) {
                if (in == null) {
                    throw new IllegalStateException("missing nrd_compose_beauty.comp.spv");
                }
                spv = in.readAllBytes();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            ByteBuffer code = MemoryUtil.memAlloc(spv.length);
            code.put(spv).flip();
            LongBuffer pMod = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(ctx.vk(),
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pMod),
                    "vkCreateShaderModule(hybrid-comp)");
            long mod = pMod.get(0);
            MemoryUtil.memFree(code);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod).pName(stack.UTF8("main"));
            LongBuffer pPipe = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(ctx.vk(), 0,
                    VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                            .stage(stage).layout(compLayout), null, pPipe),
                    "vkCreateComputePipelines(hybrid-comp)");
            compPipe = pPipe.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), mod, null);
        }
    }

    private void bindCompose(RtContext ctx, RtImage diff, RtImage spec, RtImage diffAlb, RtImage specAlb,
                             RtImage normal, RtImage ffx, RtImage out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] imgs = {diff, spec, diffAlb, specAlb, normal, ffx, out};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(imgs[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(compSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void createPreparePipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int BINDINGS = 14; // + gNrdNormalRough
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer pDsl = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, pDsl),
                    "vkCreateDescriptorSetLayout(hybrid-prep)");
            prepDsl = pDsl.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDINGS);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(ctx.vk(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pPool),
                    "vkCreateDescriptorPool(hybrid-prep)");
            prepPool = pPool.get(0);

            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(prepPool).pSetLayouts(stack.longs(prepDsl)), pSet),
                    "vkAllocateDescriptorSets(hybrid-prep)");
            prepSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            // near, hitA/B/C, useRawLayers + pad (matches prepare_nrd_inputs.comp)
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(32);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(ctx.vk(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(prepDsl)).pPushConstantRanges(pcr), null, pLayout),
                    "vkCreatePipelineLayout(hybrid-prep)");
            prepLayout = pLayout.get(0);

            byte[] spv;
            try (InputStream in = HybridFfxNrdBackend.class.getResourceAsStream(
                    "/caustica/rt/prepare_nrd_inputs.comp.spv")) {
                if (in == null) {
                    throw new IllegalStateException("missing prepare_nrd_inputs.comp.spv");
                }
                spv = in.readAllBytes();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            ByteBuffer code = MemoryUtil.memAlloc(spv.length);
            code.put(spv).flip();
            LongBuffer pMod = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(ctx.vk(),
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pMod),
                    "vkCreateShaderModule(hybrid-prep)");
            long mod = pMod.get(0);
            MemoryUtil.memFree(code);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(mod)
                    .pName(stack.UTF8("main"));
            LongBuffer pPipe = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(ctx.vk(), 0,
                    VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                            .stage(stage).layout(prepLayout), null, pPipe),
                    "vkCreateComputePipelines(hybrid-prep)");
            prepPipe = pPipe.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), mod, null);
        }
    }

    private void bindPrepare(RtContext ctx, RtImage beauty, RtImage unshadowed, RtImage shadowRaw,
                             RtImage shadowClean, RtImage specRaw, RtImage specClean, RtImage depth,
                             RtImage nrdDiff, RtImage nrdSpec, RtImage vz,
                             RtImage diffAlb, RtImage specAlb, RtImage normal, RtImage nrdNormal) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] imgs = {beauty, unshadowed, shadowRaw, shadowClean, specRaw, specClean, depth,
                    nrdDiff, nrdSpec, vz, diffAlb, specAlb, normal, nrdNormal};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(imgs[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(prepSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private static void copyImage(MemoryStack stack, VkCommandBuffer cmd, RtImage src, RtImage dst) {
        org.lwjgl.vulkan.VkImageCopy.Buffer region = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.dstSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.extent().set(src.width, src.height, 1);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
    }

    private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(1, stack);
        barriers.get(0).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }
}

