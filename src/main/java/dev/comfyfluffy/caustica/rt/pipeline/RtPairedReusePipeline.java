package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.light.PairedReuseTextures;
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

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * ReSTIR PT Enhanced §3 paired spatial-reuse compute pass.
 *
 * Reads gReservoirCurr, picks a per-pixel pair partner from one of three pre-baked pair tables,
 * MIS-merges, and writes back. The pass is a strict superset of the inline 9-tap spatial merge
 * in world.rgen (Lin, Kettunen, Wyman §3) — it costs 1 descriptor set + 1 dispatch per frame
 * and saves the inline neighbour loop in the raygen shader.
 *
 * Bindings:
 *   0  gReservoirCurr   (rgba32ui) rw
 *   1  gPairedReuseTex0  (rg16ui)  ro
 *   2  gPairedReuseTex1  (rg16ui)  ro
 *   3  gPairedReuseTex2  (rg16ui)  ro
 *   4  gPairedShuffle    (r32ui)   ro
 *
 * Push:  ivec2 renderSize, int maxM, uint shuffleIndex = 12 B (rounds to 16 in practice).
 */
public final class RtPairedReusePipeline {

    private static final String SPATIAL_SHADER = "/caustica/rt/spatial_reuse_paired.comp.spv";
    private static final String GENERATOR_SHADER = "/caustica/rt/paired_reuse_generator.comp.spv";
    /** 2×ivec2 + int + uint — pad to 16. */
    private static final int PUSH_BYTES = 16;

    private final RtContext ctx;
    private final long spatialDsl;
    private final long spatialPool;
    private final long spatialSet;
    private final long spatialLayout;
    private final long spatialPipeline;

    private final long generatorDsl;
    private final long generatorPool;
    private final long generatorSet;
    private final long generatorLayout;
    private final long generatorPipeline;

    private long boundReservoirView;
    private long boundTex0View;
    private long boundTex1View;
    private long boundTex2View;
    private long boundShuffleView;

    private boolean destroyed;

    private RtPairedReusePipeline(RtContext ctx,
                                  long spatialDsl, long spatialPool, long spatialSet,
                                  long spatialLayout, long spatialPipeline,
                                  long generatorDsl, long generatorPool, long generatorSet,
                                  long generatorLayout, long generatorPipeline) {
        this.ctx = ctx;
        this.spatialDsl = spatialDsl;
        this.spatialPool = spatialPool;
        this.spatialSet = spatialSet;
        this.spatialLayout = spatialLayout;
        this.spatialPipeline = spatialPipeline;
        this.generatorDsl = generatorDsl;
        this.generatorPool = generatorPool;
        this.generatorSet = generatorSet;
        this.generatorLayout = generatorLayout;
        this.generatorPipeline = generatorPipeline;
    }

