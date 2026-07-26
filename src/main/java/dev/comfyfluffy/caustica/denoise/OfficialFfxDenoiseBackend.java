package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.ffx.denoiser.FfxDenoiserRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * FidelityFX-style Shadow + Reflection denoiser (Caustica-hosted SPIR-V).
 *
 * <p>P0 alignment with official FFX Denoiser docs:
 * <ul>
 *   <li>Shadow prepare: pack 8×4 hitmask + dense float shadow</li>
 *   <li>Shadow reproject: local moments / variance boost / neighborhood clamp</li>
 *   <li>Shadow spatial: 3-pass EAW with variance cooling</li>
 *   <li>Reflection: depth hierarchy (3 half-res mips) for disocclusion</li>
 *   <li>Energy-correct composite (never multiplies GI by shadow)</li>
 * </ul>
 *
 * <p>Fail-open: seed with raw beauty; any filter failure leaves that seed.
 */
public final class OfficialFfxDenoiseBackend implements CausticaDenoiseBackend {

    private static final String SHADER_DIR = "/caustica/rt/";
    private static final int[] SPATIAL_STEPS = {1, 2, 4};

    private boolean ready;
    private int width;
    private int height;
    private boolean shadowHistoryValid;
    private boolean reflHistoryValid;
    private boolean hardReset;
    /** Clear history/spat buffers once after allocate or reset (avoid garbage dark deltas). */
    private boolean needsHistoryClear;

    private RtImage shadowHit;
    /** Unshadowed primary direct (for shadow delta). Prefer gUnshadowedDirect over gDiffuse. */
    private RtImage unshadowedDirect;
    private RtImage diffuseFallback; // gDiffuse — only used if unshadowedDirect is null
    private RtImage reflection;   // raw specular
    private RtImage specMotion;   // optional; falls back to surface motion

    private RtImage lastCleanShadow;
    private RtImage lastCleanReflection;
    private int lastCompositeFlags;

    // Pipelines: prepare, shadow reproject/spatial, depth pyramid, reflection reproject/spatial, composite
    private long prepDsl, prepPool, prepSet, prepLayout, prepPipe;
    private long shReproDsl, shReproPool, shReproSet, shReproLayout, shReproPipe;
    private long shSpatDsl, shSpatPool, shSpatLayout, shSpatPipe;
    private long[] shSpatSets = new long[0];
    private long depthPyrDsl, depthPyrPool, depthPyrLayout, depthPyrPipe;
    private long[] depthPyrSets = new long[0];
    private long rfReproDsl, rfReproPool, rfReproSet, rfReproLayout, rfReproPipe;
    private long rfSpatDsl, rfSpatPool, rfSpatLayout, rfSpatPipe;
    private long[] rfSpatSets = new long[0];
    private long compDsl, compPool, compLayout, compPipe;
    private long[] compSets = new long[0];

    /**
     * Descriptor contents are execution-time state in Vulkan, not command-recording snapshots.
     * Cache stable bindings so a later frame never updates a set still referenced by the GPU.
     */
    private final Map<Long, long[]> descriptorBindings = new HashMap<>();

    // Shadow buffers (rgba16f: r=mask, g=count, b=variance, a=mean)
    private RtImage hitMask;       // r32ui tiles (ceil(w/8) x ceil(h/4))
    private RtImage shadowDense;   // rgba16f dense float from prepare
    private RtImage historyShadow;
    private RtImage shReproBuf;
    private RtImage shSpatA;
    private RtImage shSpatB;
    // Depth hierarchy
    private RtImage depthMip1;
    private RtImage depthMip2;
    private RtImage depthMip3;
    // Reflection buffers
    private RtImage historyRefl;
    private RtImage rfReproBuf;
    private RtImage rfSpatA;
    private RtImage rfSpatB;

    private boolean nativeProbeLogged;

