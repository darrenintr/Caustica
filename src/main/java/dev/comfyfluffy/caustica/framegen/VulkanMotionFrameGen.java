package dev.comfyfluffy.caustica.framegen;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
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
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
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
import java.util.Arrays;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Vendor-neutral frame interpolation implemented with core Vulkan compute and transfer commands.
 *
 * <p>The pass reprojects the previous display-resolution color through Caustica's motion guide, rejects
 * history across depth discontinuities, and blends it toward the real current frame according to the
 * generated-frame index. All generated indices read the same previous-frame history; current color/depth
 * are copied into history only after the final generated index has been recorded.
 */
public final class VulkanMotionFrameGen implements FrameGen {
    public static final VulkanMotionFrameGen INSTANCE = new VulkanMotionFrameGen();

    private static final String SHADER_DIR = "/caustica/rt/";
    private static final String SDR_SHADER = "framegen_motion_rgba8.comp.spv";
    private static final String HDR_SHADER = "framegen_motion_rgba16f.comp.spv";
    private static final int BINDING_COUNT = 6;
    private static final int PUSH_BYTES = 32;
    private static final int MAX_GENERATED_FRAMES = 3;

    private RtContext ctx;
    private boolean probed;
    private boolean available;
    private boolean failed;
    private boolean ready;
    private boolean historyReady;

    private int width = -1;
    private int height = -1;
    private int renderWidth = -1;
    private int renderHeight = -1;
    private int format = Integer.MIN_VALUE;

    private long descriptorSetLayout;
    private long descriptorPool;
    private long pipelineLayout;
    private long sdrPipeline;
    private long hdrPipeline;
    private long sampler;
    private long[] descriptorSets = new long[0];
    private long[][] boundViews = new long[0][];
    private RtImage colorHistory;
    private RtImage depthHistory;

    private VulkanMotionFrameGen() {
    }

    @Override
    public String name() {
        return "Vulkan motion frame generation";
    }

    @Override
    public boolean isEnabled() {
        return CausticaConfig.Rt.Fg.ENABLED.value();
    }

    @Override
    public boolean isAvailable() {
        return isEnabled() && available && !failed;
    }

    @Override
    public boolean isReady() {
        return isAvailable() && ready && colorHistory != null && depthHistory != null;
    }

    @Override
    public int effectiveMultiFrameCount() {
        if (!isEnabled()) {
            return 0;
        }
        return Math.clamp(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value(), 1, MAX_GENERATED_FRAMES);
    }

    @Override
    public void probeAvailabilityOnce() {
        if (probed || failed || !isEnabled()) {
            return;
        }
        RtContext current = RtContext.get();
        if (current == null) {
            return;
        }
        ctx = current;
        probed = true;
        available = true;
        CausticaMod.LOGGER.info("Vendor-neutral Vulkan frame generation available (up to {} generated frames)",
                MAX_GENERATED_FRAMES);
    }

    @Override
    public boolean featureReadyFor(int width, int height, int renderWidth, int renderHeight,
            int backbufferFormat) {
        return isReady()
                && this.width == width && this.height == height
                && this.renderWidth == renderWidth && this.renderHeight == renderHeight
                && this.format == backbufferFormat
                && descriptorSets.length == effectiveMultiFrameCount();
    }

    @Override
    public synchronized boolean ensureFeature(long commandBuffer, int width, int height,
            int renderWidth, int renderHeight, int backbufferFormat) {
        if (!isEnabled() || failed || width <= 0 || height <= 0 || renderWidth <= 0 || renderHeight <= 0) {
            return false;
        }
        if (backbufferFormat != VK10.VK_FORMAT_R8G8B8A8_UNORM
                && backbufferFormat != VK10.VK_FORMAT_R16G16B16A16_SFLOAT) {
            CausticaMod.LOGGER.warn("Vulkan frame generation does not support backbuffer format {}", backbufferFormat);
            return false;
        }
        RtContext current = RtContext.get();
        if (current == null) {
            return false;
        }
        try {
            if (!probed) {
                probeAvailabilityOnce();
            }
            if (!available) {
                return false;
            }
            if (ctx != null && ctx != current) {
                // The old VkDevice may already have been destroyed. Its children died with it, so only
                // forget Java-side handles here instead of issuing vkDestroy* against a stale device.
                forgetDeviceResources();
            }
            ctx = current;
            if (descriptorSetLayout == 0L) {
                createPipelineResources();
            }
            if (featureReadyFor(width, height, renderWidth, renderHeight, backbufferFormat)) {
                return true;
            }

            // Resize/reconfigure is rare and may otherwise destroy descriptors/images still referenced by
            // the preceding frame. Waiting here keeps the provider independent of Minecraft's frames-in-flight.
            ctx.waitIdle();
            destroyFeatureResources();

            int setCount = effectiveMultiFrameCount();
            createDescriptorSets(setCount);
            colorHistory = ctx.createStorageImage(width, height, backbufferFormat,
                    "framegen previous color " + width + "x" + height);
            depthHistory = ctx.createStorageImage(renderWidth, renderHeight, VK10.VK_FORMAT_R32_SFLOAT,
                    "framegen previous depth " + renderWidth + "x" + renderHeight);

            this.width = width;
            this.height = height;
            this.renderWidth = renderWidth;
            this.renderHeight = renderHeight;
            this.format = backbufferFormat;
            historyReady = false;
            ready = true;
            return true;
        } catch (Throwable t) {
            failed = true;
            available = false;
            ready = false;
            CausticaMod.LOGGER.error("Vulkan frame-generation feature creation failed", t);
            destroyFeatureResources();
            return false;
        }
    }

