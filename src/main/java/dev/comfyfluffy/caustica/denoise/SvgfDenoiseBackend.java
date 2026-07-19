package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * SVGF (Spatiotemporal Variance-Guided Filtering) denoiser.
 *
 * A three-pass compute shader denoiser:
 * 1. Temporal reprojection - reuse history with motion vectors
 * 2. Variance estimation - compute spatial variance
 * 3. À-Trous wavelet filter - edge-preserving spatial filter (5 iterations)
 *
 * Better quality than bilateral at low SPP (1-2), pure shader implementation.
 */
public final class SvgfDenoiseBackend implements CausticaDenoiseBackend {

    private static final String SHADER_DIR = "/caustica/rt/denoise/";

    private boolean ready;
    private int width;
    private int height;

    // Vulkan resources
    private long dslTemporal;
    private long dslVariance;
    private long dslAtrous;
    private long layoutTemporal;
    private long layoutVariance;
    private long layoutAtrous;
    private long pipelineTemporal;
    private long pipelineVariance;
    private long pipelineAtrous;
    private long pool;
    private long setTemporal;
    private long setVariance;
    private long[] setsAtrous = new long[5]; // 5 iterations

    // Ping-pong buffers
    private RtImage colorHistory;
    private RtImage momentHistory;
    private RtImage varianceBuffer;
    private RtImage tempColorOut;
    private RtImage[] atrousPingPong = new RtImage[2];

    private int frameIndex;

    public SvgfDenoiseBackend() {
    }

    @Override
    public String name() {
        return "SVGF";
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public boolean supportsAsyncCompute() {
        return true; // SVGF is pure compute, fully supports async
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

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        ready = true;
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
        if (pipelineTemporal == 0L) {
            createPipelines(ctx);
        }
        if (this.width == width && this.height == height && colorHistory != null) {
            return;
        }
        resizeBuffers(ctx, width, height);
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                        RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                        float mvScaleX, float mvScaleY,
                        RtImage outColor) {
        if (!ready || pipelineTemporal == 0L) {
            return false;
        }

        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }

        int w = inColor.width;
        int h = inColor.height;
        int groupsX = (w + 15) / 16;
        int groupsY = (h + 15) / 16;

        try {
            // Pass 1: Temporal reprojection
            updateDescriptorsTemporal(ctx, inColor, inNormal, inDepth, inMotion);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineTemporal);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    layoutTemporal, 0, stack.longs(setTemporal), null);

            ByteBuffer pushTemporal = stack.malloc(32);
            pushTemporal.putInt(0, w);
            pushTemporal.putInt(4, h);
            pushTemporal.putFloat(8, 0.1f);   // alpha
            pushTemporal.putFloat(12, 0.01f); // depthThreshold
            pushTemporal.putFloat(16, 0.5f);  // normalThreshold
            pushTemporal.putInt(20, frameIndex);
            VK10.vkCmdPushConstants(cmd, layoutTemporal, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushTemporal);
            VK10.vkCmdDispatch(cmd, groupsX, groupsY, 1);

            barrier(cmd, stack, tempColorOut.image);

