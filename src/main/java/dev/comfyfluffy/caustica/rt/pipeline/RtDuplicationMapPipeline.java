package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
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
 * ReSTIR PT Enhanced §5 duplication map compute pass (Lin, Kettunen, Wyman, I3D 2026).
 *
 * Reads {@code gV2Seed} (R32 UI, per-pixel V2 identifier) and writes {@code gDupMapCurr}
 * (R8 UNORM, render-res). Each output pixel is the local V2-match ratio in a 17×17 window.
 * Consumed by the temporal merge to cap history reuse: a pixel in a V2-homogeneous neighbourhood
 * reuses the previous frame's sample aggressively (low rejection), a pixel in a heterogeneous
 * neighbourhood reuses tightly (high rejection).
 *
 * Bindings:
 *   0  gV2Seed      (r32ui) ro
 *   1  gDupMapCurr  (r8)    writeonly
 *
 * Push:  ivec2 renderSize, uint frameSeed (so the per-pixel PCG hash reshuffles every frame),
 *        uint pad → 16 bytes.
 */
public final class RtDuplicationMapPipeline {

    private static final String MAP_SHADER = "/caustica/rt/duplication_map.comp.spv";
    private static final String SEED_SHADER = "/caustica/rt/v2_seed_init.comp.spv";
    private static final int PUSH_BYTES = 16;

    private final RtContext ctx;
    private final long mapDsl;
    private final long mapPool;
    private final long mapSet;
    private final long mapLayout;
    private final long mapPipeline;

    private final long seedDsl;
    private final long seedPool;
    private final long seedSet;
    private final long seedLayout;
    private final long seedPipeline;

    private long boundMapV2SeedView;
    private long boundMapDupMapView;
    private long boundSeedView;

    private boolean destroyed;

    private RtDuplicationMapPipeline(RtContext ctx,
                                     long mapDsl, long mapPool, long mapSet, long mapLayout, long mapPipeline,
                                     long seedDsl, long seedPool, long seedSet, long seedLayout, long seedPipeline) {
        this.ctx = ctx;
        this.mapDsl = mapDsl;
        this.mapPool = mapPool;
        this.mapSet = mapSet;
        this.mapLayout = mapLayout;
        this.mapPipeline = mapPipeline;
        this.seedDsl = seedDsl;
        this.seedPool = seedPool;
        this.seedSet = seedSet;
        this.seedLayout = seedLayout;
        this.seedPipeline = seedPipeline;
    }