    @Override
    public synchronized boolean interpolate(long commandBuffer,
            long backbufferView, long backbufferImage, int backbufferFormat,
            long depthView, long depthImage, int depthFormat,
            long motionView, long motionImage, int motionFormat,
            long hudlessView, long hudlessImage, int hudlessFormat,
            long uiView, long uiImage, int uiFormat,
            long outputView, long outputImage, int outputFormat,
            int width, int height, int motionDepthWidth, int motionDepthHeight,
            int generatedFrameCount, int generatedFrameIndex, float motionScaleX, float motionScaleY,
            boolean depthInverted, boolean colorBuffersHdr, boolean cameraMotionIncluded, boolean reset,
            Matrix4fc clipToPreviousClip, Matrix4fc previousClipToClip) {
        if (!isReady() || commandBuffer == 0L
                || !featureReadyFor(width, height, motionDepthWidth, motionDepthHeight, backbufferFormat)
                || outputFormat != backbufferFormat
                || depthFormat != VK10.VK_FORMAT_R32_SFLOAT
                || motionFormat != VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                || generatedFrameCount != descriptorSets.length
                || generatedFrameIndex < 1 || generatedFrameIndex > generatedFrameCount
                || backbufferView == 0L || backbufferImage == 0L
                || depthView == 0L || depthImage == 0L
                || motionView == 0L || motionImage == 0L
                || outputView == 0L || outputImage == 0L) {
            return false;
        }

        if (reset) {
            historyReady = false;
        }
        boolean canUseHistory = historyReady;
        int slot = generatedFrameIndex - 1;
        try {
            updateDescriptorSet(slot, backbufferView, depthView, motionView, outputView);
            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer, ctx.vk());
            try (MemoryStack stack = MemoryStack.stackPush();
                    RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                            "vulkan framegen " + generatedFrameIndex + "/" + generatedFrameCount)) {
                makeInputsVisible(stack, cmd);

                long pipeline = backbufferFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                        ? hdrPipeline : sdrPipeline;
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                        stack.longs(descriptorSets[slot]), null);