            // Pass 2: Variance estimation
            updateDescriptorsVariance(ctx);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineVariance);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    layoutVariance, 0, stack.longs(setVariance), null);

            ByteBuffer pushVariance = stack.malloc(8);
            pushVariance.putInt(0, w);
            pushVariance.putInt(4, h);
            VK10.vkCmdPushConstants(cmd, layoutVariance, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushVariance);
            VK10.vkCmdDispatch(cmd, groupsX, groupsY, 1);

            barrier(cmd, stack, varianceBuffer.image);

            // Pass 3: À-Trous wavelet filter (5 iterations)
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineAtrous);

            int[] stepSizes = {1, 2, 4, 8, 16};
            for (int i = 0; i < 5; i++) {
                int srcIdx = i % 2;
                int dstIdx = 1 - srcIdx;

                RtImage input = (i == 0) ? varianceBuffer : atrousPingPong[srcIdx];
                RtImage output = (i == 4) ? outColor : atrousPingPong[dstIdx];

                updateDescriptorsAtrous(ctx, i, input, inDepth, inNormal, output);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                        layoutAtrous, 0, stack.longs(setsAtrous[i]), null);

                ByteBuffer pushAtrous = stack.malloc(24);
                pushAtrous.putInt(0, w);
                pushAtrous.putInt(4, h);
                pushAtrous.putInt(8, stepSizes[i]);
                pushAtrous.putFloat(12, 0.01f);   // sigmaDepth
                pushAtrous.putFloat(16, 128.0f);  // sigmaNormal
                pushAtrous.putFloat(20, 4.0f);    // sigmaLuminance
                VK10.vkCmdPushConstants(cmd, layoutAtrous, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushAtrous);
                VK10.vkCmdDispatch(cmd, groupsX, groupsY, 1);

                if (i < 4) {
                    barrier(cmd, stack, output.image);
                }
            }

            // Copy temp output to history for next frame
            barrier(cmd, stack, tempColorOut.image);
            copyImage(cmd, stack, tempColorOut, colorHistory);

            frameIndex++;
            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("SVGF denoise failed", t);
            return false;
        }
    }

    @Override
    public void resetHistory() {
        frameIndex = 0;
        if (colorHistory != null) {
            colorHistory.destroy();
            colorHistory = null;
        }
        if (momentHistory != null) {
            momentHistory.destroy();
            momentHistory = null;
        }
    }

    @Override
    public void destroy() {
        if (!ready) {
            return;
        }

        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return;
        }

        VkDevice device = ctx.vk();
        if (pipelineTemporal != 0L) VK10.vkDestroyPipeline(device, pipelineTemporal, null);
        if (pipelineVariance != 0L) VK10.vkDestroyPipeline(device, pipelineVariance, null);
        if (pipelineAtrous != 0L) VK10.vkDestroyPipeline(device, pipelineAtrous, null);
        if (layoutTemporal != 0L) VK10.vkDestroyPipelineLayout(device, layoutTemporal, null);
        if (layoutVariance != 0L) VK10.vkDestroyPipelineLayout(device, layoutVariance, null);
        if (layoutAtrous != 0L) VK10.vkDestroyPipelineLayout(device, layoutAtrous, null);
        if (dslTemporal != 0L) VK10.vkDestroyDescriptorSetLayout(device, dslTemporal, null);
        if (dslVariance != 0L) VK10.vkDestroyDescriptorSetLayout(device, dslVariance, null);
        if (dslAtrous != 0L) VK10.vkDestroyDescriptorSetLayout(device, dslAtrous, null);
        if (pool != 0L) VK10.vkDestroyDescriptorPool(device, pool, null);

        if (colorHistory != null) colorHistory.destroy();
        if (momentHistory != null) momentHistory.destroy();
        if (varianceBuffer != null) varianceBuffer.destroy();
        if (tempColorOut != null) tempColorOut.destroy();
        if (atrousPingPong[0] != null) atrousPingPong[0].destroy();
        if (atrousPingPong[1] != null) atrousPingPong[1].destroy();

        ready = false;
        CausticaMod.LOGGER.info("SVGF denoiser destroyed");
    }

    private void resizeBuffers(RtContext ctx, int width, int height) {
        if (colorHistory != null) colorHistory.destroy();
        if (momentHistory != null) momentHistory.destroy();
        if (varianceBuffer != null) varianceBuffer.destroy();
        if (tempColorOut != null) tempColorOut.destroy();
        if (atrousPingPong[0] != null) atrousPingPong[0].destroy();
        if (atrousPingPong[1] != null) atrousPingPong[1].destroy();

        colorHistory = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf-color-history");
        momentHistory = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf-moment-history");
        varianceBuffer = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf-variance");
        tempColorOut = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf-temp-out");
        atrousPingPong[0] = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf-atrous-0");
        atrousPingPong[1] = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf-atrous-1");

        this.width = width;
        this.height = height;
        frameIndex = 0;

        CausticaMod.LOGGER.info("SVGF buffers resized to {}x{}", width, height);
    }

    private void createPipelines(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = ctx.vk();

            // Create descriptor set layouts
            createDescriptorSetLayouts(ctx, stack);

            // Create descriptor pool
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(50); // generous pool
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(10).pPoolSizes(poolSizes);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(device, dpci, null, pPool), "vkCreateDescriptorPool(svgf)");
            pool = pPool.get(0);

            // Allocate descriptor sets
            LongBuffer pSet = stack.mallocLong(1);
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(dslTemporal));
            check(VK10.vkAllocateDescriptorSets(device, dsai, pSet), "vkAllocateDescriptorSets(svgf-temporal)");
            setTemporal = pSet.get(0);

            dsai.pSetLayouts(stack.longs(dslVariance));
            check(VK10.vkAllocateDescriptorSets(device, dsai, pSet), "vkAllocateDescriptorSets(svgf-variance)");
            setVariance = pSet.get(0);

            for (int i = 0; i < 5; i++) {
                dsai.pSetLayouts(stack.longs(dslAtrous));
                check(VK10.vkAllocateDescriptorSets(device, dsai, pSet), "vkAllocateDescriptorSets(svgf-atrous-" + i + ")");
                setsAtrous[i] = pSet.get(0);
            }

            // Load shaders and create pipelines
            long temporalModule = loadModule(device, stack, "svgf_temporal.comp.spv");
            long varianceModule = loadModule(device, stack, "svgf_variance.comp.spv");
            long atrousModule = loadModule(device, stack, "svgf_atrous.comp.spv");

            // Temporal pipeline
            VkPushConstantRange.Buffer pcrTemporal = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(32);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(dslTemporal)).pPushConstantRanges(pcrTemporal);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(device, plci, null, pLayout), "vkCreatePipelineLayout(svgf-temporal)");
            layoutTemporal = pLayout.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(temporalModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layoutTemporal);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, cpci, null, pPipeline), "vkCreateComputePipelines(svgf-temporal)");
            pipelineTemporal = pPipeline.get(0);

            // Variance pipeline
            VkPushConstantRange.Buffer pcrVariance = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
            plci.pSetLayouts(stack.longs(dslVariance)).pPushConstantRanges(pcrVariance);
            check(VK10.vkCreatePipelineLayout(device, plci, null, pLayout), "vkCreatePipelineLayout(svgf-variance)");
            layoutVariance = pLayout.get(0);

            stage.module(varianceModule);
            cpci.get(0).stage(stage).layout(layoutVariance);
            check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, cpci, null, pPipeline), "vkCreateComputePipelines(svgf-variance)");
            pipelineVariance = pPipeline.get(0);

            // Atrous pipeline
            VkPushConstantRange.Buffer pcrAtrous = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(24);
            plci.pSetLayouts(stack.longs(dslAtrous)).pPushConstantRanges(pcrAtrous);
            check(VK10.vkCreatePipelineLayout(device, plci, null, pLayout), "vkCreatePipelineLayout(svgf-atrous)");
            layoutAtrous = pLayout.get(0);

            stage.module(atrousModule);
            cpci.get(0).stage(stage).layout(layoutAtrous);
            check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, cpci, null, pPipeline), "vkCreateComputePipelines(svgf-atrous)");
            pipelineAtrous = pPipeline.get(0);

            VK10.vkDestroyShaderModule(device, temporalModule, null);
            VK10.vkDestroyShaderModule(device, varianceModule, null);
            VK10.vkDestroyShaderModule(device, atrousModule, null);

            CausticaMod.LOGGER.info("SVGF pipelines created");
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("SVGF pipeline creation failed", t);
            throw new RuntimeException("SVGF pipeline creation failed", t);
        }
    }

    private void createDescriptorSetLayouts(RtContext ctx, MemoryStack stack) {
        VkDevice device = ctx.vk();
        LongBuffer pLayout = stack.mallocLong(1);

        // Temporal layout: 8 bindings (6 samplers + 2 storage images)
        VkDescriptorSetLayoutBinding.Buffer bindsTemporal = VkDescriptorSetLayoutBinding.calloc(8, stack);
        for (int i = 0; i < 8; i++) {
            bindsTemporal.get(i).binding(i).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindsTemporal);
        check(VK10.vkCreateDescriptorSetLayout(device, dslci, null, pLayout), "vkCreateDescriptorSetLayout(svgf-temporal)");
        dslTemporal = pLayout.get(0);

        // Variance layout: 3 bindings
        VkDescriptorSetLayoutBinding.Buffer bindsVariance = VkDescriptorSetLayoutBinding.calloc(3, stack);
        for (int i = 0; i < 3; i++) {
            bindsVariance.get(i).binding(i).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        dslci.pBindings(bindsVariance);
        check(VK10.vkCreateDescriptorSetLayout(device, dslci, null, pLayout), "vkCreateDescriptorSetLayout(svgf-variance)");
        dslVariance = pLayout.get(0);

        // Atrous layout: 5 bindings
        VkDescriptorSetLayoutBinding.Buffer bindsAtrous = VkDescriptorSetLayoutBinding.calloc(5, stack);
        for (int i = 0; i < 5; i++) {
            bindsAtrous.get(i).binding(i).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        dslci.pBindings(bindsAtrous);
        check(VK10.vkCreateDescriptorSetLayout(device, dslci, null, pLayout), "vkCreateDescriptorSetLayout(svgf-atrous)");
        dslAtrous = pLayout.get(0);
    }

    private void updateDescriptorsTemporal(RtContext ctx, RtImage inColor, RtImage inNormal,
                                           RtImage inDepth, RtImage inMotion) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(8, stack);
            RtImage[] images = {inColor, colorHistory, momentHistory, inMotion, inDepth, inNormal, tempColorOut, momentHistory};

            for (int i = 0; i < 8; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(setTemporal).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void updateDescriptorsVariance(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            RtImage[] images = {tempColorOut, momentHistory, varianceBuffer};

            for (int i = 0; i < 3; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(setVariance).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void updateDescriptorsAtrous(RtContext ctx, int iteration, RtImage input,
                                         RtImage inDepth, RtImage inNormal, RtImage output) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
            RtImage[] images = {input, inDepth, inNormal, varianceBuffer, output};

            for (int i = 0; i < 5; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(setsAtrous[iteration]).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void barrier(VkCommandBuffer cmd, MemoryStack stack, long image) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(1, stack);
        barriers.get(0)
                .sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private void copyImage(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        // Simple blit copy (could use vkCmdCopyImage for exact copy)
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0)
                .srcSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1))
                .dstSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1))
                .srcOffsets(0, it -> it.set(0, 0, 0))
                .srcOffsets(1, it -> it.set(src.width, src.height, 1))
                .dstOffsets(0, it -> it.set(0, 0, 0))
                .dstOffsets(1, it -> it.set(dst.width, dst.height, 1));

        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                region, VK10.VK_FILTER_NEAREST);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = SvgfDenoiseBackend.class.getResourceAsStream(SHADER_DIR + name)) {
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
}
