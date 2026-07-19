package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
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
import java.util.Arrays;

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

    // v0.6: FFX denoiser disabled at source level. The SPIR-V shadow+reflection filters were
    // amplifying SPP=1 noise on the way into NRD REBLUR (the prepass weights its input
    // by the FFX shadow/reflection channels, which carry their own per-sample variance
    // at SPP=1). HybridFfxNrdBackend now runs NRD only -- the FFX prepass is bypassed
    // unconditionally. The ffx field/usage lines below are commented out but kept
    // for reference in case the upstream FFX issues are fixed and a future flag re-enables.
    // private final OfficialFfxDenoiseBackend ffx = new OfficialFfxDenoiseBackend();
    /** When true, skip FFX prepass and feed raw S/R into prepare (config denoise=NRD). */
    private final boolean nrdOnly;
    private boolean ready;
    private int width;
    private int height;
    private boolean nrdRanLogged;
    private boolean nrdFailLogged;
    /** True if last dispatch finished REBLUR + compose (caller uses light residual TAA). */
    private volatile boolean lastNrdOk;
    private volatile boolean lastPrepareOk;
    private volatile boolean lastDispatchOk;
    private volatile boolean lastComposeOk;
    /** What the last frame actually did, for the debug overlay. */
    private volatile String lastPathLabel = "idle";

    private RtImage shadowHit;
    private RtImage diffuse;
    private RtImage reflection;
    private RtImage unshadowedDirect;
    private RtImage diffAlbedo;
    private RtImage specAlbedo;
    private RtImage beautyRawCopy; // pre-FFX beauty (for G recovery)
    private RtImage ffxPlate;      // post-FFX beauty (sky fallback)
    private RtImage nrdDiffuse;
    private RtImage nrdSpecular;
    private RtImage viewZ;
    private RtImage tracedViewZ;
    private RtImage materialFlags;
    private RtImage clearEmission;
    private RtImage transmission;
    private RtImage confidenceDisocclusion;
    private RtImage nrdNormalRough; // NRD best-fit packed normal+rough
    private RtImage nrdOutDiff;
    private RtImage nrdOutSpec;
    private RtImage sigmaPenumbra;
    private RtImage diffConfidence;
    private RtImage specConfidence;
    private RtImage disocclusionMix;
    private RtImage demodulationMask;
    private RtImage nrdOutShadow;
    /**
     * Dummy RG16F shadow-clean target for NRD-only (prepare never reads it when useRawLayers=1).
     * Avoids binding R8 raw shadow into a rg16f descriptor slot.
     */
    private RtImage dummyShadowClean;
    private RtImage transparencyMask; // R8: 1.0 = transparent (glass/water), 0.0 = opaque

    // Transparent material denoiser (glass/water/ice specialist)
    private final TransparentMaterialDenoiser transparentDenoiser = new TransparentMaterialDenoiser();
    private RtImage transparentResult; // denoised transparent materials

    // Camera for NRD common settings (column-major 4x4)
    private final float[] viewToClip = new float[16];
    private final float[] viewToClipPrev = new float[16];
    private final float[] worldToView = new float[16];
    private final float[] worldToViewPrev = new float[16];
    private float jitterX, jitterY, jitterXPrev, jitterYPrev;
    private float lightDirX, lightDirY = 1.0f, lightDirZ;
    private int nrdFrameIndex;
    private boolean nrdHardReset = true;
    private boolean haveCamera;

    // Temporal warmup: gradually blend in history over first N frames to eliminate startup flicker
    private int warmupFramesRemaining = 8;
    private static final int WARMUP_DURATION = 8;

    private long prepDsl, prepPool, prepSet, prepLayout, prepPipe;
    private long compDsl, compPool, compSet, compLayout, compPipe;
    private final long[] prepBindings = new long[22];
    private final long[] compBindings = new long[14];

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
        // v0.6: FFX disabled. NRD-only path always marks itself ready; the hybrid mode
        // (nrdOnly=false) used to chain FFX for shadow+reflection prepass, but that's been
        // bypassed too -- we just run NRD on the raw inputs.
        ready = true;
        transparentDenoiser.init();
    }

    public void setSplitBuffers(RtImage shadowHit, RtImage diffuse, RtImage reflection) {
        this.shadowHit = shadowHit;
        this.diffuse = diffuse;
        this.reflection = reflection;
        // ffx.setSplitBuffers(shadowHit, diffuse, reflection);  // v0.6: FFX disabled
    }

    public void setUnshadowedDirectGuide(RtImage unshadowedDirect) {
        this.unshadowedDirect = unshadowedDirect;
    }

    public void setConfidenceDisocclusionGuide(RtImage confidenceDisocclusion) {
        this.confidenceDisocclusion = confidenceDisocclusion;
    }

    public void setLightDirection(float x, float y, float z) {
        this.lightDirX = x;
        this.lightDirY = y;
        this.lightDirZ = z;
    }

    public void setSpecMotion(RtImage specMotion) {
        // ffx.setSpecMotion(specMotion);  // v0.6: FFX disabled
    }

    /** Diffuse + specular albedo for NRD material de/re-modulation. */
    public void setAlbedoGuides(RtImage diffuseAlbedo, RtImage specularAlbedo) {
        this.diffAlbedo = diffuseAlbedo;
        this.specAlbedo = specularAlbedo;
    }

    /** Linear positive view Z emitted directly by raygen. */
    public void setViewZGuide(RtImage viewZ) {
        this.tracedViewZ = viewZ;
    }

    public void setMaterialFlagsGuide(RtImage materialFlags) {
        this.materialFlags = materialFlags;
    }

    public void setClearEmissionGuide(RtImage clearEmission) {
        this.clearEmission = clearEmission;
    }

    public void setTransmissionGuide(RtImage transmission) {
        this.transmission = transmission;
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
        warmupFramesRemaining = WARMUP_DURATION; // restart warmup on reset
    }

    /** Whether the last {@link #dispatch} produced an NRD-composited beauty plate. */
    public boolean lastNrdOk() {
        return lastNrdOk;
    }

    public boolean lastPrepareOk() { return lastPrepareOk; }

    public boolean lastDispatchOk() { return lastDispatchOk; }

    public boolean lastComposeOk() { return lastComposeOk; }

    @Override
    public void ensureSized(int width, int height) {
        // v0.6: FFX disabled. We still need to wire the NRD prepare/compose pipelines + native
        // REBLUR for the NRD-only path.
        RtContext ctx = RtContext.get();
        if (ctx == null) {
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
        // Beauty plates match the RT output format (B10G11R11). NRD intermediate packs stay RGBA16F
        // (YCoCg + normHitDist / signed normal).
        beautyRawCopy = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "hybrid beauty raw");
        ffxPlate = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "hybrid ffx plate");
        nrdDiffuse = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd diffuse");
        nrdSpecular = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd specular");
        viewZ = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT, "hybrid nrd viewZ");
        nrdNormalRough = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd normal");
        nrdOutDiff = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd out diff");
        nrdOutSpec = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "hybrid nrd out spec");
        nrdOutShadow = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "nrd sigma shadow");
        sigmaPenumbra = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16_SFLOAT, "nrd sigma penumbra");
        diffConfidence = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8_UNORM, "nrd diffuse confidence");
        specConfidence = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8_UNORM, "nrd specular confidence");
        disocclusionMix = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8_UNORM, "nrd disocclusion mix");
        demodulationMask = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8_UNORM, "nrd demodulation mask");
        dummyShadowClean = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT,
                "nrd dummy shadow clean");
        transparencyMask = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8_UNORM, "transparency mask");
        transparentResult = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "transparent denoised");
        this.width = width;
        this.height = height;
        nrdHardReset = true;
        ready = true;
        transparentDenoiser.ensureSized(width, height);
        // Create NRD context when native is present
        try {
            long dev = ctx.vk().address();
            long phys = ctx.vk().getPhysicalDevice().address();
            int graphicsFamily = ctx.device().graphicsQueue().queueFamilyIndex();
            // NRD commands are recorded into the graphics command buffer and submitted on
            // the graphics queue.  The async-compute queue is not used by this backend yet;
            // advertising it here makes the native shim create concurrent-sharing resources
            // even though no ownership transfer is performed, which breaks on AMD drivers.
            NrdRuntime.INSTANCE.ensureContext(dev, phys, graphicsFamily, -1, width, height);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NRD ensureContext at resize failed", t);
        }
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready || outColor == null) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }

        lastNrdOk = false;
        lastPrepareOk = false;
        lastDispatchOk = false;
        lastComposeOk = false;
        lastPathLabel = nrdOnly ? "nrd-only (pending)" : "hybrid (pending)";

        // A forced NRD mode with a missing/incompatible native must be a true no-op. Recording
        // prepare/transparent work before eventually returning false can still mutate auxiliary
        // resources and stress a driver even though RtComposite correctly keeps raw beauty.
        if (!NrdRuntime.INSTANCE.isAvailable()) {
            lastPathLabel = nrdOnly ? "raw (nrd unavailable)" : "ffx-only (nrd unavailable)";
            if (!nrdFailLogged) {
                nrdFailLogged = true;
                CausticaMod.LOGGER.info("NRD native unavailable; skipping every NRD prepare/dispatch/compose command");
            }
            return false;
        }

        // Snapshot raw beauty (caller seeds outColor with raw; FFX hybrid may overwrite outColor).
        if (beautyRawCopy != null && inColor != null) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "hybrid snapshot beauty")) {
                copyImage(stack, cmd, inColor, beautyRawCopy);
            }
            barrier(stack, cmd, beautyRawCopy.image);
        }

        // --- Stage 1: Official FFX shadow + reflection (hybrid only) ---
        // v0.6: FFX disabled at source level. The hybrid mode no longer runs FFX for
        // shadow/reflection; the prepare pass gets the raw split buffers directly.
        RtImage cleanS = null;
        RtImage cleanR = null;
        // ffx.dispatch(stack, cmd, inColor, inNormal, inDepth, inMotion, mvScaleX, mvScaleY, outColor);
        // if (ffxPlate != null && outColor != null) { copyImage(stack, cmd, outColor, ffxPlate); barrier(stack, cmd, ffxPlate.image); }
        // cleanS = ffx.lastCleanShadow();
        // cleanR = ffx.lastCleanReflection();

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
                && diffAlbedo != null && specAlbedo != null && inNormal != null && transparencyMask != null
                && materialFlags != null && confidenceDisocclusion != null && sigmaPenumbra != null
                && diffConfidence != null && specConfidence != null && disocclusionMix != null
                && demodulationMask != null) {
            try {
                bindPrepare(ctx, beautyRawCopy, diffuse, shadowHit, shadowForNrd, reflection, specForNrd, inDepth,
                        nrdDiffuse, nrdSpecular, viewZ, diffAlbedo, specAlbedo, inNormal, nrdNormalRough,
                        transparencyMask, materialFlags, confidenceDisocclusion, sigmaPenumbra,
                        diffConfidence, specConfidence, disocclusionMix, demodulationMask);
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
                // Batched: 5 prepare-outputs barrier in one vkCmdPipelineBarrier2KHR call. Was 5 separate
                // barriers (one per image) — same semantics, ~4 fewer command-buffer entries + driver
                // dependency records. All 5 images are written by the dispatch above and read by either
                // the transparent denoiser (transparencyMask) or NRD REBLUR (the rest); batching lets the
                // GPU scheduler see them as one dependency group.
                barriers(stack, cmd, nrdDiffuse.image, nrdSpecular.image, viewZ.image,
                        nrdNormalRough.image, transparencyMask.image, sigmaPenumbra.image,
                        diffConfidence.image, specConfidence.image, disocclusionMix.image,
                        demodulationMask.image);
                lastPrepareOk = true;
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Hybrid prepare_nrd_inputs failed", t);
            }
        }

        // --- Stage 3: Transparent material denoiser (glass/water/ice) ---
        // Run BEFORE NRD so we have a clean transparent result to blend later.
        // No barrier between this and NRD: transparent writes transparentResult, NRD writes
        // nrdOutDiff/nrdOutSpec — disjoint outputs, disjoint inputs (no shared writes). The Vulkan
        // command buffer orders them sequentially, but the GPU scheduler is now free to overlap
        // execution if the hardware has spare lanes. Saves one vkCmdPipelineBarrier2KHR per frame
        // (~5-10 µs of CPU overhead, plus removes a sync point that was preventing out-of-order
        // dispatch on the same queue).
        if (transparentDenoiser != null && transparencyMask != null && beautyRawCopy != null &&
            inNormal != null && inDepth != null && inMotion != null && transparentResult != null) {
            try {
                transparentDenoiser.dispatch(stack, cmd, transparencyMask, beautyRawCopy, inNormal,
                        inDepth, inMotion, mvScaleX, mvScaleY, transparentResult);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Transparent material denoiser failed", t);
            }
        }

        // --- Stage 4: NRD REBLUR_DIFFUSE_SPECULAR (opaque surfaces only) ---
        boolean nrdOk = false;
        RtImage normalForNrd = nrdNormalRough != null ? nrdNormalRough : inNormal;
        RtImage viewZForNrd = tracedViewZ != null ? tracedViewZ : viewZ;
        if (lastPrepareOk && NrdRuntime.INSTANCE.isAvailable() && haveCamera && nrdDiffuse != null && nrdSpecular != null
                && viewZForNrd != null && nrdOutDiff != null && nrdOutSpec != null && normalForNrd != null && inMotion != null) {
            try {
                long dev = ctx.vk().address();
                long phys = ctx.vk().getPhysicalDevice().address();
                int graphicsFamily = ctx.device().graphicsQueue().queueFamilyIndex();
                // Keep NRD on the queue that actually submits this command buffer.  A
                // dedicated compute family is detected globally, but is not wired into the
                // NRD submission/ownership-transfer path.
                if (!NrdRuntime.INSTANCE.ensureContext(dev, phys, graphicsFamily, -1, width, height)) {
                    throw new IllegalStateException("NRD context not ready");
                }
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "hybrid NRD REBLUR")) {
                    // Temporal warmup: gradually blend history over first WARMUP_DURATION frames
                    // to eliminate startup/teleport flicker. We manipulate frameIndex to reduce
                    // NRD's temporal weight during warmup (lower frameIndex = less history trust).
                    int effectiveFrameIndex = nrdFrameIndex;
                    if (warmupFramesRemaining > 0) {
                        // Remap frameIndex: warmup frame 0→0, frame 4→2, frame 8→8 (linear blend)
                        effectiveFrameIndex = (WARMUP_DURATION - warmupFramesRemaining) * nrdFrameIndex / WARMUP_DURATION;
                        warmupFramesRemaining--;
                    }

                    int rc = NrdRuntime.INSTANCE.dispatch(cmd.address(),
                            nrdDiffuse.image, nrdDiffuse.view,
                            nrdSpecular.image, nrdSpecular.view,
                            inMotion.image, inMotion.view,
                            normalForNrd.image, normalForNrd.view,
                            viewZForNrd.image, viewZForNrd.view,
                            sigmaPenumbra.image, sigmaPenumbra.view,
                            diffConfidence.image, diffConfidence.view,
                            specConfidence.image, specConfidence.view,
                            disocclusionMix.image, disocclusionMix.view,
                            nrdOutDiff.image, nrdOutDiff.view,
                            nrdOutSpec.image, nrdOutSpec.view,
                            nrdOutShadow.image, nrdOutShadow.view,
                            viewToClip, viewToClipPrev, worldToView, worldToViewPrev,
                            jitterX, jitterY, jitterXPrev, jitterYPrev,
                            lightDirX, lightDirY, lightDirZ,
                            effectiveFrameIndex, nrdHardReset);
                    if (rc != 0) {
                        throw new IllegalStateException("nrd dispatch rc=" + rc);
                    }
                    lastDispatchOk = true;
                }
                // Batched: NRD outputs + transparent result (if it ran) all need to be visible to the compose
                // pass. Was 2 barriers (one per NRD output) + 1 separate transparent barrier earlier;
                // now all three are gated by a single vkCmdPipelineBarrier2KHR call so the driver
                // schedules them as one dependency.
                if (transparentResult != null) {
                    barriers(stack, cmd, nrdOutDiff.image, nrdOutSpec.image, nrdOutShadow.image,
                            transparentResult.image);
                } else {
                    barriers(stack, cmd, nrdOutDiff.image, nrdOutSpec.image, nrdOutShadow.image);
                }
                // Radiance: clear radiance = path-traced plate (pre-FFX raw) for sky; surface = NRD
                RtImage clear = beautyRawCopy != null ? beautyRawCopy : ffxPlate;
                if (compPipe != 0L && diffAlbedo != null && specAlbedo != null && clear != null &&
                    transparencyMask != null && transparentResult != null && clearEmission != null
                    && transmission != null && nrdOutShadow != null && unshadowedDirect != null
                    && demodulationMask != null) {
                    bindCompose(ctx, nrdOutDiff, nrdOutSpec, diffAlbedo, specAlbedo, inNormal, clear,
                            transparencyMask, transparentResult, outColor, clearEmission, transmission,
                            nrdOutShadow, unshadowedDirect, demodulationMask);
                    try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "hybrid NRD compose beauty")) {
                        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compPipe);
                        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compLayout, 0,
                                stack.longs(compSet), null);
                        ByteBuffer cpush = stack.malloc(16);
                        // Push constants: surfaceGain, skyGain, detailMix (unused), debugView
                        cpush.putFloat(0, 1.0f); // surfaceGain
                        cpush.putFloat(4, 1.05f); // skyGain
                        cpush.putFloat(8, 0.0f); // detailMix (unused, kept for layout)
                        // Fetch debugView via the static helper (same pattern as RtComposite)
                        int debugViewValue = CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
                        cpush.putInt(12, debugViewValue); // debugView for extended visualization (8-14)
                        VK10.vkCmdPushConstants(cmd, compLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, cpush);
                        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
                    }
                    barrier(stack, cmd, outColor.image);
                    lastComposeOk = true;
                }
                if (!lastComposeOk) {
                    throw new IllegalStateException("NRD compose pipeline unavailable");
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
                        "Denoise: NRD-only context not ready; raw beauty + TAA");
            } else {
                lastPathLabel = "ffx-only";
                CausticaMod.LOGGER.info(
                        "Hybrid denoise: NRD context not ready; beauty TAA still applies");
            }
        } else if (!nrdOk && !nrdOnly) {
            lastPathLabel = "ffx-only";
        } else if (!nrdOk) {
            lastPathLabel = "raw (nrd fail)";
        }
        return nrdOk;
    }

    @Override
    public void resetHistory() {
        // v0.6: FFX disabled. The ffx reset path is bypassed unconditionally.
        // if (!nrdOnly) { ffx.resetHistory(); }
        requestNrdReset();
        transparentDenoiser.resetHistory();
    }

    @Override
    public void destroy() {
        // v0.6: FFX disabled. The ffx.destroy path is bypassed unconditionally.
        // if (!nrdOnly) { ffx.destroy(); }
        NrdRuntime.INSTANCE.destroy();
        transparentDenoiser.destroy();
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
        // v0.6: FFX disabled. The ffx instance is no longer created, so isReady just
        // returns our own ready flag. (Hybrid mode nrdOnly=false used to require both
        // the FFX prepass pipelines AND the NRD pipelines; now NRD alone suffices.)
        return ready;
    }

    @Override
    public boolean supportsAsyncCompute() {
        return true; // NRD is compute-based, supports async
    }

    @Override
    public void dispatchAsync(
            VkCommandBuffer computeCmd,
            dev.comfyfluffy.caustica.rt.RtAsyncCompute asyncCompute,
            MemoryStack stack,
            RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
            float mvScaleX, float mvScaleY,
            RtImage outColor) {
        // NRD/Hybrid is compute-based - can directly use compute command buffer
        dispatch(stack, computeCmd, inColor, inNormal, inDepth, inMotion, mvScaleX, mvScaleY, outColor);
    }

    private void destroyImages() {
        Arrays.fill(prepBindings, 0L);
        Arrays.fill(compBindings, 0L);
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
        if (nrdOutShadow != null) { nrdOutShadow.destroy(); nrdOutShadow = null; }
        if (sigmaPenumbra != null) { sigmaPenumbra.destroy(); sigmaPenumbra = null; }
        if (diffConfidence != null) { diffConfidence.destroy(); diffConfidence = null; }
        if (specConfidence != null) { specConfidence.destroy(); specConfidence = null; }
        if (disocclusionMix != null) { disocclusionMix.destroy(); disocclusionMix = null; }
        if (demodulationMask != null) { demodulationMask.destroy(); demodulationMask = null; }
        if (dummyShadowClean != null) {
            dummyShadowClean.destroy();
            dummyShadowClean = null;
        }
        if (transparencyMask != null) {
            transparencyMask.destroy();
            transparencyMask = null;
        }
        if (transparentResult != null) {
            transparentResult.destroy();
            transparentResult = null;
        }
    }

    private void createComposePipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int BINDINGS = 14; // + SIGMA shadow, unshadowed direct, demodulation mask
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
                             RtImage normal, RtImage ffx, RtImage transMask, RtImage transResult,
                             RtImage out, RtImage clearEmission, RtImage transmission,
                             RtImage sigmaShadow, RtImage unshadowedDirect, RtImage demodulationMask) {
        RtImage[] imgs = {diff, spec, diffAlb, specAlb, normal, ffx, transMask, transResult, out,
                clearEmission, transmission, sigmaShadow, unshadowedDirect, demodulationMask};
        long[] views = Arrays.stream(imgs).mapToLong(image -> image.view).toArray();
        if (Arrays.equals(compBindings, views)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(imgs[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(compSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            System.arraycopy(views, 0, compBindings, 0, views.length);
        }
    }

    private void createPreparePipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int BINDINGS = 22; // standard NRD inputs + confidence/disocclusion/demodulation
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
                             RtImage diffAlb, RtImage specAlb, RtImage normal, RtImage nrdNormal,
                             RtImage transMask, RtImage materialFlags, RtImage confidenceDisocclusion,
                             RtImage sigmaPenumbra, RtImage diffConfidence, RtImage specConfidence,
                             RtImage disocclusionMix, RtImage demodulationMask) {
        RtImage[] imgs = {beauty, unshadowed, shadowRaw, shadowClean, specRaw, specClean, depth,
                nrdDiff, nrdSpec, vz, diffAlb, specAlb, normal, nrdNormal, transMask, materialFlags,
                confidenceDisocclusion, sigmaPenumbra, diffConfidence, specConfidence,
                disocclusionMix, demodulationMask};
        long[] views = Arrays.stream(imgs).mapToLong(image -> image.view).toArray();
        if (Arrays.equals(prepBindings, views)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(imgs[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(prepSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            System.arraycopy(views, 0, prepBindings, 0, views.length);
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
        barriers(stack, cmd, image);
    }

    /**
     * Batched image barrier — packs N image transitions into one {@code vkCmdPipelineBarrier2KHR} call.
     * Saves (N-1) per-call command-buffer overhead and gives the driver one dependency-info record
     * to schedule, instead of N small ones. Layout/access/stage flags are identical to the single-image
     * version: compute-write → compute-read|write, GENERAL→GENERAL (these images stay in GENERAL layout
     * throughout the denoise block).
     */
    private static void barriers(MemoryStack stack, VkCommandBuffer cmd, long... images) {
        if (images.length == 0) {
            return;
        }
        VkImageMemoryBarrier2.Buffer bars = VkImageMemoryBarrier2.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            bars.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(bars);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }
}
