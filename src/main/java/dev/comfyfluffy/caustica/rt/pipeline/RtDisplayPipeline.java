package dev.comfyfluffy.caustica.rt.pipeline;

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
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Compute pass that maps the display-res HDR RT image into an LDR image compatible with the main target. */
public final class RtDisplayPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Push constants: int hdrEnabled, float paperWhiteNits, float headroom, int specularOnlyMode. */
    private static final int PUSH_BYTES = 4 * Integer.BYTES;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private long boundOutputView;
    private long boundRtView;
    private long boundExposureView;
    private long boundHdrView;
    private long boundVanillaSdrView; // Phase 2 hybrid: caustica.toml mode = "specular-only"
    private long boundSpecularView;   // Phase 2 hybrid: reflection RT output
    private boolean destroyed;

    private RtDisplayPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtDisplayPipeline create(RtContext ctx, int inputFormat) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(6, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(rt display)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "display descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(6);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(rt display)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "display descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(rt display)");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "display descriptor set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(rt display)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "display pipeline layout");

            String shader = inputFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                    ? "display_rgba16f.comp.spv"
                    : "display.comp.spv";
            long module = loadModule(vk, stack, shader);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "display shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(rt display)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), "display compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new RtDisplayPipeline(ctx, dsl, pool, set, layout, pPipeline.get(0));
        }
    }

    public void setImages(long outputImageView, long rtImageView, long exposureImageView, long hdrImageView,
                       long vanillaSdrView, long specularView) {
        if (boundOutputView == outputImageView && boundRtView == rtImageView
                && boundExposureView == exposureImageView && boundHdrView == hdrImageView
                && boundVanillaSdrView == vanillaSdrView && boundSpecularView == specularView) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer outputInfo = VkDescriptorImageInfo.calloc(1, stack);
            outputInfo.get(0).imageView(outputImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer rtInfo = VkDescriptorImageInfo.calloc(1, stack);
            rtInfo.get(0).imageView(rtImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer exposureInfo = VkDescriptorImageInfo.calloc(1, stack);
            exposureInfo.get(0).imageView(exposureImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hdrInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrInfo.get(0).imageView(hdrImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            // Phase 2 hybrid: in full mode these stay VK_NULL_HANDLE — display.comp's
            // `if (specularOnlyMode)` branch never reads them. In SPECULAR_ONLY mode
            // vanillaSdrView is vanilla MC's mainTarget SDR color texture and specularView
            // is the reflection RT output (gReflection binding 11).
            VkDescriptorImageInfo.Buffer vanillaSdrInfo = VkDescriptorImageInfo.calloc(1, stack);
            vanillaSdrInfo.get(0).imageView(vanillaSdrView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer specularInfo = VkDescriptorImageInfo.calloc(1, stack);
            specularInfo.get(0).imageView(specularView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outputInfo);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(rtInfo);
            writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(exposureInfo);
            writes.get(3).sType$Default().dstSet(descriptorSet).dstBinding(3)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(hdrInfo);
            writes.get(4).sType$Default().dstSet(descriptorSet).dstBinding(4)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(vanillaSdrInfo);
            writes.get(5).sType$Default().dstSet(descriptorSet).dstBinding(5)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(specularInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundOutputView = outputImageView;
        boundRtView = rtImageView;
        boundExposureView = exposureImageView;
        boundHdrView = hdrImageView;
        boundVanillaSdrView = vanillaSdrView;
        boundSpecularView = specularView;
    }

    /**
     * Run the display mapping. The SDR AgX output is always written (binding 0). When {@code hdrEnabled}, the
     * PQ-encoded HDR image (binding 3) is also written using the paper-white/headroom mapping.
     */
    public void dispatch(VkCommandBuffer cmd, int width, int height, boolean hdrEnabled, float paperWhiteNits, float headroom,
                         boolean specularOnlyMode) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "display compute")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, hdrEnabled ? 1 : 0);
            push.putFloat(4, paperWhiteNits);
            push.putFloat(8, headroom);
            push.putInt(12, specularOnlyMode ? 1 : 0);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDisplayPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
