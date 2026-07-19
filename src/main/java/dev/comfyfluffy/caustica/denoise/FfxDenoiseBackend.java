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
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkClearColorValue;
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

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Whole-radiance temporal + spatial denoiser for AMD (and forced FFX mode).
 *
 * <p>Pipeline per frame:
 * <ol>
 *   <li>{@code ffx_reproject} — MV reproject of previous denoised frame + disocclusion variance</li>
 *   <li>{@code ffx_resolve_temporal} — AABB-clamped temporal mix (anti-ghost) + firefly squash</li>
 *   <li>{@code ffx_atrous} × 5 — SVGF-style dilated edge-stopping filter
 *       (step 1,2,4,8,16) that kills residual SPP-1 noise on flat Minecraft surfaces</li>
 * </ol>
 *
 * <p>Owns descriptor pools, pipelines, sampler, history ring, and intermediate
 * storage images. Algorithm lineage: AMD GPUOpen FFX denoiser samples + classic
 * SVGF (Schied et al.), re-implemented in plain GLSL.
 */
public final class FfxDenoiseBackend implements CausticaDenoiseBackend {

    private static final String SHADER_DIR = "/caustica/rt/";
    private static final int HISTORY_RING = 3;
    /**
     * Temporal reproject is off until MV path is proven. Spatial à-trous is also off for now
     * ({@link #SPATIAL_PASSTHROUGH}): the multi-pass atrous path produced pure black frames on
     * RADV even with tight sigmas (2026-07-14). Dispatch does a reliable {@code vkCmdCopyImage}
     * of the noisy RT color so the user at least sees the path-traced image while denoise=ON.
     */
    private static final boolean TEMPORAL_ENABLED = false;
    private static final boolean SPATIAL_PASSTHROUGH = true;
    private static final int ATROUS_PASSES = 5;
    private static final int[] ATROUS_STEPS = {1, 2, 4, 8, 1};

    private boolean ready;
    private int width;
    private int height;
    private long frameCounter;
    private boolean historyCleared;

    private long reprojectDsl;
    private long reprojectPool;
    private long reprojectSet;
    private long reprojectLayout;
    private long reprojectPipeline;

    private long resolveDsl;
    private long resolvePool;
    private long resolveSet;
    private long resolveLayout;
    private long resolvePipeline;

    private long atrousDsl;
    private long atrousPool;
    private long atrousSet;
    private long atrousLayout;
    private long atrousPipeline;

    private long sampler;
    private RtImage[] historyRadiance;
    private RtImage reprojectColorBuf;
    private RtImage reprojectVarianceBuf;
    private RtImage resolveDenoisedBuf;

