package dev.comfyfluffy.caustica.denoise;

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
 * Specialized denoiser for transparent materials (glass, water, ice).
 *
 * <p>Unlike NRD REBLUR which assumes opaque diffuse+specular surfaces, this filter:
 * <ul>
 *   <li>Preserves high-frequency reflections (no albedo demodulation)</li>
 *   <li>Uses relaxed depth/normal thresholds (water waves, refraction)</li>
 *   <li>Applies aggressive temporal rejection (avoid ghost trails from moving geometry)</li>
 * </ul>
 *
 * <p>Pipeline: spatial bilateral (edge-aware) → temporal accumulation with disocclusion detection.
 */
public final class TransparentMaterialDenoiser {

    private boolean ready;
    private int width;
    private int height;

    // Spatial bilateral pass
    private long spatialDsl, spatialPool, spatialSet, spatialLayout, spatialPipeline;
    private RtImage spatialTemp;

    // Temporal accumulation pass
    private long temporalDsl, temporalPool, temporalSet, temporalLayout, temporalPipeline;
    private RtImage historyColor;
    private RtImage historyDepth;
    private boolean firstFrame = true;

    public TransparentMaterialDenoiser() {
    }

    public void init() {
        ready = true;
    }

    public void ensureSized(int width, int height) {
        if (!ready) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return;
        }

        if (spatialPipeline == 0L) {
            createSpatialPipeline(ctx);
        }
        if (temporalPipeline == 0L) {
            createTemporalPipeline(ctx);
        }

        if (this.width == width && this.height == height && spatialTemp != null && historyColor != null) {
            return;
        }

