package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Firefly kill pass: 3x3 per-channel median filter on the path-tracer radiance plate.
 *
 * <p>SPP=1 path tracing produces single-pixel HDR spikes (one ray hits an emissive
 * at high intensity -> clamped to MAX_HDR_RADIANCE but still orders of magnitude
 * above the dark surroundings). NRD's spatial filter doesn't always fully dampen
 * these; TAAU's temporal accumulator can replay them frame after frame. The
 * <em>median</em> of a 3x3 window rejects a single-pixel spike cleanly -- one
 * bright ray can't dominate 8 neighbours.
 *
 * <p>Both NRD and TAAU read from the output of this pass (instead of the raw
 * path-tracer output), so fireflies are killed at the source and never reach
 * the final composite.
 */
public final class FireflyKill {

    private static final String SHADER_PATH = "/caustica/rt/firefly_kill.comp.spv";

    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long descriptorSet;
    private boolean ready;
    private int width;
    private int height;

    /** Create the firefly-kill pipeline and the output image. Idempotent. */
    public boolean ensureSized(RtContext ctx, int width, int height, RtImage inRadiance) {
        if (this.width == width && this.height == height && ready) {
            return true;
        }
        destroy();
        this.width = width;
        this.height = height;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int bindings = 2;
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(bindings, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer pDsl = stack.mallocLong(1);
            VK10.vkCreateDescriptorSetLayout(ctx.vk(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds),
                    null, pDsl);
            descriptorSetLayout = pDsl.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            LongBuffer pPool = stack.mallocLong(1);
            VK10.vkCreateDescriptorPool(ctx.vk(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                            .maxSets(1).pPoolSizes(sizes),
                    null, pPool);
            descriptorPool = pPool.get(0);

            LongBuffer pSet = stack.mallocLong(1);
            VK10.vkAllocateDescriptorSets(ctx.vk(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout)),
                    pSet);
            descriptorSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(0);
            LongBuffer pLayout = stack.mallocLong(1);
            VK10.vkCreatePipelineLayout(ctx.vk(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(descriptorSetLayout))
                            .pPushConstantRanges(pcr),
                    null, pLayout);
            pipelineLayout = pLayout.get(0);

            byte[] spv;
            try (InputStream in = FireflyKill.class.getResourceAsStream(SHADER_PATH)) {
                if (in == null) {
                    throw new IllegalStateException("Missing " + SHADER_PATH);
                }
                spv = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            ByteBuffer code = org.lwjgl.system.MemoryUtil.memAlloc(spv.length);
            code.put(spv).flip();
            LongBuffer pMod = stack.mallocLong(1);
            VK10.vkCreateShaderModule(ctx.vk(),
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pMod);
            long mod = pMod.get(0);
            org.lwjgl.system.MemoryUtil.memFree(code);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod)
                    .pName(stack.UTF8("main"));
            LongBuffer pPipe = stack.mallocLong(1);
            VK10.vkCreateComputePipelines(ctx.vk(), 0,
                    VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                            .stage(stage).layout(pipelineLayout), null, pPipe);
            pipeline = pPipe.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), mod, null);
        }
        ready = true;
        return true;
    }

    /** Dispatch the 3x3 median pass. in -> out (both rgba16f at render res). */
    public void dispatch(long cmd, RtContext ctx, RtImage inRadiance, RtImage outRadiance) {
        if (!ready || pipeline == 0L || inRadiance == null || outRadiance == null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Bind descriptors.
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            VkDescriptorImageInfo.Buffer info0 = VkDescriptorImageInfo.calloc(1, stack);
            info0.get(0).imageView(inRadiance.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(info0);
            VkDescriptorImageInfo.Buffer info1 = VkDescriptorImageInfo.calloc(1, stack);
            info1.get(0).imageView(outRadiance.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(info1);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);

            VkCommandBuffer cb = new VkCommandBuffer(cmd, ctx.vk());
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            int gx = (width + 7) / 8;
            int gy = (height + 7) / 8;
            VK10.vkCmdDispatch(cb, gx, gy, 1);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("FireflyKill dispatch failed", t);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public void destroy() {
        ready = false;
        if (pipeline != 0L && RtContext.get() != null) {
            VK10.vkDestroyPipeline(RtContext.get().vk(), pipeline, null);
            pipeline = 0L;
        }
        if (pipelineLayout != 0L && RtContext.get() != null) {
            VK10.vkDestroyPipelineLayout(RtContext.get().vk(), pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (descriptorSetLayout != 0L && RtContext.get() != null) {
            VK10.vkDestroyDescriptorSetLayout(RtContext.get().vk(), descriptorSetLayout, null);
            descriptorSetLayout = 0L;
        }
        if (descriptorPool != 0L && RtContext.get() != null) {
            VK10.vkDestroyDescriptorPool(RtContext.get().vk(), descriptorPool, null);
            descriptorPool = 0L;
        }
        width = 0;
        height = 0;
    }
}