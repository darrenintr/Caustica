package dev.comfyfluffy.caustica.display;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
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
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
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

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * FidelityFX CAS-style sharpen pass. Pure compute, runs at display resolution after upscale.
 */
public final class CasSharpenPass {
    private static final String SHADER = "/caustica/rt/cas.comp.spv";

    private boolean ready;
    private int width;
    private int height;

    private long dsl;
    private long pool;
    private long set;
    private long layout;
    private long pipeline;
    private RtImage temp;
    private final long[] boundViews = new long[2];

    public void ensureSized(int width, int height) {
        RtContext ctx = RtContext.get();
        if (ctx == null || width <= 0 || height <= 0) {
            return;
        }
        if (pipeline == 0L) {
            try {
                createPipeline(ctx);
                ready = true;
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("CAS pipeline create failed; sharpen disabled", t);
                ready = false;
                return;
            }
        }
        if (this.width == width && this.height == height && temp != null) {
            return;
        }
        if (temp != null) {
            temp.destroy();
            temp = null;
        }
        temp = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "cas temp");
        this.width = width;
        this.height = height;
    }

    /**
     * Sharpen {@code image} in place (via internal temp). No-op if sharpness≈0 or not ready.
     * @return true if the image was rewritten
     */
    public boolean dispatchInPlace(MemoryStack stack, VkCommandBuffer cmd, RtImage image, float sharpness) {
        if (!ready || pipeline == 0L || temp == null || image == null) {
            return false;
        }
        float s = Math.max(0f, Math.min(1f, sharpness));
        if (s <= 1e-4f) {
            return false;
        }
        if (image.width != width || image.height != height) {
            ensureSized(image.width, image.height);
            if (temp == null) {
                return false;
            }
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }

        bind(ctx, image, temp);
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "cas sharpen")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(set), null);
            ByteBuffer push = stack.malloc(16);
            push.putInt(0, width);
            push.putInt(4, height);
            push.putFloat(8, s);
            push.putFloat(12, 0f);
            VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
        copyBack(stack, cmd, temp, image);
        return true;
    }

    public void destroy() {
        RtContext ctx = RtContext.get();
        if (ctx != null) {
            VkDevice vk = ctx.vk();
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
        }
        if (temp != null) {
            temp.destroy();
            temp = null;
        }
        pipeline = layout = pool = dsl = set = 0L;
        Arrays.fill(boundViews, 0L);
        ready = false;
        width = height = 0;
    }

    public boolean isReady() {
        return ready;
    }

    private void createPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(cas)");
            dsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(cas)");
            pool = p.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet), "vkAllocateDescriptorSets(cas)");
            set = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(cas)");
            layout = p.get(0);

            long module = loadModule(ctx.vk(), stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p), "vkCreateComputePipelines(cas)");
            pipeline = p.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void bind(RtContext ctx, RtImage in, RtImage out) {
        if (boundViews[0] == in.view && boundViews[1] == out.view) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            VkDescriptorImageInfo.Buffer inInfo = VkDescriptorImageInfo.calloc(1, stack);
            inInfo.get(0).imageView(in.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(inInfo);
            VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
            outInfo.get(0).imageView(out.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            boundViews[0] = in.view;
            boundViews[1] = out.view;
        }
    }

    private void copyBack(MemoryStack stack, VkCommandBuffer cmd, RtImage source, RtImage destination) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
        barriers.get(0).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(source.image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        barriers.get(1).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(destination.image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0)
                .srcSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1))
                .dstSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1))
                .extent(it -> it.width(width).height(height).depth(1));
        VK10.vkCmdCopyImage(cmd,
                source.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                destination.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                region);

        barriers.get(0)
                .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT);
        barriers.get(1)
                .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream in = CasSharpenPass.class.getResourceAsStream(SHADER)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(cas)");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