    @Override
    public String name() {
        return "ffx";
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
        boolean needRealloc = (historyRadiance == null) || (historyRadiance[0] == null) || (this.width != width) || (this.height != height);
        if (!needRealloc) {
            return;
        }
        if (historyRadiance == null) {
            VkDevice vk = ctx.vk();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                sampler = createBilinearClampSampler(vk, stack);
                createReprojectPipeline(ctx);
                createResolvePipeline(ctx);
                createAtrousPipeline(ctx);
            }
            historyRadiance = new RtImage[HISTORY_RING];
        }
        for (int i = 0; i < HISTORY_RING; i++) {
            if (historyRadiance[i] != null) {
                historyRadiance[i].destroy();
                historyRadiance[i] = null;
            }
            // History packs device depth into .a for the next-frame disocclusion test — must stay RGBA16F.
            historyRadiance[i] = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "ffx history " + i);
        }
        if (reprojectColorBuf != null) {
            reprojectColorBuf.destroy();
        }
        if (reprojectVarianceBuf != null) {
            reprojectVarianceBuf.destroy();
        }
        if (resolveDenoisedBuf != null) {
            resolveDenoisedBuf.destroy();
        }
        // reprojectColorBuf packs depth into .a (rgba16f). resolveDenoisedBuf is pure RGB beauty.
        reprojectColorBuf = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx reproject color");
        reprojectVarianceBuf = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT, "ffx reproject variance");
        resolveDenoisedBuf = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "ffx resolve denoised");
        this.width = width;
        this.height = height;
        frameCounter = 0;
        historyCleared = false;
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready) {
            System.err.println("[Caustica FFX] dispatch() early-return: backend not ready (no init or ensureSized)");
            return false;
        }
        if (resolveDenoisedBuf == null) {
            System.err.println("[Caustica FFX] dispatch() early-return: resolveDenoisedBuf is null after ensureSized");
            return false;
        }
        if (TEMPORAL_ENABLED && historyRadiance[0] == null) {
            System.err.println("[Caustica FFX] dispatch() early-return: historyRadiance[0] is null after ensureSized (image allocation may have failed)");
            return false;
        }
        RtContext ctx = RtContext.get();

        // Emergency pass-through: copy noisy RT → outColor with vkCmdCopyImage (not blit).
        // Restores a visible image while the FFX filter path is being fixed.
        if (SPATIAL_PASSTHROUGH && !TEMPORAL_ENABLED) {
            copyStorageImage(stack, cmd, inColor, outColor);
            frameCounter++;
            return false;
        }

        if (TEMPORAL_ENABLED) {
            if (!historyCleared) {
                clearHistoryToZero(stack, cmd);
                barrierTransferToShader(stack, cmd,
                        historyRadiance[0].image, historyRadiance[1].image, historyRadiance[2].image,
                        reprojectColorBuf.image, reprojectVarianceBuf.image, resolveDenoisedBuf.image);
                historyCleared = true;
            }

            int readSlot = (int) ((frameCounter + HISTORY_RING - 1) % HISTORY_RING);
            RtImage historyRead = historyRadiance[readSlot];
            barrierTransferToShader(stack, cmd, historyRead.image);
            long historyView = historyRead.view;

            bindReproject(ctx, inColor, inNormal, inDepth, inMotion, reprojectColorBuf, reprojectVarianceBuf, historyView);
            dispatchReproject(stack, cmd, ctx, mvScaleX, mvScaleY);

            barrierCompute(stack, cmd,
                    reprojectColorBuf.image, reprojectVarianceBuf.image, inColor.image, inNormal.image);

            bindResolve(ctx, inColor, inNormal, reprojectColorBuf, reprojectVarianceBuf, inColor, resolveDenoisedBuf);
            dispatchResolve(stack, cmd, ctx);

            barrierCompute(stack, cmd,
                    resolveDenoisedBuf.image, inColor.image, inNormal.image, inDepth.image);
        }

        // Multi-pass dilated à-trous. Spatial-only: pass 0 reads noisy inColor; temporal
        // path (when enabled) seeds resolveDenoisedBuf first. Ping-pong ends on outColor
        // because ATROUS_PASSES is odd.
        for (int pass = 0; pass < ATROUS_PASSES; pass++) {
            int step = ATROUS_STEPS[pass];
            RtImage atrousSrc;
            RtImage atrousDst;
            if ((pass & 1) == 0) {
                atrousSrc = (pass == 0)
                        ? (TEMPORAL_ENABLED ? resolveDenoisedBuf : inColor)
                        : resolveDenoisedBuf;
                atrousDst = outColor;
            } else {
                atrousSrc = outColor;
                atrousDst = resolveDenoisedBuf;
            }
            bindAtrous(ctx, inColor, inNormal, inDepth, atrousSrc, atrousDst);
            dispatchAtrous(stack, cmd, ctx, step);
            barrierCompute(stack, cmd, atrousDst.image);
        }
        if ((ATROUS_PASSES & 1) == 0) {
            bindAtrous(ctx, inColor, inNormal, inDepth, resolveDenoisedBuf, outColor);
            dispatchAtrous(stack, cmd, ctx, 1);
            barrierCompute(stack, cmd, outColor.image);
        }

        if (TEMPORAL_ENABLED) {
            barrierComputeToTransfer(stack, cmd, outColor.image);
            copyFinalToHistory(stack, cmd, ctx, outColor);
            int writeSlot = (int) (frameCounter % HISTORY_RING);
            barrierTransferToShader(stack, cmd, historyRadiance[writeSlot].image);
        }
        frameCounter++;
        return true;
    }

    /** Same-format same-size storage image copy (GENERAL layout). Avoids rgba16f blit. */
    private void copyStorageImage(MemoryStack stack, VkCommandBuffer cmd, RtImage src, RtImage dst) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(RtContext.get(), cmd, "ffx passthrough copy")) {
            // src may have been written by raygen OR compute — wait on all shader/RT stages,
            // not just COMPUTE (barrierComputeToTransfer would miss raygen writes).
            org.lwjgl.vulkan.VkImageMemoryBarrier2.Buffer pre = org.lwjgl.vulkan.VkImageMemoryBarrier2.calloc(1, stack);
            pre.get(0).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(src.image)
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            org.lwjgl.vulkan.VkDependencyInfo preDep = org.lwjgl.vulkan.VkDependencyInfo.calloc(stack)
                    .sType$Default().pImageMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            org.lwjgl.vulkan.VkImageCopy.Buffer region = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.extent().width(width).height(height).depth(1);
            VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            barrierTransferToShader(stack, cmd, dst.image);
        }
    }

    @Override
    public void resetHistory() {
        // Ring buffer owns the historical data; force the next dispatch to re-clear + write the
        // first frame's history from scratch. We can't clear it here (no cmd buffer in this method),
        // so we set the latch and the next dispatch() will clear + start clean.
        historyCleared = false;
        frameCounter = 0;
    }

    @Override
    public void destroy() {
        if (!ready) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx != null) {
            VkDevice vk = ctx.vk();
            if (reprojectPipeline != 0L) VK10.vkDestroyPipeline(vk, reprojectPipeline, null);
            if (resolvePipeline != 0L) VK10.vkDestroyPipeline(vk, resolvePipeline, null);
            if (atrousPipeline != 0L) VK10.vkDestroyPipeline(vk, atrousPipeline, null);
            if (reprojectLayout != 0L) VK10.vkDestroyPipelineLayout(vk, reprojectLayout, null);
            if (resolveLayout != 0L) VK10.vkDestroyPipelineLayout(vk, resolveLayout, null);
            if (atrousLayout != 0L) VK10.vkDestroyPipelineLayout(vk, atrousLayout, null);
            if (reprojectPool != 0L) VK10.vkDestroyDescriptorPool(vk, reprojectPool, null);
            if (resolvePool != 0L) VK10.vkDestroyDescriptorPool(vk, resolvePool, null);
            if (atrousPool != 0L) VK10.vkDestroyDescriptorPool(vk, atrousPool, null);
            if (reprojectDsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, reprojectDsl, null);
            if (resolveDsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, resolveDsl, null);
            if (atrousDsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, atrousDsl, null);
            if (sampler != 0L) VK10.vkDestroySampler(vk, sampler, null);
        }
        for (int i = 0; i < HISTORY_RING; i++) {
            if (historyRadiance != null && historyRadiance[i] != null) {
                historyRadiance[i].destroy();
                historyRadiance[i] = null;
            }
        }
        if (reprojectColorBuf != null) { reprojectColorBuf.destroy(); reprojectColorBuf = null; }
        if (reprojectVarianceBuf != null) { reprojectVarianceBuf.destroy(); reprojectVarianceBuf = null; }
        if (resolveDenoisedBuf != null) { resolveDenoisedBuf.destroy(); resolveDenoisedBuf = null; }
        ready = false;
        width = 0;
        height = 0;
        frameCounter = 0;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    private long createBilinearClampSampler(VkDevice vk, MemoryStack stack) {
        VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR)
                .minFilter(VK10.VK_FILTER_LINEAR)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateSampler(vk, sci, null, p), "vkCreateSampler(ffx denoise)");
        return p.get(0);
    }

    private void createReprojectPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(8, stack);
            for (int i = 0; i < 5; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            binds.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(7).binding(7).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(ffx reproject)");
            reprojectDsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(8);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(ffx reproject)");
            reprojectPool = p.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(reprojectPool).pSetLayouts(stack.longs(reprojectDsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet), "vkAllocateDescriptorSets(ffx reproject)");
            reprojectSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(32);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(reprojectDsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(ffx reproject)");
            reprojectLayout = p.get(0);

            long module = loadModule(ctx.vk(), stack, "ffx_reproject.comp.spv");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(reprojectLayout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p), "vkCreateComputePipelines(ffx reproject)");
            reprojectPipeline = p.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void createResolvePipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(6, stack);
            for (int i = 0; i < 6; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(ffx resolve)");
            resolveDsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(6);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(ffx resolve)");
            resolvePool = p.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(resolvePool).pSetLayouts(stack.longs(resolveDsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet), "vkAllocateDescriptorSets(ffx resolve)");
            resolveSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(resolveDsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(ffx resolve)");
            resolveLayout = p.get(0);

            long module = loadModule(ctx.vk(), stack, "ffx_resolve_temporal.comp.spv");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(resolveLayout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p), "vkCreateComputePipelines(ffx resolve)");
            resolvePipeline = p.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void createAtrousPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(5, stack);
            for (int i = 0; i < 5; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(ffx atrous)");
            atrousDsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(5);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(ffx atrous)");
            atrousPool = p.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(atrousPool).pSetLayouts(stack.longs(atrousDsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet), "vkAllocateDescriptorSets(ffx atrous)");
            atrousSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(atrousDsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(ffx atrous)");
            atrousLayout = p.get(0);

            long module = loadModule(ctx.vk(), stack, "ffx_atrous.comp.spv");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(atrousLayout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p), "vkCreateComputePipelines(ffx atrous)");
            atrousPipeline = p.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void clearHistoryToZero(MemoryStack stack, VkCommandBuffer cmd) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(RtContext.get(), cmd, "ffx denoise init clear")) {
            VkClearColorValue black = VkClearColorValue.calloc(stack);
            for (int i = 0; i < 4; i++) {
                black.float32(i, 0.0f);
            }
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            for (int i = 0; i < HISTORY_RING; i++) {
                VK10.vkCmdClearColorImage(cmd, historyRadiance[i].image,
                        VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            }
            VK10.vkCmdClearColorImage(cmd, reprojectColorBuf.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            VkClearColorValue zeroR = VkClearColorValue.calloc(stack);
            zeroR.float32(0, 0.0f);
            VK10.vkCmdClearColorImage(cmd, reprojectVarianceBuf.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, zeroR, range);
            VK10.vkCmdClearColorImage(cmd, resolveDenoisedBuf.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, zeroR, range);
        }
    }

    private void bindReproject(RtContext ctx, RtImage inColor, RtImage inNormal, RtImage inDepth,
                               RtImage inMotion, RtImage colorOut, RtImage varianceOut, long historyView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] images = {inColor, inNormal, inDepth, inMotion, inColor, colorOut, varianceOut};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(8, stack);
            for (int i = 0; i < 7; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(reprojectSet).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VkDescriptorImageInfo.Buffer samplerInfo = VkDescriptorImageInfo.calloc(1, stack);
            samplerInfo.get(0).imageView(historyView).sampler(sampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(7).sType$Default().dstSet(reprojectSet).dstBinding(7).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(samplerInfo);

            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void bindResolve(RtContext ctx, RtImage inColor, RtImage inNormal, RtImage colorIn,
                             RtImage varianceIn, RtImage specAlbedo, RtImage denoisedOut) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] images = {inColor, inNormal, colorIn, varianceIn, specAlbedo, denoisedOut};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
            for (int i = 0; i < 6; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(resolveSet).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void bindAtrous(RtContext ctx, RtImage inColor, RtImage inNormal, RtImage inDepth,
                           RtImage denoisedIn, RtImage denoisedOut) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] images = {inColor, inNormal, inDepth, denoisedIn, denoisedOut};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
            for (int i = 0; i < 5; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(atrousSet).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private void dispatchReproject(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx, float mvScaleX, float mvScaleY) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx reproject")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, reprojectPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, reprojectLayout, 0, stack.longs(reprojectSet), null);
            ByteBuffer push = stack.malloc(32);
            // FfxReprojectParams: vec2 motionScale, disocclusionThreshold, normalThreshold,
            // depthThreshold, frameHistoryReady, pad, pad
            push.putFloat(0, mvScaleX);
            push.putFloat(4, mvScaleY);
            // Depth thresholds: tight enough to reject pan/occlusion ghosts, loose enough
            // that fp16 history.a quantisation alone does not permanent-disocclude.
            push.putFloat(8, 0.06f);  // relative depth disocclusion threshold
            push.putFloat(12, 0.2f);  // normalThreshold (reserved)
            push.putFloat(16, 0.06f); // absolute reversed-Z depth threshold
            push.putFloat(20, frameCounter > 0 ? 1.0f : 0.0f);
            push.putFloat(24, 0.0f);
            push.putFloat(28, 0.0f);
            VK10.vkCmdPushConstants(cmd, reprojectLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    private void dispatchResolve(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx resolve")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, resolvePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, resolveLayout, 0, stack.longs(resolveSet), null);
            ByteBuffer push = stack.malloc(16);
            // Must match FfxResolveParams in ffx_resolve_temporal.comp:
            //   float temporalWeightMax;  // @0
            //   float varianceCutoff;     // @4
            //   float minHistoryBlend;    // @8
            //   float frameIndex;         // @12
            // temporalWeightMax default 0.82 (anti-ghost). varianceCutoff: trust =
            // 1 - variance/cutoff; 0.55 means variance=0.05 → trust≈0.91, variance=0.5
            // (brisk pan from reproject motionVar) → trust≈0.09.
            // minHistoryBlend=0: hard disocclusion short-circuits before the mix.
            push.putFloat(0, dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.FFX_TEMPORAL_WEIGHT_MAX.value());
            push.putFloat(4, 0.55f);   // varianceCutoff
            push.putFloat(8, 0.0f);    // minHistoryBlend
            push.putFloat(12, (float) frameCounter);
            VK10.vkCmdPushConstants(cmd, resolveLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    private void dispatchAtrous(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx, int stepSize) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx atrous s" + stepSize)) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, atrousPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, atrousLayout, 0, stack.longs(atrousSet), null);
            ByteBuffer push = stack.malloc(16);
            // FfxAtrousParams: depthSigma, normalSigma, colorSigma, stepSize.
            //
            // colour weight removed (depth/normal only). Keep depth sigma tight so large
            // steps cannot average the whole frame into a near-black mush.
            push.putFloat(0, 0.03f);           // depthSigma (reversed-Z, tight edges)
            push.putFloat(4, 0.15f);           // normalSigma
            push.putFloat(8, 1.0f);            // colorSigma (unused; host layout pad)
            push.putFloat(12, (float) stepSize);
            VK10.vkCmdPushConstants(cmd, atrousLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    private void barrierCompute(MemoryStack stack, VkCommandBuffer cmd, long... images) {
        int count = images.length;
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            barriers.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private void barrierComputeToTransfer(MemoryStack stack, VkCommandBuffer cmd, long... images) {
        int count = images.length;
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            barriers.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    /**
     * Make TRANSFER writes (clear / image copy) visible to subsequent compute shader reads.
     * Required on AMD RADV — without it the FFX history ring samples stale zeros and the
     * reproject depth test permanently disoccludes every pixel.
     */
    private void barrierTransferToShader(MemoryStack stack, VkCommandBuffer cmd, long... images) {
        int count = images.length;
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            barriers.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private void copyFinalToHistory(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx, RtImage outColor) {
        int writeSlot = (int) (frameCounter % HISTORY_RING);
        RtImage history = historyRadiance[writeSlot];
        if (history == null) {
            System.err.println("[Caustica FFX] copyFinalToHistory early-return: historyRadiance[" + writeSlot + "] is null");
            return;
        }
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx history copy")) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.extent().width(width).height(height).depth(1);
            VK10.vkCmdCopyImage(cmd, outColor.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    history.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = FfxDenoiseBackend.class.getResourceAsStream(SHADER_DIR + name)) {
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
