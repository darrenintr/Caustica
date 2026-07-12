package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Caustica's internal SVGF-lite denoise pass: a single-pass joint bilateral + temporal accumulation that
 * runs the path-traced color through a 3x3 spatial filter with depth/normal edge-stopping and a small
 * reprojected-history blend, before the upscaler (FSR / XeSS / DLSS-RR) reads it.
 *
 * <p>The point is to give the upscaler clean-enough input that its own temporal accumulator can lock on
 * for multi-frame convergence, without the path-tracing's per-pixel Monte-Carlo noise dragging the
 * accumulator's confidence around. The upscaler still does the heavy lifting — this pass is deliberately
 * not iterative (no a-trous wavelet); the cost is one full-resolution dispatch and one small push-constant
 * upload per frame.
 *
 * <p>The pass is gated by {@code caustica.rt.denoise.mode} (auto / svgf / off). Default: on for any
 * non-DLSS-RR path, off for DLSS-RR (DLSS-RR already denoises; running this first would just cost without
 * quality gain).
 *
 * <p>History ring: 3 slots, each holding the denoised color + the source depth + the source normal. We
 * read from {@code (frameCounter + 2) % 3} (the slot the previous frame wrote, two frames ago — the
 * "in-flight safe" horizon is 2 to match MC's frame pacing) and write to {@code frameCounter % 3}.
 * Sized at render res so they live alongside the path-traced color image.
 */
public final class RtDenoisePass {
    private static final String SHADER_DIR = "/caustica/rt/";
    private static final int RING = 3;

    /** Push constants: sigmaDepth, sigmaNormal, sigmaColor, temporalMin, temporalMax, motionScale, _pad0, _pad1. */
    private static final int PUSH_BYTES = 8 * 4;

    private final RtContext ctx;
    private final long descriptorSetLayout0;     // set 0: current-frame storage images + output + UBO
    private final long descriptorSetLayout1;     // set 1: history combined-image-samplers
    private final long descriptorPool;
    private final long pipelineLayout;
    private final long pipeline;
    private final long bilinearSampler;

    // History per slot: 3 images — denoised color (rgba16f), depth (r32f), normal (rgba16f).
    private final RtImage[] histColor = new RtImage[RING];
    private final RtImage[] histDepth = new RtImage[RING];
    private final RtImage[] histNormal = new RtImage[RING];
    private final long[] descriptorSets0 = new long[RING];
    private final long[] descriptorSets1 = new long[RING];

    private int lastW = -1;
    private int lastH = -1;
    private long frameCounter;
    private boolean destroyed;

    private RtDenoisePass(RtContext ctx, long dsl0, long dsl1, long pool, long layout, long pipeline, long sampler) {
        this.ctx = ctx;
        this.descriptorSetLayout0 = dsl0;
        this.descriptorSetLayout1 = dsl1;
        this.descriptorPool = pool;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
        this.bilinearSampler = sampler;
    }

    public static RtDenoisePass create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Set 0: current-frame storage images + output. 5 bindings (no UBO — params are push consts).
            VkDescriptorSetLayoutBinding.Buffer binds0 = VkDescriptorSetLayoutBinding.calloc(5, stack);
            binds0.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds0.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds0.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds0.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds0.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslci0 = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds0);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci0, null, p), "vkCreateDescriptorSetLayout(denoise s0)");
            long dsl0 = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl0, "denoise s0");

            // Set 1: 3 combined-image-samplers for history.
            VkDescriptorSetLayoutBinding.Buffer binds1 = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                binds1.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci1 = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci1, null, p), "vkCreateDescriptorSetLayout(denoise s1)");
            long dsl1 = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl1, "denoise s1");

            // Pool: per-slot (5 storage + 3 combined samplers) × RING slots.
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(RING * 5);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(RING * 3);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(RING * 2).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(denoise)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "denoise pool");

            // Pipeline layout: two descriptor sets + push constants.
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl0, dsl1)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(denoise)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "denoise layout");

            // Compute pipeline.
            long module = loadModule(vk, stack, "denoise.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "denoise shader");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(denoise)");
            long pipeline = pPipeline.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "denoise");
            VK10.vkDestroyShaderModule(vk, module, null);

            // Bilinear sampler for history.
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            LongBuffer pSampler = stack.mallocLong(1);
            check(VK10.vkCreateSampler(vk, sci, null, pSampler), "vkCreateSampler(denoise)");
            long sampler = pSampler.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, "denoise sampler");

            return new RtDenoisePass(ctx, dsl0, dsl1, pool, layout, pipeline, sampler);
        }
    }

    /**
     * Ensure the history buffers and descriptor sets are allocated at the current render resolution.
     * Idempotent — only does work on size changes.
     */
    private void ensureSized(int width, int height) {
        if (width == lastW && height == lastH && histColor[0] != null) {
            return;
        }
        ctx.waitIdle();
        destroyHistory();
        lastW = width;
        lastH = height;
        for (int i = 0; i < RING; i++) {
            histColor[i] = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "denoise hist color " + i);
            histDepth[i] = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT,
                    "denoise hist depth " + i);
            histNormal[i] = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "denoise hist normal " + i);
        }
        // Allocate descriptor sets and write per-slot images.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Set 0 layouts.
            LongBuffer layouts0 = stack.mallocLong(RING);
            for (int i = 0; i < RING; i++) layouts0.put(i, descriptorSetLayout0);
            VkDescriptorSetAllocateInfo dsai0 = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(layouts0);
            LongBuffer pSets0 = stack.mallocLong(RING);
            check(VK10.vkAllocateDescriptorSets(vk(ctx), dsai0, pSets0), "vkAllocateDescriptorSets(denoise s0)");
            for (int i = 0; i < RING; i++) descriptorSets0[i] = pSets0.get(i);

            // Set 1 layouts.
            LongBuffer layouts1 = stack.mallocLong(RING);
            for (int i = 0; i < RING; i++) layouts1.put(i, descriptorSetLayout1);
            VkDescriptorSetAllocateInfo dsai1 = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(layouts1);
            LongBuffer pSets1 = stack.mallocLong(RING);
            check(VK10.vkAllocateDescriptorSets(vk(ctx), dsai1, pSets1), "vkAllocateDescriptorSets(denoise s1)");
            for (int i = 0; i < RING; i++) descriptorSets1[i] = pSets1.get(i);

            // Bind each slot's images. The per-frame dispatch swaps which set is bound, not which
            // image is bound — so this write happens once at allocation time.
            for (int i = 0; i < RING; i++) {
                // Set 0: inColor/inNormal/inDepth/inMotion bindings — point at THIS slot's history so a
                // single fixed set can be used as a "previous frame" reference for any slot. We use
                // the same storage image the dispatch reads from; the dispatch's writes go to outColor
                // (binding 4), and the actual *current* inColor comes from a separate read.
                // NOTE: the per-slot descriptor set pattern means the "current frame" inputs change
                // every frame (they come from the path tracer's live targets) — we can't pre-bind
                // those. The current-frame inputs (binding 0..3 + binding 4 output) get re-written in
                // dispatch() each frame. Binding 5 (params UBO) also changes per frame.
                // Set 1: history samplers point at THIS slot's history.
                long[] histViews = { histColor[i].view, histNormal[i].view, histDepth[i].view };
                VkWriteDescriptorSet.Buffer histWrites = VkWriteDescriptorSet.calloc(3, stack);
                for (int b = 0; b < 3; b++) {
                    VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                    info.get(0).sampler(bilinearSampler).imageView(histViews[b]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    histWrites.get(b).sType$Default().dstSet(descriptorSets1[i]).dstBinding(b)
                            .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(info);
                }
                VK10.vkUpdateDescriptorSets(vk(ctx), histWrites, null);
            }
        }
    }

    /**
     * Run the denoise pass. Writes denoised color to {@code outColor} (consumed by the upscaler) and
     * updates slot {@code slot} of the history ring for the next frame.
     */
    public void dispatch(VkCommandBuffer cmd, RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                        RtImage outColor, int width, int height) {
        ensureSized(width, height);
        int writeSlot = (int) (frameCounter % RING);
        int readSlot = (writeSlot + 1) % RING;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Update set 0 for this frame: the live (in-frame) images and the output.
            // Bindings 0..3 = current frame, binding 4 = output. Update every frame because the
            // current-frame images (path tracer output) live in the always-allocated storage images
            // and never change handles, but we re-bind for safety (the ring of writeSlot handles
            // the in-flight-safety requirement that no two in-flight dispatches can write the same
            // output image).
            VkDescriptorImageInfo.Buffer imgInfos = VkDescriptorImageInfo.calloc(5, stack);
            imgInfos.get(0).imageView(inColor.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgInfos.get(1).imageView(inNormal.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgInfos.get(2).imageView(inDepth.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgInfos.get(3).imageView(inMotion.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgInfos.get(4).imageView(outColor.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes0 = VkWriteDescriptorSet.calloc(5, stack);
            for (int b = 0; b < 5; b++) {
                writes0.get(b).sType$Default().dstSet(descriptorSets0[writeSlot]).dstBinding(b)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(imgInfos.slice(b, 1));
            }
            VK10.vkUpdateDescriptorSets(vk(ctx), writes0, null);

            // Push constants: 8 floats, read from config every frame so the video-settings UI can
            // retune at runtime.
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putFloat(0, dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.SIGMA_DEPTH.value());
            push.putFloat(4, dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.SIGMA_NORMAL.value());
            push.putFloat(8, dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.SIGMA_COLOR.value());
            push.putFloat(12, 0.0f);                                                       // temporalMin (disoccluded → pure bilateral)
            push.putFloat(16, dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.TEMPORAL_MAX.value());
            push.putFloat(20, 1.0f / width);                                                // motionScale (render pixels → UV)
            push.putFloat(24, 0f);
            push.putFloat(28, 0f);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "denoise svgf-lite")) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                        stack.longs(descriptorSets0[writeSlot], descriptorSets1[readSlot]), null);
                VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            }
            // Note: the actual history write (copying outColor into histColor[writeSlot]) is done
            // by the caller in recordFrame, since the denoise output may be the upscaler's input
            // and the copy needs to happen at the right point in the command stream.
        }
        frameCounter++;
    }

    /** Index of the history slot that was most-recently written (for the caller's post-pass copy). */
    public int currentWriteSlot() {
        return (int) ((frameCounter - 1 + RING) % RING);
    }

    public RtImage historyColor(int slot) {
        return histColor[slot];
    }

    public RtImage historyDepth(int slot) {
        return histDepth[slot];
    }

    public RtImage historyNormal(int slot) {
        return histNormal[slot];
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        VkDevice vkDev = vk(ctx);
        destroyHistory();
        VK10.vkDestroyPipeline(vkDev, pipeline, null);
        VK10.vkDestroyPipelineLayout(vkDev, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vkDev, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vkDev, descriptorSetLayout0, null);
        VK10.vkDestroyDescriptorSetLayout(vkDev, descriptorSetLayout1, null);
        VK10.vkDestroySampler(vkDev, bilinearSampler, null);
    }

    private void destroyHistory() {
        for (int i = 0; i < RING; i++) {
            if (histColor[i] != null) { histColor[i].destroy(); histColor[i] = null; }
            if (histDepth[i] != null) { histDepth[i].destroy(); histDepth[i] = null; }
            if (histNormal[i] != null) { histNormal[i].destroy(); histNormal[i] = null; }
        }
    }

    private static VkDevice vk(RtContext ctx) {
        return ctx.vk();
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDenoisePass.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(code);
        }
    }
}