                float interpolationPoint = (float) generatedFrameIndex / (generatedFrameCount + 1.0f);
                ByteBuffer push = stack.malloc(PUSH_BYTES);
                push.putInt(0, width);
                push.putInt(4, height);
                push.putInt(8, motionDepthWidth);
                push.putInt(12, motionDepthHeight);
                push.putFloat(16, interpolationPoint);
                push.putFloat(20, motionScaleX);
                push.putFloat(24, motionScaleY);
                push.putInt(28, canUseHistory ? 1 : 0);
                VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);

                makeOutputTransferReadable(stack, cmd, outputImage);
                if (generatedFrameIndex == generatedFrameCount) {
                    copyCurrentToHistory(stack, cmd, backbufferImage, depthImage);
                    // Host state changes only after the final generated index. Every index recorded for this
                    // real frame therefore consumes the same previous-frame history.
                    historyReady = true;
                }
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            available = false;
            ready = false;
            CausticaMod.LOGGER.error("Vulkan frame interpolation failed", t);
            return false;
        }
    }

    @Override
    public synchronized void destroy() {
        destroyResources(true);
        ctx = null;
        probed = false;
        available = false;
        failed = false;
    }

    private void createPipelineResources() {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
            for (int i = 0; i < BINDING_COUNT; i++) {
                int type = i <= 1
                        ? VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                        : VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                bindings.get(i).binding(i).descriptorType(type).descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p),
                    "vkCreateDescriptorSetLayout(framegen)");
            descriptorSetLayout = p.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(framegen)");
            pipelineLayout = p.get(0);

            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(0.0f).maxAnisotropy(1.0f);
            check(VK10.vkCreateSampler(vk, sci, null, p), "vkCreateSampler(framegen)");
            sampler = p.get(0);

            sdrPipeline = createPipeline(stack, SDR_SHADER);
            hdrPipeline = createPipeline(stack, HDR_SHADER);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, sdrPipeline, "framegen rgba8 pipeline");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, hdrPipeline, "framegen rgba16f pipeline");
        }
    }

    private long createPipeline(MemoryStack stack, String shaderName) {
        long module = loadModule(ctx.vk(), stack, shaderName);
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p),
                    "vkCreateComputePipelines(" + shaderName + ")");
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void createDescriptorSets(int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(count * 2);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(count * 4);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(count).pPoolSizes(sizes);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(framegen)");
            descriptorPool = p.get(0);

            LongBuffer layouts = stack.mallocLong(count);
            for (int i = 0; i < count; i++) {
                layouts.put(i, descriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(layouts);
            LongBuffer sets = stack.mallocLong(count);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, sets), "vkAllocateDescriptorSets(framegen)");
            descriptorSets = new long[count];
            for (int i = 0; i < count; i++) {
                descriptorSets[i] = sets.get(i);
            }
            boundViews = new long[count][4];
        }
    }

    private void updateDescriptorSet(int slot, long currentColorView, long currentDepthView,
            long motionView, long outputView) {
        long[] wanted = {currentColorView, currentDepthView, motionView, outputView};
        if (Arrays.equals(boundViews[slot], wanted)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(BINDING_COUNT, stack);
            infos.get(0).sampler(sampler).imageView(currentColorView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            infos.get(1).sampler(sampler).imageView(colorHistory.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            infos.get(2).imageView(currentDepthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            infos.get(3).imageView(depthHistory.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            infos.get(4).imageView(motionView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            infos.get(5).imageView(outputView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
            for (int i = 0; i < BINDING_COUNT; i++) {
                int type = i <= 1
                        ? VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                        : VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                writes.get(i).sType$Default().dstSet(descriptorSets[slot]).dstBinding(i)
                        .descriptorType(type).descriptorCount(1).pImageInfo(infos.slice(i, 1));
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            boundViews[slot] = wanted;
        }
    }

    private static void makeInputsVisible(MemoryStack stack, VkCommandBuffer cmd) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);
        VK10.vkCmdPipelineBarrier(cmd,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, barrier, null, null);
    }

    private static void makeOutputTransferReadable(MemoryStack stack, VkCommandBuffer cmd, long outputImage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(outputImage);
        barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
    }

    private void copyCurrentToHistory(MemoryStack stack, VkCommandBuffer cmd,
            long currentColorImage, long currentDepthImage) {
        VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(2, stack);
        historyWriteBarrier(toTransfer.get(0), colorHistory.image);
        historyWriteBarrier(toTransfer.get(1), depthHistory.image);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);

        VK10.vkCmdCopyImage(cmd, currentColorImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                colorHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
        VK10.vkCmdCopyImage(cmd, currentDepthImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                depthHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, renderWidth, renderHeight));

        VkImageMemoryBarrier.Buffer toShader = VkImageMemoryBarrier.calloc(2, stack);
        historyReadBarrier(toShader.get(0), colorHistory.image);
        historyReadBarrier(toShader.get(1), depthHistory.image);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toShader);
    }

    private static void historyWriteBarrier(VkImageMemoryBarrier barrier, long image) {
        barrier.sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
    }

    private static void historyReadBarrier(VkImageMemoryBarrier barrier, long image) {
        barrier.sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.get(0).extent().width(width).height(height).depth(1);
        return region;
    }


    private void forgetDeviceResources() {
        colorHistory = null;
        depthHistory = null;
        descriptorSetLayout = descriptorPool = pipelineLayout = sdrPipeline = hdrPipeline = sampler = 0L;
        descriptorSets = new long[0];
        boundViews = new long[0][];
        width = height = renderWidth = renderHeight = -1;
        format = Integer.MIN_VALUE;
        historyReady = false;
        ready = false;
    }

    private void destroyFeatureResources() {
        if (colorHistory != null) {
            colorHistory.destroy();
            colorHistory = null;
        }
        if (depthHistory != null) {
            depthHistory.destroy();
            depthHistory = null;
        }
        if (descriptorPool != 0L && ctx != null) {
            VK10.vkDestroyDescriptorPool(ctx.vk(), descriptorPool, null);
        }
        descriptorPool = 0L;
        descriptorSets = new long[0];
        boundViews = new long[0][];
        width = height = renderWidth = renderHeight = -1;
        format = Integer.MIN_VALUE;
        historyReady = false;
        ready = false;
    }

    private void destroyResources(boolean waitIdle) {
        if (ctx == null) {
            return;
        }
        if (waitIdle) {
            try {
                ctx.waitIdle();
            } catch (Throwable ignored) {
                // Device-loss teardown still needs to release host-side ownership state.
            }
        }
        destroyFeatureResources();
        VkDevice vk = ctx.vk();
        if (sdrPipeline != 0L) VK10.vkDestroyPipeline(vk, sdrPipeline, null);
        if (hdrPipeline != 0L) VK10.vkDestroyPipeline(vk, hdrPipeline, null);
        if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        if (sampler != 0L) VK10.vkDestroySampler(vk, sampler, null);
        if (descriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        descriptorSetLayout = pipelineLayout = sdrPipeline = hdrPipeline = sampler = 0L;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String shaderName) {
        byte[] bytes;
        try (InputStream in = VulkanMotionFrameGen.class.getResourceAsStream(SHADER_DIR + shaderName)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + shaderName);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + shaderName, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, p), "vkCreateShaderModule(" + shaderName + ")");
            return p.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