    @Override
    public String name() {
        return "ffx-official";
    }

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        if (!nativeProbeLogged) {
            nativeProbeLogged = true;
            var ver = FfxDenoiserRuntime.INSTANCE.tryLoad();
            if (ver.isPresent()) {
                CausticaMod.LOGGER.info(
                        "OfficialFfxDenoiseBackend: native probe OK (packed={}); SPIR-V P0 shadow+reflection active",
                        ver.getAsInt());
            } else {
                CausticaMod.LOGGER.info(
                        "OfficialFfxDenoiseBackend: native probe unavailable; SPIR-V P0 shadow+reflection still active");
            }
        }
        ready = true;
    }

    public void setSplitBuffers(RtImage shadowHit, RtImage diffuse, RtImage reflection) {
        this.shadowHit = shadowHit;
        this.diffuseFallback = diffuse;
        this.reflection = reflection;
    }

    /** Primary NEE without visibility — required for energy-correct shadow composite. */
    public void setUnshadowedDirect(RtImage unshadowedDirect) {
        this.unshadowedDirect = unshadowedDirect;
    }

    public void setSpecMotion(RtImage specMotion) {
        this.specMotion = specMotion;
    }

    private RtImage unshadowedForComposite() {
        return unshadowedDirect != null ? unshadowedDirect : diffuseFallback;
    }

    public RtImage lastCleanShadow() {
        return lastCleanShadow;
    }

    public RtImage lastCleanReflection() {
        return lastCleanReflection;
    }

    public int lastCompositeFlags() {
        return lastCompositeFlags;
    }

    @Override
    public void ensureSized(int width, int height) {
        if (!ready) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return;
        }
        if (shReproPipe == 0L) {
            try {
                createPipelines(ctx);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("OfficialFfxDenoiseBackend pipeline create failed; fail-open to raw beauty", t);
                destroyPipelines(ctx);
                return;
            }
        }
        if (this.width == width && this.height == height && historyShadow != null && historyRefl != null
                && hitMask != null && depthMip1 != null) {
            return;
        }
        destroyImages();

        int tilesX = Math.max(1, (width + 7) / 8);
        int tilesY = Math.max(1, (height + 3) / 4);
        hitMask = ctx.createStorageImage(tilesX, tilesY, VK10.VK_FORMAT_R32_UINT, "ffx hitmask 8x4");
        shadowDense = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx shadow dense");
        historyShadow = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx shadow history");
        shReproBuf = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx shadow reproject");
        shSpatA = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx shadow spat A");
        shSpatB = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx shadow spat B");

        int w1 = Math.max(1, width / 2);
        int h1 = Math.max(1, height / 2);
        int w2 = Math.max(1, w1 / 2);
        int h2 = Math.max(1, h1 / 2);
        int w3 = Math.max(1, w2 / 2);
        int h3 = Math.max(1, h2 / 2);
        depthMip1 = ctx.createStorageImage(w1, h1, VK10.VK_FORMAT_R32_SFLOAT, "ffx depth mip1");
        depthMip2 = ctx.createStorageImage(w2, h2, VK10.VK_FORMAT_R32_SFLOAT, "ffx depth mip2");
        depthMip3 = ctx.createStorageImage(w3, h3, VK10.VK_FORMAT_R32_SFLOAT, "ffx depth mip3");

        historyRefl = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx refl history");
        rfReproBuf = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx refl reproject");
        rfSpatA = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx refl spat A");
        rfSpatB = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx refl spat B");

        this.width = width;
        this.height = height;
        shadowHistoryValid = false;
        reflHistoryValid = false;
        hardReset = true;
        needsHistoryClear = true;
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                            RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                            float mvScaleX, float mvScaleY,
                            RtImage outColor) {
        if (!ready || width <= 0 || height <= 0 || outColor == null) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }

        lastCleanShadow = null;
        lastCleanReflection = null;
        lastCompositeFlags = 0;

        RtImage unshadowed = unshadowedForComposite();
        boolean canComposite = shadowHit != null && unshadowed != null && reflection != null
                && inColor != null && compPipe != 0L && shSpatA != null && rfSpatA != null;

        if (canComposite) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off seed beauty")) {
                // flags=0: pure beauty passthrough seed (fail-open baseline)
                bindComposite(ctx, compSets[0], unshadowed, shadowHit, shSpatA,
                        inColor, reflection, rfSpatA, outColor);
                dispatchComposite(stack, cmd, compSets[0], 0);
            }
            barrier(stack, cmd, outColor.image);
        } else {
            return false;
        }

        if (needsHistoryClear || hardReset) {
            clearHistoryBuffers(stack, cmd);
            needsHistoryClear = false;
        }

        int flags = 0;
        boolean reset = hardReset || !shadowHistoryValid;

        // --- Shadow prepare (8x4 hitmask + dense) ---
        if (prepPipe != 0L && hitMask != null && shadowDense != null && shadowHit != null) {
            try {
                bindImages(ctx, prepSet, shadowHit, hitMask, shadowDense);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off shadow prepare")) {
                    VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, prepPipe);
                    VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, prepLayout, 0,
                            stack.longs(prepSet), null);
                    ByteBuffer push = stack.malloc(16);
                    push.putInt(0, width);
                    push.putInt(4, height);
                    push.putInt(8, hitMask.width);
                    push.putInt(12, hitMask.height);
                    VK10.vkCmdPushConstants(cmd, prepLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                    VK10.vkCmdDispatch(cmd, hitMask.width, hitMask.height, 1);
                }
                barrier(stack, cmd, shadowDense.image);
                barrier(stack, cmd, hitMask.image);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("OfficialFfx shadow prepare failed", t);
            }
        }

        // --- Shadow denoise only (reflection delta was darkening the plate when history was garbage) ---
        RtImage cleanShadow = shSpatA;
        RtImage shadowSrc = shadowDense;
        if (shReproPipe != 0L && shSpatPipe != 0L && historyShadow != null && shadowSrc != null
                && inNormal != null && inDepth != null && inMotion != null) {
            try {
                bindImages(ctx, shReproSet, shadowSrc, inDepth, inNormal, inMotion, historyShadow, shReproBuf, hitMask);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off shadow reproject")) {
                    dispatchPush(stack, cmd, shReproPipe, shReproLayout, shReproSet, reset);
                }
                barrier(stack, cmd, shReproBuf.image);

                cleanShadow = spatialPingPong(stack, cmd, ctx, shSpatPipe, shSpatLayout, shSpatSets,
                        shReproBuf, shSpatA, shSpatB, inDepth, inNormal, "shadow");
                copyImage(stack, cmd, cleanShadow, historyShadow);
                shadowHistoryValid = true;
                barrier(stack, cmd, historyShadow.image);
                flags |= 1;
                lastCleanShadow = cleanShadow;
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("OfficialFfx shadow path failed; keeping beauty seed", t);
                cleanShadow = shSpatA;
                flags &= ~1;
            }
        }

        // Reflection path updates history every frame so the delta is stable the first frame it is
        // applied. The composite bit is config-gated: the uninitialised-history bug that used to
        // zero the plate was the missing transfer→compute barrier after the history clear (fixed);
        // the composite's ±2.0 delta cap and 0.35*beauty floor remain as fail-open guards.
        RtImage cleanRefl = rfSpatA;
        RtImage reflMv = (specMotion != null) ? specMotion : inMotion;
        boolean reflReset = hardReset || !reflHistoryValid;
        boolean reflectionComposite = CausticaConfig.Rt.Denoise.FFX_REFLECTION_COMPOSITE.value();
        if (rfReproPipe != 0L && rfSpatPipe != 0L && historyRefl != null
                && reflection != null && inNormal != null && inDepth != null && reflMv != null
                && depthMip1 != null && depthPyrPipe != 0L) {
            try {
                downsampleDepth(stack, cmd, ctx, depthPyrSets[0], inDepth, depthMip1);
                downsampleDepth(stack, cmd, ctx, depthPyrSets[1], depthMip1, depthMip2);
                downsampleDepth(stack, cmd, ctx, depthPyrSets[2], depthMip2, depthMip3);
                bindImages(ctx, rfReproSet, reflection, inDepth, inNormal, reflMv, historyRefl, rfReproBuf,
                        depthMip1, depthMip2, depthMip3);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off refl reproject")) {
                    dispatchPush(stack, cmd, rfReproPipe, rfReproLayout, rfReproSet, reflReset);
                }
                barrier(stack, cmd, rfReproBuf.image);
                cleanRefl = spatialPingPong(stack, cmd, ctx, rfSpatPipe, rfSpatLayout, rfSpatSets,
                        rfReproBuf, rfSpatA, rfSpatB, inDepth, inNormal, "refl");
                copyImage(stack, cmd, cleanRefl, historyRefl);
                reflHistoryValid = true;
                barrier(stack, cmd, historyRefl.image);
                lastCleanReflection = cleanRefl;
                if (reflectionComposite) {
                    flags |= 2;
                }
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("OfficialFfx reflection path failed; ignored (shadow-only composite)", t);
            }
        }

        hardReset = false;
        lastCompositeFlags = flags;

        try {
            bindComposite(ctx, compSets[1], unshadowed, shadowHit, cleanShadow,
                    inColor, reflection, cleanRefl, outColor);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off composite f=" + flags)) {
                dispatchComposite(stack, cmd, compSets[1], flags);
            }
            barrier(stack, cmd, outColor.image);
            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("OfficialFfx composite failed; restoring beauty seed", t);
            try {
                bindComposite(ctx, compSets[0], unshadowed, shadowHit, shSpatA,
                        inColor, reflection, rfSpatA, outColor);
                dispatchComposite(stack, cmd, compSets[0], 0);
                lastCompositeFlags = 0;
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    private void clearHistoryBuffers(MemoryStack stack, VkCommandBuffer cmd) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(RtContext.get(), cmd, "ffx-off clear history")) {
            VkClearColorValue black = VkClearColorValue.calloc(stack);
            for (int i = 0; i < 4; i++) {
                black.float32(i, 0.0f);
            }
            // Shadow history: r=1 (fully lit) so a missed write never applies a dark delta.
            VkClearColorValue litShadow = VkClearColorValue.calloc(stack);
            litShadow.float32(0, 1.0f);
            litShadow.float32(1, 0.0f);
            litShadow.float32(2, 0.0f);
            litShadow.float32(3, 1.0f);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            if (historyShadow != null) {
                VK10.vkCmdClearColorImage(cmd, historyShadow.image, VK10.VK_IMAGE_LAYOUT_GENERAL, litShadow, range);
            }
            if (shReproBuf != null) {
                VK10.vkCmdClearColorImage(cmd, shReproBuf.image, VK10.VK_IMAGE_LAYOUT_GENERAL, litShadow, range);
            }
            if (shSpatA != null) {
                VK10.vkCmdClearColorImage(cmd, shSpatA.image, VK10.VK_IMAGE_LAYOUT_GENERAL, litShadow, range);
            }
            if (shSpatB != null) {
                VK10.vkCmdClearColorImage(cmd, shSpatB.image, VK10.VK_IMAGE_LAYOUT_GENERAL, litShadow, range);
            }
            if (shadowDense != null) {
                VK10.vkCmdClearColorImage(cmd, shadowDense.image, VK10.VK_IMAGE_LAYOUT_GENERAL, litShadow, range);
            }
            if (historyRefl != null) {
                VK10.vkCmdClearColorImage(cmd, historyRefl.image, VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            }
            if (rfReproBuf != null) {
                VK10.vkCmdClearColorImage(cmd, rfReproBuf.image, VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            }
            if (rfSpatA != null) {
                VK10.vkCmdClearColorImage(cmd, rfSpatA.image, VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            }
            if (rfSpatB != null) {
                VK10.vkCmdClearColorImage(cmd, rfSpatB.image, VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            }
        }
        // vkCmdClearColorImage is a TRANSFER write. A compute→compute barrier does
        // not make those clears visible on RADV and left stale/random history in
        // exactly the buffers sampled by the first denoise frame.
        barrierTransferToCompute(stack, cmd,
                historyShadow.image, shReproBuf.image, shSpatA.image, shSpatB.image,
                shadowDense.image, historyRefl.image, rfReproBuf.image,
                rfSpatA.image, rfSpatB.image);
    }

    private void downsampleDepth(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx, long set,
                                 RtImage src, RtImage dst) {
        bindImages(ctx, set, src, dst);
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                "ffx-off depth pyr " + dst.width + "x" + dst.height)) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, depthPyrPipe);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, depthPyrLayout, 0,
                stack.longs(set), null);
            VK10.vkCmdDispatch(cmd, (dst.width + 7) / 8, (dst.height + 7) / 8, 1);
        }
        barrier(stack, cmd, dst.image);
    }

    private RtImage spatialPingPong(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx,
                                    long pipe, long layout, long[] sets,
                                    RtImage input, RtImage a, RtImage b,
                                    RtImage depth, RtImage normal, String tag) {
        RtImage finalDst = a;
        for (int i = 0; i < SPATIAL_STEPS.length; i++) {
            RtImage src;
            RtImage dst;
            if (i == 0) {
                src = input;
                dst = a;
            } else if ((i & 1) == 1) {
                src = a;
                dst = b;
            } else {
                src = b;
                dst = a;
            }
            finalDst = dst;
            long set = sets[i];
            bindImages(ctx, set, src, depth, normal, dst);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off " + tag + " spat s" + SPATIAL_STEPS[i])) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0,
                        stack.longs(set), null);
                ByteBuffer push = stack.malloc(16);
                push.putInt(0, SPATIAL_STEPS[i]);
                push.putFloat(4, 0.03f);
                push.putFloat(8, 0.15f);
                push.putFloat(12, 0.0f);
                VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            }
            barrier(stack, cmd, dst.image);
        }
        return finalDst;
    }

    private void dispatchPush(MemoryStack stack, VkCommandBuffer cmd, long pipe, long layout, long set,
                              boolean reset) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0,
                stack.longs(set), null);
        ByteBuffer push = stack.malloc(16);
        push.putFloat(0, 1.0f / Math.max(1, width));
        push.putFloat(4, 1.0f / Math.max(1, height));
        push.putFloat(8, 0.02f);
        push.putFloat(12, reset ? 1.0f : 0.0f);
        VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
    }

    private void dispatchComposite(MemoryStack stack, VkCommandBuffer cmd, long set, int useFlags) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compPipe);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compLayout, 0,
                stack.longs(set), null);
        ByteBuffer push = stack.malloc(16);
        push.putInt(0, useFlags);
        push.putFloat(4, 0.0f);
        push.putFloat(8, 0.0f);
        push.putFloat(12, 0.0f);
        VK10.vkCmdPushConstants(cmd, compLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
    }

    @Override
    public void resetHistory() {
        hardReset = true;
        shadowHistoryValid = false;
        reflHistoryValid = false;
        needsHistoryClear = true;
    }

    @Override
    public void destroy() {
        RtContext ctx = RtContext.get();
        destroyImages();
        if (ctx != null) {
            destroyPipelines(ctx);
        }
        shadowHit = null;
        unshadowedDirect = null;
        diffuseFallback = null;
        reflection = null;
        specMotion = null;
        ready = false;
        width = 0;
        height = 0;
        shadowHistoryValid = false;
        reflHistoryValid = false;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public boolean supportsAsyncCompute() {
        return true;
    }

    @Override
    public void dispatchAsync(
            VkCommandBuffer computeCmd,
            dev.comfyfluffy.caustica.rt.RtAsyncCompute asyncCompute,
            MemoryStack stack,
            RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
            float mvScaleX, float mvScaleY,
            RtImage outColor) {
        dispatch(stack, computeCmd, inColor, inNormal, inDepth, inMotion, mvScaleX, mvScaleY, outColor);
    }

    private void createPipelines(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // prepare: shadowHit, hitMask, dense
            prepDsl = createDsl(ctx, stack, 3);
            prepPool = createPool(ctx, stack, 3);
            prepSet = allocSet(ctx, stack, prepPool, prepDsl);
            prepLayout = createLayout(ctx, stack, prepDsl, 16);
            prepPipe = createPipeline(ctx, stack, prepLayout, "shadow_prepare.comp.spv");

            // shadow reproject: dense, depth, normal, motion, history, out, hitmask
            shReproDsl = createDsl(ctx, stack, 7);
            shReproPool = createPool(ctx, stack, 7);
            shReproSet = allocSet(ctx, stack, shReproPool, shReproDsl);
            shReproLayout = createLayout(ctx, stack, shReproDsl, 16);
            shReproPipe = createPipeline(ctx, stack, shReproLayout, "shadow_reproject.comp.spv");

            shSpatDsl = createDsl(ctx, stack, 4);
            shSpatPool = createPool(ctx, stack, 4, SPATIAL_STEPS.length);
            shSpatSets = allocSets(ctx, stack, shSpatPool, shSpatDsl, SPATIAL_STEPS.length);
            shSpatLayout = createLayout(ctx, stack, shSpatDsl, 16);
            shSpatPipe = createPipeline(ctx, stack, shSpatLayout, "shadow_spatial.comp.spv");

            depthPyrDsl = createDsl(ctx, stack, 2);
            depthPyrPool = createPool(ctx, stack, 2, 3);
            depthPyrSets = allocSets(ctx, stack, depthPyrPool, depthPyrDsl, 3);
            depthPyrLayout = createLayout(ctx, stack, depthPyrDsl, 0);
            depthPyrPipe = createPipeline(ctx, stack, depthPyrLayout, "depth_pyramid.comp.spv");

            // reflection reproject: refl, depth, normal, mv, history, out, mip1, mip2, mip3
            rfReproDsl = createDsl(ctx, stack, 9);
            rfReproPool = createPool(ctx, stack, 9);
            rfReproSet = allocSet(ctx, stack, rfReproPool, rfReproDsl);
            rfReproLayout = createLayout(ctx, stack, rfReproDsl, 16);
            rfReproPipe = createPipeline(ctx, stack, rfReproLayout, "reflection_reproject.comp.spv");

            rfSpatDsl = createDsl(ctx, stack, 4);
            rfSpatPool = createPool(ctx, stack, 4, SPATIAL_STEPS.length);
            rfSpatSets = allocSets(ctx, stack, rfSpatPool, rfSpatDsl, SPATIAL_STEPS.length);
            rfSpatLayout = createLayout(ctx, stack, rfSpatDsl, 16);
            rfSpatPipe = createPipeline(ctx, stack, rfSpatLayout, "reflection_spatial.comp.spv");

            compDsl = createDsl(ctx, stack, 7);
            compPool = createPool(ctx, stack, 7, 2);
            compSets = allocSets(ctx, stack, compPool, compDsl, 2);
            compLayout = createLayout(ctx, stack, compDsl, 16);
            compPipe = createPipeline(ctx, stack, compLayout, "denoise_composite.comp.spv");
        }
    }

    private void destroyPipelines(RtContext ctx) {
        VkDevice vk = ctx.vk();
        destroyPipe(vk, prepPipe, prepLayout, prepPool, prepDsl);
        destroyPipe(vk, shReproPipe, shReproLayout, shReproPool, shReproDsl);
        destroyPipe(vk, shSpatPipe, shSpatLayout, shSpatPool, shSpatDsl);
        destroyPipe(vk, depthPyrPipe, depthPyrLayout, depthPyrPool, depthPyrDsl);
        destroyPipe(vk, rfReproPipe, rfReproLayout, rfReproPool, rfReproDsl);
        destroyPipe(vk, rfSpatPipe, rfSpatLayout, rfSpatPool, rfSpatDsl);
        destroyPipe(vk, compPipe, compLayout, compPool, compDsl);
        prepPipe = prepLayout = prepPool = prepDsl = prepSet = 0L;
        shReproPipe = shReproLayout = shReproPool = shReproDsl = shReproSet = 0L;
        shSpatPipe = shSpatLayout = shSpatPool = shSpatDsl = 0L;
        shSpatSets = new long[0];
        depthPyrPipe = depthPyrLayout = depthPyrPool = depthPyrDsl = 0L;
        depthPyrSets = new long[0];
        rfReproPipe = rfReproLayout = rfReproPool = rfReproDsl = rfReproSet = 0L;
        rfSpatPipe = rfSpatLayout = rfSpatPool = rfSpatDsl = 0L;
        rfSpatSets = new long[0];
        compPipe = compLayout = compPool = compDsl = 0L;
        compSets = new long[0];
        descriptorBindings.clear();
    }

    private static void destroyPipe(VkDevice vk, long pipeline, long layout, long pool, long dsl) {
        if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
        if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
        if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
        if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
    }

    private void destroyImages() {
        if (hitMask != null) { hitMask.destroy(); hitMask = null; }
        if (shadowDense != null) { shadowDense.destroy(); shadowDense = null; }
        if (historyShadow != null) { historyShadow.destroy(); historyShadow = null; }
        if (shReproBuf != null) { shReproBuf.destroy(); shReproBuf = null; }
        if (shSpatA != null) { shSpatA.destroy(); shSpatA = null; }
        if (shSpatB != null) { shSpatB.destroy(); shSpatB = null; }
        if (depthMip1 != null) { depthMip1.destroy(); depthMip1 = null; }
        if (depthMip2 != null) { depthMip2.destroy(); depthMip2 = null; }
        if (depthMip3 != null) { depthMip3.destroy(); depthMip3 = null; }
        if (historyRefl != null) { historyRefl.destroy(); historyRefl = null; }
        if (rfReproBuf != null) { rfReproBuf.destroy(); rfReproBuf = null; }
        if (rfSpatA != null) { rfSpatA.destroy(); rfSpatA = null; }
        if (rfSpatB != null) { rfSpatB.destroy(); rfSpatB = null; }
    }

    private void bindComposite(RtContext ctx, long set,
                               RtImage unshadowed, RtImage shadowRaw, RtImage shadowClean,
                               RtImage beauty, RtImage specRaw, RtImage specClean, RtImage out) {
        bindImages(ctx, set, unshadowed, shadowRaw, shadowClean, beauty, specRaw, specClean, out);
    }

    private void bindImages(RtContext ctx, long set, RtImage... images) {
        long[] views = new long[images.length];
        for (int i = 0; i < images.length; i++) {
            views[i] = images[i].view;
        }
        if (Arrays.equals(descriptorBindings.get(set), views)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(images.length, stack);
            for (int i = 0; i < images.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        descriptorBindings.put(set, views);
    }

    private static long createDsl(RtContext ctx, MemoryStack stack, int count) {
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            binds.get(i).binding(i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(ffx-off)");
        return p.get(0);
    }

    private static long createPool(RtContext ctx, MemoryStack stack, int count) {
        return createPool(ctx, stack, count, 1);
    }

    private static long createPool(RtContext ctx, MemoryStack stack, int count, int setCount) {
        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
        poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(Math.max(count * setCount, 1));
        VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(setCount).pPoolSizes(poolSizes);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(ffx-off)");
        return p.get(0);
    }

    private static long allocSet(RtContext ctx, MemoryStack stack, long pool, long dsl) {
        VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet), "vkAllocateDescriptorSets(ffx-off)");
        return pSet.get(0);
    }

    private static long[] allocSets(RtContext ctx, MemoryStack stack, long pool, long dsl, int count) {
        long[] sets = new long[count];
        for (int i = 0; i < count; i++) {
            sets[i] = allocSet(ctx, stack, pool, dsl);
        }
        return sets;
    }

    private static long createLayout(RtContext ctx, MemoryStack stack, long dsl, int pushSize) {
        VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(dsl));
        if (pushSize > 0) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushSize);
            plci.pPushConstantRanges(pcr);
        }
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(ffx-off)");
        return p.get(0);
    }

    private static long createPipeline(RtContext ctx, MemoryStack stack, long layout, String spvName) {
        long module = loadModule(ctx.vk(), stack, spvName);
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p),
                    "vkCreateComputePipelines(" + spvName + ")");
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = OfficialFfxDenoiseBackend.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
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

    private static void barrierTransferToCompute(MemoryStack stack, VkCommandBuffer cmd, long... images) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            barriers.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
    }

    private static void copyImage(MemoryStack stack, VkCommandBuffer cmd, RtImage src, RtImage dst) {
        // The source was written by compute and history dst was read by compute.
        // Synchronize both hazards before the transfer copy; the old helper used a
        // compute→compute barrier, which does not cover vkCmdCopyImage at all.
        VkImageMemoryBarrier2.Buffer before = VkImageMemoryBarrier2.calloc(2, stack);
        before.get(0).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(src.image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        before.get(1).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(dst.image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(before));

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0)
                .srcSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1))
                .dstSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1))
                .extent(it -> it.width(src.width).height(src.height).depth(1));
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
        barrierTransferToCompute(stack, cmd, dst.image);
    }
}
