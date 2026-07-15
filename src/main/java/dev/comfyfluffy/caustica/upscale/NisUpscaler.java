package dev.comfyfluffy.caustica.upscale;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * NVIDIA Image Scaling (NIS) upscaler - pure compute shader implementation.
 * Open source, MIT licensed, works on all GPUs.
 *
 * <p>NIS is a 6-tap Lanczos-inspired edge-adaptive upscaling filter with sharpening.
 * Quality is between FSR1 and FSR2, with excellent performance (~1ms at 1080p→4K).
 *
 * @see <a href="https://github.com/NVIDIAGameWorks/NVIDIAImageScaling">NIS GitHub</a>
 */
public final class NisUpscaler implements Upscaler {

    private static final String SHADER_PATH = "/caustica/rt/nis_upscale.comp.spv";

    private final VulkanDevice vkDevice;
    private boolean ready;
    private int renderWidth;
    private int renderHeight;
    private int displayWidth;
    private int displayHeight;
    private float sharpness = 0.5f;  // 0.0 = no sharpen, 1.0 = max sharpen

    // Vulkan resources
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private long descriptorSet;
    private long sampler;

    private NisUpscaler(VulkanDevice device) {
        this.vkDevice = device;
    }

    public static NisUpscaler tryCreate() {
        try {
            if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
                return null;
            }
            NisUpscaler upscaler = new NisUpscaler(device);
            CausticaMod.LOGGER.info("NIS upscaler created (pure shader, cross-vendor)");
            return upscaler;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NIS upscaler creation failed", t);
            return null;
        }
    }

    @Override
    public Mode mode() {
        return Mode.NIS;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        // NIS can handle any ratio, but typical is 0.67x (quality mode)
        int renderWidth = (int) (displayWidth * 0.67f);
        int renderHeight = (int) (displayHeight * 0.67f);
        return new int[] { renderWidth, renderHeight };
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                 int quality, int featureFlags) {
        if (this.renderWidth == renderWidth && this.renderHeight == renderHeight
                && this.displayWidth == displayWidth && this.displayHeight == displayHeight
                && ready) {
            return true;
        }

        if (!ready) {
            try {
                createPipeline();
                ready = true;
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("NIS pipeline creation failed", t);
                ready = false;
                return false;
            }
        }

        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;

        CausticaMod.LOGGER.info("NIS upscaler sized: {}×{} → {}×{} (sharpness={})",
                renderWidth, renderHeight, displayWidth, displayHeight, sharpness);
        return true;
    }

    @Override
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage specularHitDistance, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!ready) {
            return false;
        }

        VkCommandBuffer vkCmd = new VkCommandBuffer(cmd, vkDevice.vkDevice());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Update descriptor set
            updateDescriptorSet(stack, color, out);

            // Bind pipeline
            VK10.vkCmdBindPipeline(vkCmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

            // Bind descriptor set
            LongBuffer pDescriptorSets = stack.mallocLong(1).put(0, descriptorSet);
            VK10.vkCmdBindDescriptorSets(vkCmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, pDescriptorSets, null);

            // Push constants: inputW, inputH, outputW, outputH, sharpness
            ByteBuffer pushData = stack.malloc(20);
            pushData.putInt(0, renderWidth);
            pushData.putInt(4, renderHeight);
            pushData.putInt(8, displayWidth);
            pushData.putInt(12, displayHeight);
            pushData.putFloat(16, sharpness);

            VK10.vkCmdPushConstants(vkCmd, pipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);

            // Dispatch (16×16 workgroup)
            int groupsX = (displayWidth + 15) / 16;
            int groupsY = (displayHeight + 15) / 16;
            VK10.vkCmdDispatch(vkCmd, groupsX, groupsY, 1);

            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("NIS evaluate failed", t);
            return false;
        }
    }

    @Override
    public void destroy() {
        if (!ready) {
            return;
        }

        VkDevice device = vkDevice.vkDevice();
        if (pipeline != 0L) VK10.vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (descriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        if (descriptorPool != 0L) VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
        if (sampler != 0L) VK10.vkDestroySampler(device, sampler, null);

        ready = false;
        CausticaMod.LOGGER.info("NIS upscaler destroyed");
    }

    private void createPipeline() throws IOException {
        VkDevice device = vkDevice.vkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create sampler
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR)
                    .minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            vkCheck(VK10.vkCreateSampler(device, samplerInfo, null, pSampler), "vkCreateSampler");
            sampler = pSampler.get(0);

            // Descriptor set layout: binding 0 = sampler, binding 1 = storage image
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            vkCheck(VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout), "vkCreateDescriptorSetLayout");
            descriptorSetLayout = pLayout.get(0);

            // Pipeline layout with push constants
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(20);  // 4 uints + 1 float

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);

            vkCheck(VK10.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pLayout), "vkCreatePipelineLayout");
            pipelineLayout = pLayout.get(0);

            // Load shader
            ByteBuffer spirv = loadShader(SHADER_PATH);
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);

            LongBuffer pModule = stack.mallocLong(1);
            vkCheck(VK10.vkCreateShaderModule(device, moduleInfo, null, pModule), "vkCreateShaderModule");
            long shaderModule = pModule.get(0);

            // Compute pipeline
            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .stage(stageInfo)
                    .layout(pipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            vkCheck(VK10.vkCreateComputePipelines(device, 0L, pipelineInfo, null, pPipeline), "vkCreateComputePipelines");
            pipeline = pPipeline.get(0);

            VK10.vkDestroyShaderModule(device, shaderModule, null);

            // Create descriptor pool
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(1)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            vkCheck(VK10.vkCreateDescriptorPool(device, poolInfo, null, pPool), "vkCreateDescriptorPool");
            descriptorPool = pPool.get(0);

            // Allocate descriptor set
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            vkCheck(VK10.vkAllocateDescriptorSets(device, allocInfo, pSet), "vkAllocateDescriptorSets");
            descriptorSet = pSet.get(0);
        }
    }

    private void updateDescriptorSet(MemoryStack stack, RtImage input, RtImage output) {
        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(2, stack);
        imageInfo.get(0)
                .sampler(sampler)
                .imageView(input.view)
                .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        imageInfo.get(1)
                .imageView(output.view)
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0)
                .sType$Default()
                .dstSet(descriptorSet)
                .dstBinding(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(imageInfo.slice(0, 1));
        writes.get(1)
                .sType$Default()
                .dstSet(descriptorSet)
                .dstBinding(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(imageInfo.slice(1, 1));

        VK10.vkUpdateDescriptorSets(vkDevice.vkDevice(), writes, null);
    }

    private ByteBuffer loadShader(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Shader not found: " + path);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        }
    }

    private static void vkCheck(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new RuntimeException(operation + " failed: " + result);
        }
    }
}