    public static RtPairedReusePipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // === Spatial pass (read pair tables, mutate reservoir). 5 bindings. ===
            VkDescriptorSetLayoutBinding.Buffer spatialBinds = VkDescriptorSetLayoutBinding.calloc(5, stack);
            spatialBinds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            spatialBinds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            spatialBinds.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            spatialBinds.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            spatialBinds.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(spatialBinds),
                    null, p), "vkCreateDescriptorSetLayout(spatial_reuse_paired)");
            long spatialDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, spatialDsl,
                    "paired-reuse spatial DSL");

            VkDescriptorPoolSize.Buffer spatialSizes = VkDescriptorPoolSize.calloc(1, stack);
            spatialSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(5);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(spatialSizes),
                    null, p), "vkCreateDescriptorPool(spatial_reuse_paired)");
            long spatialPool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, spatialPool,
                    "paired-reuse spatial pool");

            check(VK10.vkAllocateDescriptorSets(vk,
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(spatialPool).pSetLayouts(stack.longs(spatialDsl)),
                    p), "vkAllocateDescriptorSets(spatial_reuse_paired)");
            long spatialSet = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, spatialSet,
                    "paired-reuse spatial set");

            VkPushConstantRange.Buffer spatialPcr = VkPushConstantRange.calloc(1, stack);
            spatialPcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(spatialDsl)).pPushConstantRanges(spatialPcr),
                    null, p), "vkCreatePipelineLayout(spatial_reuse_paired)");
            long spatialLayout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, spatialLayout,
                    "paired-reuse spatial layout");

            long spatialModule = loadModule(vk, stack, SPATIAL_SHADER);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, spatialModule,
                    "paired-reuse spatial module");
            VkPipelineShaderStageCreateInfo spatialStage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(spatialModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer spatialCpc = VkComputePipelineCreateInfo.calloc(1, stack);
            spatialCpc.get(0).sType$Default().stage(spatialStage).layout(spatialLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, spatialCpc, null, p),
                    "vkCreateComputePipelines(spatial_reuse_paired)");
            long spatialPipeline = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, spatialPipeline,
                    "paired-reuse spatial pipeline");
            VK10.vkDestroyShaderModule(vk, spatialModule, null);

            // === Generator pass (writes the 4 paired-reuse tables). 4 writable image bindings. ===
            VkDescriptorSetLayoutBinding.Buffer genBinds = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int i = 0; i < 4; ++i) {
                genBinds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(genBinds),
                    null, p), "vkCreateDescriptorSetLayout(paired_reuse_generator)");
            long generatorDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, generatorDsl,
                    "paired-reuse generator DSL");

            VkDescriptorPoolSize.Buffer genSizes = VkDescriptorPoolSize.calloc(1, stack);
            genSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(genSizes),
                    null, p), "vkCreateDescriptorPool(paired_reuse_generator)");
            long generatorPool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, generatorPool,
                    "paired-reuse generator pool");

            check(VK10.vkAllocateDescriptorSets(vk,
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(generatorPool).pSetLayouts(stack.longs(generatorDsl)),
                    p), "vkAllocateDescriptorSets(paired_reuse_generator)");
            long generatorSet = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, generatorSet,
                    "paired-reuse generator set");

            VkPushConstantRange.Buffer genPcr = VkPushConstantRange.calloc(1, stack);
            genPcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(generatorDsl)).pPushConstantRanges(genPcr),
                    null, p), "vkCreatePipelineLayout(paired_reuse_generator)");
            long generatorLayout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, generatorLayout,
                    "paired-reuse generator layout");

            long genModule = loadModule(vk, stack, GENERATOR_SHADER);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, genModule,
                    "paired-reuse generator module");
            VkPipelineShaderStageCreateInfo genStage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(genModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer genCpc = VkComputePipelineCreateInfo.calloc(1, stack);
            genCpc.get(0).sType$Default().stage(genStage).layout(generatorLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, genCpc, null, p),
                    "vkCreateComputePipelines(paired_reuse_generator)");
            long generatorPipeline = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, generatorPipeline,
                    "paired-reuse generator pipeline");
            VK10.vkDestroyShaderModule(vk, genModule, null);

            return new RtPairedReusePipeline(ctx,
                    spatialDsl, spatialPool, spatialSet, spatialLayout, spatialPipeline,
                    generatorDsl, generatorPool, generatorSet, generatorLayout, generatorPipeline);
        }
    }

    /**
     * Bind the four paired-reuse images (3 pair tables + shuffle) to the generator pipeline.
     * Idempotent — fast-path if the views haven't changed.
     */
    public void bindGeneratorImages(PairedReuseTextures tex) {
        if (destroyed) {
            return;
        }
        long v0 = tex.tex0().view;
        long v1 = tex.tex1().view;
        long v2 = tex.tex2().view;
        long vS = tex.shuffle().view;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(4, stack);
            imgs.get(0).imageView(v0).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(1).imageView(v1).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(2).imageView(v2).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(3).imageView(vS).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            for (int i = 0; i < 4; ++i) {
                writes.get(i).sType$Default().dstSet(generatorSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(imgs);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    /**
     * Bind the reservoir + 4 pair-table views to the spatial pipeline. Called each frame the
     * reservoir swaps (DI only — GI will need a separate set once the pass is generalised).
     */
    public void bindSpatialImages(long reservoirView, PairedReuseTextures tex) {
        if (destroyed) {
            return;
        }
        long v0 = tex.tex0().view;
        long v1 = tex.tex1().view;
        long v2 = tex.tex2().view;
        long vS = tex.shuffle().view;
        if (boundReservoirView == reservoirView
                && boundTex0View == v0
                && boundTex1View == v1
                && boundTex2View == v2
                && boundShuffleView == vS) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(5, stack);
            imgs.get(0).imageView(reservoirView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(1).imageView(v0).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(2).imageView(v1).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(3).imageView(v2).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(4).imageView(vS).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
            for (int i = 0; i < 5; ++i) {
                writes.get(i).sType$Default().dstSet(spatialSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(imgs);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundReservoirView = reservoirView;
        boundTex0View = v0;
        boundTex1View = v1;
        boundTex2View = v2;
        boundShuffleView = vS;
    }

    /**
     * Run the GPU-side pair-table generator once. Dispatched once per size change (idempotent
     * via a local flag in the caller; this method just dispatches).
     */
    public void dispatchGenerator(VkCommandBuffer cmd, int seed) {
        if (destroyed) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "paired-reuse generator")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, generatorPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    generatorLayout, 0, stack.longs(generatorSet), null);

            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0,  seed);
            push.putInt(4,  1); // mirrorTex1: flip dx for the tex1 channel
            push.putInt(8,  PairedReuseTextures.TABLE_SIZE);
            push.putInt(12, PairedReuseTextures.PAIRS);
            VK10.vkCmdPushConstants(cmd, generatorLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

            int groups = (PairedReuseTextures.TABLE_SIZE + 15) / 16;
            VK10.vkCmdDispatch(cmd, groups, groups, 1);
        }
    }

    /**
     * Run the per-frame spatial pass. {@code maxM} caps M on merged reservoirs — typically
     * {@code caustica.rt.gi.maxMSpatial} (default 5).
     */
    public void dispatchSpatial(VkCommandBuffer cmd, int renderW, int renderH, int maxM, int shuffleIndex) {
        if (destroyed) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "paired-reuse spatial")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, spatialPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    spatialLayout, 0, stack.longs(spatialSet), null);

            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0,  renderW);
            push.putInt(4,  renderH);
            push.putInt(8,  maxM);
            push.putInt(12, shuffleIndex);
            VK10.vkCmdPushConstants(cmd, spatialLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

            int groupsX = (renderW + 7) / 8;
            int groupsY = (renderH + 7) / 8;
            VK10.vkCmdDispatch(cmd, groupsX, groupsY, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, spatialPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, spatialLayout, null);
        VK10.vkDestroyDescriptorPool(vk, spatialPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, spatialDsl, null);
        VK10.vkDestroyPipeline(vk, generatorPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, generatorLayout, null);
        VK10.vkDestroyDescriptorPool(vk, generatorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, generatorDsl, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String resourcePath) {
        byte[] bytes;
        try (InputStream in = RtPairedReusePipeline.class.getResourceAsStream(resourcePath)) {
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
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule),
                    "vkCreateShaderModule(" + resourcePath + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