        destroyImages();
        // Pure RGB plates — match the beauty chain (B10G11R11). Depth stays a separate R32F image.
        spatialTemp = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "transparent spatial temp");
        historyColor = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "transparent history color");
        historyDepth = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT, "transparent history depth");

        this.width = width;
        this.height = height;
        firstFrame = true;
    }

    /**
     * Denoise transparent materials only (where mask == 1.0).
     *
     * @param transparencyMask R8 mask: 1.0 = transparent, 0.0 = opaque
     * @param inColor raw beauty (pre-NRD)
     * @param inNormal surface normal
     * @param inDepth device depth
     * @param inMotion motion vectors
     * @param outColor denoised output (blended with input based on mask)
     */
    public void dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage transparencyMask, RtImage inColor, RtImage inNormal,
                         RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready || spatialPipeline == 0L || temporalPipeline == 0L ||
            spatialTemp == null || historyColor == null || width <= 0 || height <= 0) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return;
        }

        // Pass 1: Spatial bilateral (edge-aware 5x5 kernel)
        // Relaxed depth/normal thresholds for water waves and refraction.
        bindSpatial(ctx, inColor, inNormal, inDepth, transparencyMask, spatialTemp);
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "transparent spatial")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, spatialPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, spatialLayout, 0,
                    stack.longs(spatialSet), null);
            ByteBuffer push = stack.malloc(16);
            // Relaxed thresholds for transparent materials
            push.putFloat(0, 0.15f);  // depthSigma (relaxed for water waves)
            push.putFloat(4, 0.35f);  // normalSigma (relaxed for refraction)
            push.putFloat(8, 0.8f);   // colorSigma (tighter to reject fireflies)
            push.putFloat(12, 0.0f);  // unused
            VK10.vkCmdPushConstants(cmd, spatialLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
        barrier(stack, cmd, spatialTemp.image);

        // Pass 2: Temporal accumulation with aggressive disocclusion
        // Rejects history on any depth/normal mismatch (avoid ghosts from moving water/glass).
        bindTemporal(ctx, spatialTemp, inDepth, inMotion, transparencyMask, historyColor, historyDepth, outColor);
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "transparent temporal")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, temporalPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, temporalLayout, 0,
                    stack.longs(temporalSet), null);
            ByteBuffer push = stack.malloc(16);
            push.putFloat(0, mvScaleX);
            push.putFloat(4, mvScaleY);
            push.putFloat(8, firstFrame ? 0.0f : 0.65f);  // temporalAlpha (0.65 = keep 65% history, aggressive for noise suppression)
            push.putFloat(12, 0.12f);  // disocclusionThreshold (relaxed to allow more history accumulation)
            VK10.vkCmdPushConstants(cmd, temporalLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
        barrier(stack, cmd, outColor.image);
        barrier(stack, cmd, historyColor.image);
        barrier(stack, cmd, historyDepth.image);

        firstFrame = false;
    }

    public void resetHistory() {
        firstFrame = true;
    }

    public void destroy() {
        destroyImages();
        RtContext ctx = RtContext.get();
        if (ctx != null) {
            if (spatialPipeline != 0L) {
                VK10.vkDestroyPipeline(ctx.vk(), spatialPipeline, null);
                VK10.vkDestroyPipelineLayout(ctx.vk(), spatialLayout, null);
                VK10.vkDestroyDescriptorPool(ctx.vk(), spatialPool, null);
                VK10.vkDestroyDescriptorSetLayout(ctx.vk(), spatialDsl, null);
                spatialPipeline = spatialLayout = spatialPool = spatialDsl = spatialSet = 0L;
            }
            if (temporalPipeline != 0L) {
                VK10.vkDestroyPipeline(ctx.vk(), temporalPipeline, null);
                VK10.vkDestroyPipelineLayout(ctx.vk(), temporalLayout, null);
                VK10.vkDestroyDescriptorPool(ctx.vk(), temporalPool, null);
                VK10.vkDestroyDescriptorSetLayout(ctx.vk(), temporalDsl, null);
                temporalPipeline = temporalLayout = temporalPool = temporalDsl = temporalSet = 0L;
            }
        }
        ready = false;
        width = 0;
        height = 0;
    }

    private void destroyImages() {
        if (spatialTemp != null) {
            spatialTemp.destroy();
            spatialTemp = null;
        }
        if (historyColor != null) {
            historyColor.destroy();
            historyColor = null;
        }
        if (historyDepth != null) {
            historyDepth.destroy();
            historyDepth = null;
        }
    }

    private void createSpatialPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int BINDINGS = 5; // inColor, inNormal, inDepth, mask, outTemp
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer pDsl = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, pDsl),
                    "vkCreateDescriptorSetLayout(transparent-spatial)");
            spatialDsl = pDsl.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDINGS);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(ctx.vk(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pPool),
                    "vkCreateDescriptorPool(transparent-spatial)");
            spatialPool = pPool.get(0);

            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(spatialPool).pSetLayouts(stack.longs(spatialDsl)), pSet),
                    "vkAllocateDescriptorSets(transparent-spatial)");
            spatialSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(ctx.vk(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(spatialDsl)).pPushConstantRanges(pcr), null, pLayout),
                    "vkCreatePipelineLayout(transparent-spatial)");
            spatialLayout = pLayout.get(0);

            byte[] spv;
            try (InputStream in = TransparentMaterialDenoiser.class.getResourceAsStream(
                    "/caustica/rt/transparent_spatial.comp.spv")) {
                if (in == null) {
                    throw new IllegalStateException("missing transparent_spatial.comp.spv");
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
                    "vkCreateShaderModule(transparent-spatial)");
            long mod = pMod.get(0);
            MemoryUtil.memFree(code);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod).pName(stack.UTF8("main"));
            LongBuffer pPipe = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(ctx.vk(), 0,
                    VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                            .stage(stage).layout(spatialLayout), null, pPipe),
                    "vkCreateComputePipelines(transparent-spatial)");
            spatialPipeline = pPipe.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), mod, null);
        }
    }

    private void createTemporalPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int BINDINGS = 7; // spatialIn, depth, motion, mask, historyColor, historyDepth, out
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer pDsl = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, pDsl),
                    "vkCreateDescriptorSetLayout(transparent-temporal)");
            temporalDsl = pDsl.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDINGS);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(ctx.vk(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pPool),
                    "vkCreateDescriptorPool(transparent-temporal)");
            temporalPool = pPool.get(0);

            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(temporalPool).pSetLayouts(stack.longs(temporalDsl)), pSet),
                    "vkAllocateDescriptorSets(transparent-temporal)");
            temporalSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(ctx.vk(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(temporalDsl)).pPushConstantRanges(pcr), null, pLayout),
                    "vkCreatePipelineLayout(transparent-temporal)");
            temporalLayout = pLayout.get(0);

            byte[] spv;
            try (InputStream in = TransparentMaterialDenoiser.class.getResourceAsStream(
                    "/caustica/rt/transparent_temporal.comp.spv")) {
                if (in == null) {
                    throw new IllegalStateException("missing transparent_temporal.comp.spv");
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
                    "vkCreateShaderModule(transparent-temporal)");
            long mod = pMod.get(0);
            MemoryUtil.memFree(code);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod).pName(stack.UTF8("main"));
            LongBuffer pPipe = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(ctx.vk(), 0,
                    VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                            .stage(stage).layout(temporalLayout), null, pPipe),
                    "vkCreateComputePipelines(transparent-temporal)");
            temporalPipeline = pPipe.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), mod, null);
        }
    }

    private void bindSpatial(RtContext ctx, RtImage inColor, RtImage inNormal, RtImage inDepth,
                             RtImage mask, RtImage outTemp) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] imgs = {inColor, inNormal, inDepth, mask, outTemp};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(imgs[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(spatialSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void bindTemporal(RtContext ctx, RtImage spatialIn, RtImage depth, RtImage motion,
                              RtImage mask, RtImage historyColor, RtImage historyDepth, RtImage out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] imgs = {spatialIn, depth, motion, mask, historyColor, historyDepth, out};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(imgs[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(temporalSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
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
}