    public static RtDuplicationMapPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            // === Duplication map pass (reads prev seed, writes curr map). 2 bindings. ===
            VkDescriptorSetLayoutBinding.Buffer mapBinds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            mapBinds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            mapBinds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(mapBinds),
                    null, p), "vkCreateDescriptorSetLayout(duplication_map)");
            long mapDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, mapDsl,
                    "duplication-map DSL");

            VkDescriptorPoolSize.Buffer mapSizes = VkDescriptorPoolSize.calloc(1, stack);
            mapSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(mapSizes),
                    null, p), "vkCreateDescriptorPool(duplication_map)");
            long mapPool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, mapPool,
                    "duplication-map pool");

            check(VK10.vkAllocateDescriptorSets(vk,
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(mapPool).pSetLayouts(stack.longs(mapDsl)),
                    p), "vkAllocateDescriptorSets(duplication_map)");
            long mapSet = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, mapSet,
                    "duplication-map set");

            VkPushConstantRange.Buffer mapPcr = VkPushConstantRange.calloc(1, stack);
            mapPcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(mapDsl)).pPushConstantRanges(mapPcr),
                    null, p), "vkCreatePipelineLayout(duplication_map)");
            long mapLayout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, mapLayout,
                    "duplication-map layout");

            long mapModule = loadModule(vk, stack, MAP_SHADER);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, mapModule,
                    "duplication-map module");
            VkPipelineShaderStageCreateInfo mapStage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(mapModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer mapCpc = VkComputePipelineCreateInfo.calloc(1, stack);
            mapCpc.get(0).sType$Default().stage(mapStage).layout(mapLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, mapCpc, null, p),
                    "vkCreateComputePipelines(duplication_map)");
            long mapPipeline = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, mapPipeline,
                    "duplication-map pipeline");
            VK10.vkDestroyShaderModule(vk, mapModule, null);

            // === V2 seed init pass (writes the per-pixel V2 seed for the next frame). 1 binding. ===
            VkDescriptorSetLayoutBinding.Buffer seedBinds = VkDescriptorSetLayoutBinding.calloc(1, stack);
            seedBinds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(seedBinds),
                    null, p), "vkCreateDescriptorSetLayout(v2_seed_init)");
            long seedDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, seedDsl,
                    "v2-seed DSL");

            VkDescriptorPoolSize.Buffer seedSizes = VkDescriptorPoolSize.calloc(1, stack);
            seedSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(seedSizes),
                    null, p), "vkCreateDescriptorPool(v2_seed_init)");
            long seedPool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, seedPool,
                    "v2-seed pool");

            check(VK10.vkAllocateDescriptorSets(vk,
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(seedPool).pSetLayouts(stack.longs(seedDsl)),
                    p), "vkAllocateDescriptorSets(v2_seed_init)");
            long seedSet = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, seedSet,
                    "v2-seed set");

            VkPushConstantRange.Buffer seedPcr = VkPushConstantRange.calloc(1, stack);
            seedPcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(seedDsl)).pPushConstantRanges(seedPcr),
                    null, p), "vkCreatePipelineLayout(v2_seed_init)");
            long seedLayout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, seedLayout,
                    "v2-seed layout");

            long seedModule = loadModule(vk, stack, SEED_SHADER);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, seedModule,
                    "v2-seed module");
            VkPipelineShaderStageCreateInfo seedStage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(seedModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer seedCpc = VkComputePipelineCreateInfo.calloc(1, stack);
            seedCpc.get(0).sType$Default().stage(seedStage).layout(seedLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, seedCpc, null, p),
                    "vkCreateComputePipelines(v2_seed_init)");
            long seedPipeline = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, seedPipeline,
                    "v2-seed pipeline");
            VK10.vkDestroyShaderModule(vk, seedModule, null);

            return new RtDuplicationMapPipeline(ctx,
                    mapDsl, mapPool, mapSet, mapLayout, mapPipeline,
                    seedDsl, seedPool, seedSet, seedLayout, seedPipeline);
        }
    }

    /**
     * Bind the duplication-map pipeline's two images. Idempotent — fast-path if the views haven't
     * changed.
     */
    public void bindMapImages(long v2SeedPrevView, long dupMapCurrView) {
        if (destroyed) {
            return;
        }
        if (boundMapV2SeedView == v2SeedPrevView && boundMapDupMapView == dupMapCurrView) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(2, stack);
            imgs.get(0).imageView(v2SeedPrevView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            imgs.get(1).imageView(dupMapCurrView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            for (int i = 0; i < 2; ++i) {
                writes.get(i).sType$Default().dstSet(mapSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(imgs);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundMapV2SeedView = v2SeedPrevView;
        boundMapDupMapView = dupMapCurrView;
    }

    /**
     * Bind the seed-init pipeline's single output image. Idempotent.
     */
    public void bindSeedImage(long v2SeedCurrView) {
        if (destroyed) {
            return;
        }
        if (boundSeedView == v2SeedCurrView) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(1, stack);
            imgs.get(0).imageView(v2SeedCurrView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType$Default().dstSet(seedSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(imgs);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundSeedView = v2SeedCurrView;
    }

    public void dispatchSeed(VkCommandBuffer cmd, int renderW, int renderH, int frameSeed) {
        if (destroyed) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "v2-seed init")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, seedPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    seedLayout, 0, stack.longs(seedSet), null);

            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0,  renderW);
            push.putInt(4,  renderH);
            push.putInt(8,  frameSeed);
            push.putInt(12, 0);
            VK10.vkCmdPushConstants(cmd, seedLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

            int groupsX = (renderW + 7) / 8;
            int groupsY = (renderH + 7) / 8;
            VK10.vkCmdDispatch(cmd, groupsX, groupsY, 1);
        }
    }

    public void dispatchMap(VkCommandBuffer cmd, int renderW, int renderH, int frameSeed) {
        if (destroyed) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "duplication-map")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, mapPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    mapLayout, 0, stack.longs(mapSet), null);

            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0,  renderW);
            push.putInt(4,  renderH);
            push.putInt(8,  frameSeed);
            push.putInt(12, 0);
            VK10.vkCmdPushConstants(cmd, mapLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

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
        VK10.vkDestroyPipeline(vk, mapPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, mapLayout, null);
        VK10.vkDestroyDescriptorPool(vk, mapPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, mapDsl, null);
        VK10.vkDestroyPipeline(vk, seedPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, seedLayout, null);
        VK10.vkDestroyDescriptorPool(vk, seedPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, seedDsl, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String resourcePath) {
        byte[] bytes;
        try (InputStream in = RtDuplicationMapPipeline.class.getResourceAsStream(resourcePath)) {
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
