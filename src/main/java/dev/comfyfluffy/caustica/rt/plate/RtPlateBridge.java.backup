/*
 * Caustica — RT output format adaptation layer bridge.
 * Copyright (c) 2026. Caustica contributors.
 *
 * Format-adaptation layer between raw RT output and the active (denoise, upscale) pair.
 * Owns staging images and conversion compute pipelines across the denoise/upscaler seam:
 *   raw radiance beauty (B10G11R11) or Hybrid compose (RGBA16F) → FSR2 input
 *   optional self-derived R32F reactive mask
 *   post-dispatch output to B10G11R11 (SDR) or RGBA16F (HDR-PQ) with blackout fail-open
 *
 * <p>Behaviour is conservative: reallocates everything when the profile or sizes change,
 * reuse when unchanged. Caller must own the command-buffer recording order and submit
 * on the right queue — no internal fence tracking.
 *
 * <p>The bridge is owned by {@code RtComposite}; denoisers and upscalers receive only
 * non-owning access to the shared seam resources.
 */
package dev.comfyfluffy.caustica.rt.plate;

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
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Format-adaptation bridge owned by {@code RtComposite}. Replaces the inline pack /
 * unpack / reactive-mask / blackout-guard compute pipelines previously living in
 * {@code Fsr2ClassicUpscaler}.
 *
 * <p>Typical per-frame use (from {@code Fsr2ClassicUpscaler.evaluate}):
 * <pre>{@code
 *   RtImage sdkInput = plate.convertToUpscalerInput(cmd, color, actualColorFormat);
 *   boolean reactiveRan = plate.computeReactiveMaskIfNeeded(cmd, motion, depth, normals,
 *           guideViewZ, guideDisocclusionMix);
 *   plate.clearUpscalerOutput(cmd);
 *   <native dispatch writes plate.upscalerOutputColor()>
 *   plate.convertFromUpscalerOutput(cmd, caller's display destination);
 * }</pre>
 */
public final class RtPlateBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    // Reuse the existing SPIR-V files; resource paths are pinned to the locations
    // gradle's shader-gen task drops them (see build.gradle def shaderGenRoot).
    private static final String PACK_SPV = "/caustica/rt/fsr_color_pack.comp.spv";
    private static final String UNPACK_SPV = "/caustica/rt/fsr_color_unpack.comp.spv";
    private static final String GUARD_B10_SPV = "/caustica/rt/fsr_blackout_guard.comp.spv";
    private static final String GUARD_RGBA16F_SPV = "/caustica/rt/fsr_blackout_guard_rgba16f.comp.spv";
    private static final String REACTIVE_SPV = "/caustica/rt/fsr2_reactive_mask.comp.spv";

    private final RtContext ctx;

    private RtPlateProfile profile;

    // Owned staging
    private RtImage denoiseInputColor;
    private RtImage denoiseOutputColor;
    private RtImage upscalerInputColor;
    /** Actual image selected by the last convertToUpscalerInput call (identity or staging). */
    private RtImage currentUpscalerInput;
    private RtImage upscalerOutputColor;
    private RtImage reactiveMask;

    private int renderW = -1, renderH = -1;
    private int displayW = -1, displayH = -1;
    private boolean destroyed;

    // Pack + unpack pipelines
    private long convDsl, convPool, convLayout;
    private long convSetPack, convSetUnpack;
    private long packPipe, unpackPipe;
    private long[] convBoundPack = new long[0];
    private long[] convBoundUnpack = new long[0];
    private boolean convReady;

    // Guard pipeline (optional, FSR2-only)
    private long guardDsl, guardPool, guardSet, guardLayout, guardPipe;
    private long[] guardBound = new long[0];
    private boolean guardReady;

    // Reactive mask pipeline
    private long reactiveDsl, reactivePool, reactiveSet, reactiveLayout, reactivePipe;
    private long[] reactiveBound = new long[0];
    private boolean reactiveReady;

    public RtPlateBridge(RtContext ctx) {
        this.ctx = ctx;
    }

    /** Cached {@link VkDevice} handle accessor — re-fetched so a stale handle never leaks out. */
    private VkDevice device() {
        return ctx != null ? ctx.vk() : null;
    }

    public RtPlateProfile profile() { return profile; }
    public RtImage denoiseInputColor() { return denoiseInputColor; }
    public RtImage denoiseOutputColor() { return denoiseOutputColor; }
    public RtImage upscalerInputColor() { return upscalerInputColor; }
    public RtImage upscalerOutputColor() { return upscalerOutputColor; }
    public RtImage reactiveMask() { return reactiveMask; }

    /**
     * Re-evaluate the active profile. Reallocates staging + pipelines when the profile
     * or any dimension changes. No-op when unchanged.
     *
     * @return true on success, false on any failure (bridge is left in a torn-down state
     *         if so, so callers should skip dispatch).
     */
    public synchronized boolean ensureSized(RtPlateProfile newProfile,
                                            int newRenderW, int newRenderH,
                                            int newDisplayW, int newDisplayH) {
        boolean sameProfile = (profile != null && profile.equals(newProfile));
        boolean sameSizes = (newRenderW == renderW && newRenderH == renderH
                && newDisplayW == displayW && newDisplayH == displayH);
        if (sameProfile && sameSizes && upscalerOutputColor != null) {
            return true;
        }
        // Tear everything down so a partial failure doesn't leak.
        destroyResources();
        profile = newProfile;
        renderW = newRenderW; renderH = newRenderH;
        displayW = newDisplayW; displayH = newDisplayH;
        try {
            if (!profile.denoiseIdentityPack) {
                denoiseInputColor = ctx.createStorageImage(renderW, renderH,
                        profile.denoiseInputFormat, "plate denoiseInput");
            }
            // A non-raw denoise output is a real backend render target, not merely a
            // conversion staging image. Hybrid FFX+NRD composes directly into this RGBA16F plate.
            if (profile.denoiseOutputFormat != profile.rawBeautyFormat) {
                denoiseOutputColor = ctx.createStorageImage(renderW, renderH,
                        profile.denoiseOutputFormat, "plate denoiseOutput");
            }
            // Keep one input staging allocation whenever either raw fallback or the configured
            // denoise output may need conversion into the SDK's declared input format.
            if (profile.rawBeautyFormat != profile.upscalerInputFormat
                    || profile.denoiseOutputFormat != profile.upscalerInputFormat) {
                upscalerInputColor = ctx.createStorageImage(renderW, renderH,
                        profile.upscalerInputFormat, "plate upscaleInput");
            }
            upscalerOutputColor = ctx.createStorageImage(displayW, displayH,
                    profile.upscalerOutputFormat, "plate upscaleOutput");
            if (profile.needsReactiveMask) {
                reactiveMask = ctx.createStorageImage(renderW, renderH,
                        VK10.VK_FORMAT_R32_SFLOAT, "plate reactiveMask");
            }
            boolean needConvPipelines = upscalerInputColor != null
                    || (!profile.denoiseIdentityPack)
                    || (!profile.identityUnpack);
            if (needConvPipelines) {
                ensureConvertPipelines();
            }
            if (profile.needsReactiveMask) {
                ensureReactivePipelines();
            }
            if (profile.needsBlackoutGuard) {
                try {
                    ensureGuardPipelines();
                } catch (Throwable t) {
                    LOGGER.warn("plate guard pipeline unavailable; will rely on plain unpack", t);
                    destroyGuard();
                }
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("RtPlateBridge.ensureSized failed", t);
            destroyResources();
            profile = null;
            return false;
        }
    }

    /**
     * Convert an arbitrary render-resolution color plate to the active upscaler input format.
     * Identity is decided from {@code colorFormat}, not from the profile's raw-format default:
     * Hybrid FFX+NRD already produces RGBA16F and therefore flows straight into FSR2.
     *
     * @return {@code color} for identity, otherwise the bridge-owned conversion staging image
     */
    public RtImage convertToUpscalerInput(VkCommandBuffer cmd, RtImage color, int colorFormat) {
        if (profile == null || color == null) {
            throw new IllegalStateException("plate bridge/profile not ready");
        }
        if (colorFormat == profile.upscalerInputFormat) {
            currentUpscalerInput = color;
            return color;
        }
        if (!convReady || upscalerInputColor == null) {
            throw new IllegalStateException("no plate conversion path for color format 0x"
                    + Integer.toHexString(colorFormat) + " -> 0x"
                    + Integer.toHexString(profile.upscalerInputFormat));
        }

        boolean packRawToRgba = colorFormat == profile.rawBeautyFormat
                && profile.upscalerInputFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
        boolean unpackRgbaToRaw = colorFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                && profile.upscalerInputFormat == profile.rawBeautyFormat;
        if (!packRawToRgba && !unpackRgbaToRaw) {
            throw new IllegalArgumentException("unsupported plate conversion 0x"
                    + Integer.toHexString(colorFormat) + " -> 0x"
                    + Integer.toHexString(profile.upscalerInputFormat));
        }

        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                     packRawToRgba ? "plate pack" : "plate unpack input")) {
            barrierWrite(stack, cmd, upscalerInputColor.image);
            long descriptorSet;
            long pipeline;
            if (packRawToRgba) {
                bindPack(color, upscalerInputColor);
                descriptorSet = convSetPack;
                pipeline = packPipe;
            } else {
                bindUnpack(color, upscalerInputColor);
                descriptorSet = convSetUnpack;
                pipeline = unpackPipe;
            }
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, convLayout, 0,
                    stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(16);
            push.putInt(0, renderW);
            push.putInt(4, renderH);
            push.putInt(8, 0);
            push.putInt(12, 0);
            VK10.vkCmdPushConstants(cmd, convLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (renderW + 7) / 8, (renderH + 7) / 8, 1);
            barrierRW(stack, cmd, upscalerInputColor.image);
        }
        currentUpscalerInput = upscalerInputColor;
        return upscalerInputColor;
    }

    /**
     * Adapt the raw path-traced beauty into the format the active denoise backend expects.
     * Returns the image the backend should read from — the bridge's {@link #denoiseInputColor()}
     * staging when format-conversion is required, or {@code rawBeauty} itself when the formats
     * already match (the current case for all configured backends).
     */
    public RtImage adaptToDenoise(VkCommandBuffer cmd, RtImage rawBeauty) {
        if (profile == null || profile.denoiseIdentityPack || denoiseInputColor == null) {
            return rawBeauty;
        }
        if (!convReady) return rawBeauty;
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "plate denoise pack")) {
            barrierWrite(stack, cmd, denoiseInputColor.image);
            bindPack(rawBeauty, denoiseInputColor);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, packPipe);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, convLayout, 0,
                    stack.longs(convSetPack), null);
            ByteBuffer push = stack.malloc(16);
            push.putInt(0, renderW);
            push.putInt(4, renderH);
            push.putInt(8, 0);
            push.putInt(12, 0);
            VK10.vkCmdPushConstants(cmd, convLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (renderW + 7) / 8, (renderH + 7) / 8, 1);
            barrierRW(stack, cmd, denoiseInputColor.image);
        }
        return denoiseInputColor;
    }

    /**
     * Adapt the denoise backend's output into the format the upscaler-side needs.
     * Returns the image the upscaler should read from — {@link #denoiseOutputColor()}
     * staging when the bridge owns an output-side conversion, or {@code denoisedOutput}
     * itself when the formats already match.
     */
    public RtImage finalizeDenoise(VkCommandBuffer cmd, RtImage denoisedOutput) {
        if (profile == null || denoisedOutput == null) {
            return denoisedOutput;
        }
        // The backend writes directly into the correctly formatted target selected by
        // RtComposite (Hybrid compose -> denoiseOutputColor/RGBA16F). Only visibility is
        // needed here; conversion, if any, is deferred until the actual upscaler input is known.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            barrierRW(stack, cmd, denoisedOutput.image);
        }
        return denoisedOutput;
    }

    /**
     * Compute the self-derived reactive mask when (motion, depth, normals, viewZ,
     * disoccl) are all available. {@code viewZ} / {@code disoccl} may be null only
     * when the upscaler doesn't need the disocclusion-aware variant.
     *
     * @return true when the reactive mask was written; false if it was skipped (the
     *         caller should then dispatch without reactive).
     */
    public boolean computeReactiveMaskIfNeeded(VkCommandBuffer cmd,
                                               RtImage motion, RtImage depth, RtImage normals,
                                               RtImage viewZ, RtImage disoccl) {
        if (profile == null || !profile.needsReactiveMask || !reactiveReady) return false;
        if (motion == null || depth == null || normals == null) return false;
        if (viewZ == null || disoccl == null) return false;
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "plate reactive")) {
            barrierWrite(stack, cmd, reactiveMask.image);
            bindReactive(motion, depth, normals, viewZ, disoccl, reactiveMask);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, reactivePipe);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, reactiveLayout, 0,
                    stack.longs(reactiveSet), null);
            ByteBuffer push = stack.malloc(16);
            push.putFloat(0, 1.0f);
            push.putFloat(4, 1.0f);
            push.putFloat(8, 0.0f);
            push.putFloat(12, 0.0f);
            VK10.vkCmdPushConstants(cmd, reactiveLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (renderW + 7) / 8, (renderH + 7) / 8, 1);
            barrierRW(stack, cmd, reactiveMask.image);
            return true;
        }
    }

    /**
     * Clear {@link #upscalerOutputColor()} to pure black before a native dispatch.
     * Defends the temporal history against partial-write fallout.
     */
    public void clearUpscalerOutput(VkCommandBuffer cmd) {
        if (profile == null || !profile.needsBlackoutGuard) return;
        if (upscalerOutputColor == null) return;
        clearBlack(cmd, upscalerOutputColor);
    }

    /**
     * Convert the SDK's upscaled output into {@code dst} (caller-owned). Records either
     * the blackout guard pass or a plain unpack, whichever is appropriate.
     */
    public void convertFromUpscalerOutput(VkCommandBuffer cmd, RtImage dst) {
        if (profile == null || dst == null || upscalerOutputColor == null) return;
        if (profile.needsBlackoutGuard && guardReady) {
            if (currentUpscalerInput == null) {
                throw new IllegalStateException("blackout guard has no current upscaler input");
            }
            try (MemoryStack stack = MemoryStack.stackPush();
                 RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "plate guard")) {
                barrierRW(stack, cmd, upscalerOutputColor.image);
                bindGuard(upscalerOutputColor, currentUpscalerInput, dst);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, guardPipe);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, guardLayout, 0,
                        stack.longs(guardSet), null);
                ByteBuffer push = stack.malloc(16);
                push.putInt(0, displayW);
                push.putInt(4, displayH);
                push.putInt(8, renderW);
                push.putInt(12, renderH);
                VK10.vkCmdPushConstants(cmd, guardLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (displayW + 7) / 8, (displayH + 7) / 8, 1);
                barrierRW(stack, cmd, dst.image);
            }
            return;
        }
        if (profile.identityUnpack) {
            copyImage(cmd, upscalerOutputColor, dst);
            return;
        }
        if (!convReady) {
            throw new IllegalStateException("plate output conversion pipeline unavailable");
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "plate unpack")) {
            barrierRW(stack, cmd, upscalerOutputColor.image);
            bindUnpack(upscalerOutputColor, dst);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, unpackPipe);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, convLayout, 0,
                    stack.longs(convSetUnpack), null);
            ByteBuffer push = stack.malloc(16);
            push.putInt(0, displayW);
            push.putInt(4, displayH);
            push.putInt(8, 0);
            push.putInt(12, 0);
            VK10.vkCmdPushConstants(cmd, convLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (displayW + 7) / 8, (displayH + 7) / 8, 1);
            barrierRW(stack, cmd, dst.image);
        }
    }

    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        destroyResources();
    }

    // ---------------------------------------------------------------------
    // Pipeline construction
    // ---------------------------------------------------------------------

    private void ensureConvertPipelines() throws Throwable {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vk = device();
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, p),
                    "vkCreateDescriptorSetLayout(plate-convert)");
            convDsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(2).pPoolSizes(poolSizes), null, p),
                    "vkCreateDescriptorPool(plate-convert)");
            convPool = p.get(0);
            convSetPack = allocDescriptorSet(vk, stack, convPool, convDsl);
            convSetUnpack = allocDescriptorSet(vk, stack, convPool, convDsl);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(convDsl)).pPushConstantRanges(pcr), null, p),
                    "vkCreatePipelineLayout(plate-convert)");
            convLayout = p.get(0);
            packPipe = createPipe(vk, stack, convLayout, PACK_SPV, "plate-pack");
            unpackPipe = createPipe(vk, stack, convLayout, UNPACK_SPV, "plate-unpack");
            convReady = true;
        }
    }

    private void ensureGuardPipelines() throws Throwable {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vk = device();
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                binds.get(i).binding(i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, p),
                    "vkCreateDescriptorSetLayout(plate-guard)");
            guardDsl = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, p),
                    "vkCreateDescriptorPool(plate-guard)");
            guardPool = p.get(0);
            guardSet = allocDescriptorSet(vk, stack, guardPool, guardDsl);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(guardDsl)).pPushConstantRanges(pcr), null, p),
                    "vkCreatePipelineLayout(plate-guard)");
            guardLayout = p.get(0);
            String guardSpv = profile.displayFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                    ? GUARD_RGBA16F_SPV : GUARD_B10_SPV;
            guardPipe = createPipe(vk, stack, guardLayout, guardSpv, "plate-guard");
            guardReady = true;
        }
    }

    private void ensureReactivePipelines() throws Throwable {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vk = device();
            final int B = 6;
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(B, stack);
            for (int i = 0; i < B; i++) {
                binds.get(i).binding(i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, p),
                    "vkCreateDescriptorSetLayout(plate-reactive)");
            reactiveDsl = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(B);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, p),
                    "vkCreateDescriptorPool(plate-reactive)");
            reactivePool = p.get(0);
            reactiveSet = allocDescriptorSet(vk, stack, reactivePool, reactiveDsl);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(reactiveDsl)).pPushConstantRanges(pcr), null, p),
                    "vkCreatePipelineLayout(plate-reactive)");
            reactiveLayout = p.get(0);
            reactivePipe = createPipe(vk, stack, reactiveLayout, REACTIVE_SPV, "plate-reactive");
            reactiveReady = true;
        }
    }

    // ---------------------------------------------------------------------
    // Descriptor-set updates (write-through only; cache last-bound view set)
    // ---------------------------------------------------------------------

    private void bindPack(RtImage src, RtImage dst) {
        long[] views = {src.view, dst.view};
        if (Arrays.equals(convBoundPack, views)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writeStorageImage(stack, writes.get(0), convSetPack, 0, views[0]);
            writeStorageImage(stack, writes.get(1), convSetPack, 1, views[1]);
            VK10.vkUpdateDescriptorSets(device(), writes, null);
            convBoundPack = views;
        }
    }

    private void bindUnpack(RtImage src, RtImage dst) {
        long[] views = {src.view, dst.view};
        if (Arrays.equals(convBoundUnpack, views)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writeStorageImage(stack, writes.get(0), convSetUnpack, 0, views[0]);
            writeStorageImage(stack, writes.get(1), convSetUnpack, 1, views[1]);
            VK10.vkUpdateDescriptorSets(device(), writes, null);
            convBoundUnpack = views;
        }
    }

    private void bindGuard(RtImage fsrRgba, RtImage inRgba, RtImage outDisplay) {
        long[] views = {fsrRgba.view, inRgba.view, outDisplay.view};
        if (Arrays.equals(guardBound, views)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            writeStorageImage(stack, writes.get(0), guardSet, 0, views[0]);
            writeStorageImage(stack, writes.get(1), guardSet, 1, views[1]);
            writeStorageImage(stack, writes.get(2), guardSet, 2, views[2]);
            VK10.vkUpdateDescriptorSets(device(), writes, null);
            guardBound = views;
        }
    }

    private void bindReactive(RtImage motion, RtImage deviceDepth, RtImage normalRough,
                              RtImage viewZ, RtImage disoccl, RtImage out) {
        long[] views = {motion.view, deviceDepth.view, viewZ.view,
                normalRough.view, disoccl.view, out.view};
        if (Arrays.equals(reactiveBound, views)) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
            writeStorageImage(stack, writes.get(0), reactiveSet, 0, views[0]);
            writeStorageImage(stack, writes.get(1), reactiveSet, 1, views[1]);
            writeStorageImage(stack, writes.get(2), reactiveSet, 2, views[2]);
            writeStorageImage(stack, writes.get(3), reactiveSet, 3, views[3]);
            writeStorageImage(stack, writes.get(4), reactiveSet, 4, views[4]);
            writeStorageImage(stack, writes.get(5), reactiveSet, 5, views[5]);
            VK10.vkUpdateDescriptorSets(device(), writes, null);
            reactiveBound = views;
        }
    }

    private static void writeStorageImage(MemoryStack stack, VkWriteDescriptorSet write,
                                          long set, int binding, long view) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        write.sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
    }

    private static long allocDescriptorSet(VkDevice vk, MemoryStack stack, long pool, long dsl) {
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk,
                VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pool).pSetLayouts(stack.longs(dsl)), p),
                "vkAllocateDescriptorSets");
        return p.get(0);
    }

    private static long createPipe(VkDevice vk, MemoryStack stack, long layout, String spv, String tag) {
        long module = loadModule(vk, stack, spv);
        try {
            LongBuffer p = stack.mallocLong(1);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, p),
                    "vkCreateComputePipelines(" + tag + ")");
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(vk, module, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String resourcePath) {
        byte[] bytes;
        try (InputStream in = RtPlateBridge.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + resourcePath);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + resourcePath, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, p), "vkCreateShaderModule(" + resourcePath + ")");
            return p.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static void copyImage(VkCommandBuffer cmd, RtImage src, RtImage dst) {
        if (src.width != dst.width || src.height != dst.height) {
            throw new IllegalArgumentException("identity plate copy requires matching extents");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            barrier(stack, cmd, src.image,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT);
            barrier(stack, cmd, dst.image,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).extent().set(src.width, src.height, 1);
            VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            barrier(stack, cmd, dst.image,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        }
    }

    private static void clearBlack(VkCommandBuffer cmd, RtImage out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue black = VkClearColorValue.calloc(stack);
            for (int i = 0; i < 4; i++) {
                black.float32(i, 0.0f);
            }
            org.lwjgl.vulkan.VkImageSubresourceRange.Buffer range =
                    org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, out.image, VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            barrierWrite(stack, cmd, out.image);
        }
    }

    // ---------------------------------------------------------------------
    // Barriers
    // ---------------------------------------------------------------------

    private static void barrierWrite(MemoryStack stack, VkCommandBuffer cmd, long image) {
        barrier(stack, cmd, image,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void barrierRead(MemoryStack stack, VkCommandBuffer cmd, long image) {
        barrier(stack, cmd, image,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
    }

    private static void barrierRW(MemoryStack stack, VkCommandBuffer cmd, long image) {
        barrier(stack, cmd, image,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image,
                                int srcStage, int srcAccess, int dstStage, int dstAccess) {
        VkImageMemoryBarrier2.Buffer bars = VkImageMemoryBarrier2.calloc(1, stack);
        bars.get(0).sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(bars));
    }

    private void destroyResources() {
        if (ctx != null) {
            try {
                destroyConvert();
                destroyGuard();
                destroyReactive();
            } catch (Throwable t) {
                LOGGER.warn("RtPlateBridge destroy pipelines failed", t);
            }
        }
        if (denoiseInputColor != null) {
            try { denoiseInputColor.destroy(); } catch (Throwable ignored) {}
            denoiseInputColor = null;
        }
        if (denoiseOutputColor != null) {
            try { denoiseOutputColor.destroy(); } catch (Throwable ignored) {}
            denoiseOutputColor = null;
        }
        if (upscalerInputColor != null) {
            try { upscalerInputColor.destroy(); } catch (Throwable ignored) {}
            upscalerInputColor = null;
        }
        currentUpscalerInput = null;
        if (upscalerOutputColor != null) {
            try { upscalerOutputColor.destroy(); } catch (Throwable ignored) {}
            upscalerOutputColor = null;
        }
        if (reactiveMask != null) {
            try { reactiveMask.destroy(); } catch (Throwable ignored) {}
            reactiveMask = null;
        }
    }

    private void destroyConvert() {
        VkDevice vk = device();
        if (vk == null) {
            packPipe = unpackPipe = convLayout = convPool = convDsl = 0L;
            convSetPack = convSetUnpack = 0L;
            convBoundPack = new long[0];
            convBoundUnpack = new long[0];
            convReady = false;
            return;
        }
        if (packPipe != 0L) { VK10.vkDestroyPipeline(vk, packPipe, null); packPipe = 0L; }
        if (unpackPipe != 0L) { VK10.vkDestroyPipeline(vk, unpackPipe, null); unpackPipe = 0L; }
        if (convLayout != 0L) { VK10.vkDestroyPipelineLayout(vk, convLayout, null); convLayout = 0L; }
        if (convPool != 0L) { VK10.vkDestroyDescriptorPool(vk, convPool, null); convPool = 0L; }
        if (convDsl != 0L) { VK10.vkDestroyDescriptorSetLayout(vk, convDsl, null); convDsl = 0L; }
        convSetPack = convSetUnpack = 0L;
        convBoundPack = new long[0];
        convBoundUnpack = new long[0];
        convReady = false;
    }

    private void destroyGuard() {
        VkDevice vk = device();
        if (vk == null) {
            guardPipe = guardLayout = guardPool = guardDsl = guardSet = 0L;
            guardBound = new long[0];
            guardReady = false;
            return;
        }
        if (guardPipe != 0L) { VK10.vkDestroyPipeline(vk, guardPipe, null); guardPipe = 0L; }
        if (guardLayout != 0L) { VK10.vkDestroyPipelineLayout(vk, guardLayout, null); guardLayout = 0L; }
        if (guardPool != 0L) { VK10.vkDestroyDescriptorPool(vk, guardPool, null); guardPool = 0L; }
        if (guardDsl != 0L) { VK10.vkDestroyDescriptorSetLayout(vk, guardDsl, null); guardDsl = 0L; }
        guardSet = 0L;
        guardBound = new long[0];
        guardReady = false;
    }

    private void destroyReactive() {
        VkDevice vk = device();
        if (vk == null) {
            reactivePipe = reactiveLayout = reactivePool = reactiveDsl = reactiveSet = 0L;
            reactiveBound = new long[0];
            reactiveReady = false;
            return;
        }
        if (reactivePipe != 0L) { VK10.vkDestroyPipeline(vk, reactivePipe, null); reactivePipe = 0L; }
        if (reactiveLayout != 0L) { VK10.vkDestroyPipelineLayout(vk, reactiveLayout, null); reactiveLayout = 0L; }
        if (reactivePool != 0L) { VK10.vkDestroyDescriptorPool(vk, reactivePool, null); reactivePool = 0L; }
        if (reactiveDsl != 0L) { VK10.vkDestroyDescriptorSetLayout(vk, reactiveDsl, null); reactiveDsl = 0L; }
        reactiveSet = 0L;
        reactiveBound = new long[0];
        reactiveReady = false;
    }

    private RtPlateProfile rebuildWithoutGuard(RtPlateProfile p) {
        return RtPlateProfile.builder()
                .rawBeautyFormat(p.rawBeautyFormat)
                .denoiseInputFormat(p.denoiseInputFormat)
                .denoiseOutputFormat(p.denoiseOutputFormat)
                .denoiseIdentityPack(p.denoiseIdentityPack)
                .denoiseIdentityUnpack(p.denoiseIdentityUnpack)
                .upscalerInputFormat(p.upscalerInputFormat)
                .upscalerOutputFormat(p.upscalerOutputFormat)
                .displayFormat(p.displayFormat)
                .identityPack(p.identityPack)
                .identityUnpack(p.identityUnpack)
                .needsReactiveMask(p.needsReactiveMask)
                .needsBlackoutGuard(false)
                .build();
    }
}
