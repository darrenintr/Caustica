package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
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
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Post-display destroy-stage crack overlay. Runs after tonemap so dig cracks never enter
 * path-traced albedo / NRD / FSR / auto-exposure.
 */
public final class RtBreakOverlayPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";
    // mat4(64) + vec2*2(16) + ivec4(16) + vec3+pad(16) = 112
    private static final int PUSH_BYTES = 112;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private long boundDisplayView;
    private long boundDepthView;
    private long boundCrackView;
    private long boundSampler;
    private boolean destroyed;

    private RtBreakOverlayPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtBreakOverlayPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(3, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds),
                    null, p), "vkCreateDescriptorSetLayout(break overlay)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "break overlay DSL");

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),
                    null, p), "vkCreateDescriptorPool(break overlay)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "break overlay pool");

            check(VK10.vkAllocateDescriptorSets(vk,
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(pool).pSetLayouts(stack.longs(dsl)),
                    p), "vkAllocateDescriptorSets(break overlay)");
            long set = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "break overlay set");

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pcr),
                    null, p), "vkCreatePipelineLayout(break overlay)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "break overlay layout");

            long module = loadModule(vk, stack, "break_overlay.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "break overlay module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, p),
                    "vkCreateComputePipelines(break overlay)");
            long pipeline = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "break overlay pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new RtBreakOverlayPipeline(ctx, dsl, pool, set, layout, pipeline);
        }
    }

    public void setImages(long displayView, long depthView, long crackView, long sampler) {
        if (boundDisplayView == displayView && boundDepthView == depthView
                && boundCrackView == crackView && boundSampler == sampler) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer displayInfo = VkDescriptorImageInfo.calloc(1, stack);
            displayInfo.get(0).imageView(displayView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer depthInfo = VkDescriptorImageInfo.calloc(1, stack);
            depthInfo.get(0).imageView(depthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer crackInfo = VkDescriptorImageInfo.calloc(1, stack);
            crackInfo.get(0).imageView(crackView).sampler(sampler).imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(displayInfo);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(depthInfo);
            writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(crackInfo);
            // Crack textures are usually GENERAL after MC upload; try GENERAL if SHADER_READ_ONLY fails visually.
            // Prefer GENERAL for consistency with the rest of Caustica's storage/sampled images.
            crackInfo.get(0).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundDisplayView = displayView;
        boundDepthView = depthView;
        boundCrackView = crackView;
        boundSampler = sampler;
    }

    public void dispatch(VkCommandBuffer cmd,
                         Matrix4fc invViewProj,
                         int renderW, int renderH, int displayW, int displayH,
                         int blockX, int blockY, int blockZ,
                         float camOffX, float camOffY, float camOffZ) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "break overlay")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);

            ByteBuffer push = stack.malloc(PUSH_BYTES);
            FloatBuffer fb = push.asFloatBuffer();
            invViewProj.get(0, fb);
            push.putFloat(64, (float) renderW);
            push.putFloat(68, (float) renderH);
            push.putFloat(72, (float) displayW);
            push.putFloat(76, (float) displayH);
            push.putInt(80, blockX);
            push.putInt(84, blockY);
            push.putInt(88, blockZ);
            push.putInt(92, 0);
            push.putFloat(96, camOffX);
            push.putFloat(100, camOffY);
            push.putFloat(104, camOffZ);
            push.putFloat(108, 0f);

            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (displayW + 7) / 8, (displayH + 7) / 8, 1);
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
        try (InputStream in = RtBreakOverlayPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
