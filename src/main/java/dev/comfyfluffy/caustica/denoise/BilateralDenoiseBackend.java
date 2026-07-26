package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
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
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
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
 * Multi-pass 3x3 joint bilateral denoiser with optional temporal accumulation.
 *
 * <p>Spatial core: odd-count ping-pong of 3x3 joint bilateral (depth + normal +
 * relative-luma edge-stop). Pure SPIR-V, ~0.5 ms @ 1080p, vendor-portable, used
 * as the safe denoise path on RDNA / Intel Arc and as a residual polish after NRD
 * REBLUR.</p>
 *
 * <p>Optional temporal layer: a single-pass {@code bilateral_temporal.comp} that
 * runs <i>before</i> the spatial passes, blending the (noisy or pre-spatial) input
 * with the previous frame's accumulated radiance using a {@code 1/accumulatedFrameCount}
 * weight (Sundial-Lite Deferred12.frag:413-414). The temporal pass rejects history
 * samples whose 3D position jumps further than expected along the surface normal
 * (Sundial-Lite Deferred0.frag:93-95) — cheaper than NRD's plane-distance check and
 * more sensitive to thin geometry than the existing depth-jump reject.</p>
 *
 * <p>Temporal history is disabled by default; pass {@code temporalEnabled=true} to
 * the constructor to turn it on. Once on, the backend owns a {@code R11G11B10} history
 * texture + {@code R8} counter texture; both are zeroed on first dispatch and on
 * {@link #resetHistory()}.</p>
 */
public final class BilateralDenoiseBackend implements CausticaDenoiseBackend {

    private static final String SHADER_DIR = "/caustica/rt/";

    private final int passes;
    private final float depthSigma;
    private final float normalSigma;
    private final float colorSigma;
    private final String label;
    /** Borrowed from Sundial-Lite VB_MAX_BLEDED_FRAMES=20 (max accumulation depth). */
    private final int maxAccumulatedFrames;
    /** Sundial's empirical "5% of view distance" position-distance tolerance. */
    private final float positionDistanceThreshold;
    private final boolean temporalEnabled;
    /** Storage-image format used by all bilateral color inputs, outputs, and ping-pong images. */
    private final int colorFormat;
    private final String spatialShaderName;
    private final String temporalShaderName;

    private boolean ready;
    private int width;
    private int height;
    private boolean historyCleared;

    private long dsl;
    private long pool;
    private long[] sets = new long[0];
    private long[][] boundViews = new long[0][];
    private long layout;
    private long pipeline;
    private RtImage temp;

    // Temporal layer state.
    private long temporalDsl;
    private long temporalPool;
    private long temporalSet;
    private long temporalLayout;
    private long temporalPipeline;
    private RtImage temporalHistory;
    private RtImage temporalCounter;

    /** Default strong spatial (5 passes), temporal off — for pure bilateral backend. */
    public BilateralDenoiseBackend() {
        this(5, 0.04f, 0.18f, 1.2f, "bilateral", false, 20, 0.05f);
    }

    /**
     * @param passes odd pass count (forced odd); 3 is a good residual polish after NRD
     * @param temporalEnabled when true, a temporal blend pass runs before the spatial passes
     * @param maxAccumulatedFrames clamp on the per-pixel accumulator counter (Sundial uses 20)
     * @param positionDistanceThreshold along-normal tolerance for reproject validation,
     *        expressed as a fraction of view distance. Sundial's empirical value is 0.05
     *        (5% of view distance); range [0.005, 0.5] is sane.
     */
    public BilateralDenoiseBackend(int passes, float depthSigma, float normalSigma, float colorSigma,
                                   String label, boolean temporalEnabled,
                                   int maxAccumulatedFrames, float positionDistanceThreshold) {
        this(passes, depthSigma, normalSigma, colorSigma, label, temporalEnabled,
                maxAccumulatedFrames, positionDistanceThreshold, RtContext.HDR_RADIANCE_FORMAT,
                "bilateral.comp.spv", "bilateral_temporal.comp.spv");
    }

    private BilateralDenoiseBackend(int passes, float depthSigma, float normalSigma, float colorSigma,
                                    String label, boolean temporalEnabled,
                                    int maxAccumulatedFrames, float positionDistanceThreshold,
                                    int colorFormat, String spatialShaderName, String temporalShaderName) {
        this.passes = (passes < 1) ? 1 : (passes | 1); // force odd
        this.depthSigma = depthSigma;
        this.normalSigma = normalSigma;
        this.colorSigma = colorSigma;
        this.label = label != null ? label : "bilateral";
        this.temporalEnabled = temporalEnabled;
        this.maxAccumulatedFrames = Math.max(1, maxAccumulatedFrames);
        this.positionDistanceThreshold = Math.max(1e-4f, positionDistanceThreshold);
        this.colorFormat = colorFormat;
        this.spatialShaderName = spatialShaderName;
        this.temporalShaderName = temporalShaderName;
    }

    /** Spatial-only constructor (back-compat: no temporal). */
    public BilateralDenoiseBackend(int passes, float depthSigma, float normalSigma, float colorSigma, String label) {
        this(passes, depthSigma, normalSigma, colorSigma, label, false, 20, 0.05f);
    }

    /** Mild residual polish after REBLUR (ghost-safe spatial only). */
    public static BilateralDenoiseBackend residualAfterNrd() {
        return new BilateralDenoiseBackend(3, 0.035f, 0.14f, 0.85f, "nrd-residual-bilateral",
                false, 20, 0.05f, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "bilateral_rgba16f.comp.spv", "bilateral_temporal_rgba16f.comp.spv");
    }

    /**
     * Residual polish after REBLUR with the Sundial-inspired temporal layer enabled.
     * Use for low-end GPUs where the NRD output still leaves visible SPP-1 grain —
     * the temporal layer smooths it out over ~20 frames without an atrous pass.
     */
    public static BilateralDenoiseBackend temporalResidualAfterNrd() {
        return new BilateralDenoiseBackend(3, 0.035f, 0.14f, 0.85f, "nrd-residual-bilateral-temporal",
                true, 20, 0.05f, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "bilateral_rgba16f.comp.spv", "bilateral_temporal_rgba16f.comp.spv");
    }

    @Override
    public String name() {
        return label;
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
        if (pipeline == 0L) {
            createPipeline(ctx);
        }
        if (temporalEnabled && temporalPipeline == 0L) {
            createTemporalPipeline(ctx);
        }
        if (this.width == width && this.height == height && temp != null
                && (!temporalEnabled || (temporalHistory != null && temporalCounter != null))) {
            return;
        }
        if (temp != null) {
            temp.destroy();
            temp = null;
        }
        temp = ctx.createStorageImage(width, height, colorFormat, "bilateral temp");
        if (temporalEnabled) {
            if (temporalHistory != null) { temporalHistory.destroy(); temporalHistory = null; }
            if (temporalCounter != null) { temporalCounter.destroy(); temporalCounter = null; }
            temporalHistory = ctx.createStorageImage(width, height, colorFormat,
                    "bilateral temporal history");
            temporalCounter = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8_UNORM,
                    "bilateral temporal counter");
        }
        this.width = width;
        this.height = height;
        historyCleared = false;
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready || pipeline == 0L || temp == null || width <= 0 || height <= 0) {
            return false;
        }
        if (temporalEnabled && (temporalPipeline == 0L || temporalHistory == null || temporalCounter == null)) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }

        // 1. Clear temporal history on first dispatch after a reset (or first ever frame).
        //    We can't clear in resetHistory() (no cmd buffer there), so we lazy-clear here.
        if (temporalEnabled && !historyCleared) {
            clearTemporalHistory(stack, cmd);
            barrierTransferToShader(stack, cmd, temporalHistory.image, temporalCounter.image);
            historyCleared = true;
        }

        // 2. Temporal blend (optional). Writes blended radiance into `temp` so the spatial
        //    loop's pass 0 reads from `temp` instead of the raw input. The spatial loop
        //    reuses `temp` as its ping-pong partner with outColor, so reusing it as the
        //    temporal seed is safe (the temporal pass is the only writer before pass 0
        //    reads it).
        RtImage spatialSeed = inColor;
        if (temporalEnabled) {
            bindTemporal(ctx, inColor, temporalHistory, temporalCounter,
                    inNormal, inDepth, inMotion, temp, temporalCounter);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label + " temporal")) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, temporalPipeline);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, temporalLayout, 0,
                        stack.longs(temporalSet), null);
                ByteBuffer push = stack.malloc(16);
                push.putFloat(0, positionDistanceThreshold);
                push.putInt(4, maxAccumulatedFrames);
                push.putInt(8, 0);
                push.putFloat(12, 0.0f);
                VK10.vkCmdPushConstants(cmd, temporalLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            }
            barrier(stack, cmd, temp.image, temporalCounter.image);
            spatialSeed = temp;
        }

        // 3. Spatial bilateral ping-pong. Pass 0 reads spatialSeed (either raw input or
        //    the temporal-blended temp); ping-pongs with outColor; final pass lands in
        //    outColor (odd-pass ping-pong keeps the result on outColor).
        for (int pass = 0; pass < passes; pass++) {
            RtImage src;
            RtImage dst;
            if ((pass & 1) == 0) {
                src = (pass == 0) ? spatialSeed : temp;
                dst = outColor;
            } else {
                src = outColor;
                dst = temp;
            }
            long set = sets[pass];
            bind(ctx, pass, set, src, inNormal, inDepth, dst);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label + " p" + pass)) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0,
                        stack.longs(set), null);
                ByteBuffer push = stack.malloc(16);
                push.putFloat(0, depthSigma);
                push.putFloat(4, normalSigma);
                push.putFloat(8, colorSigma);
                push.putFloat(12, 1.0f);
                VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            }
            barrier(stack, cmd, dst.image);
        }

        // 4. Stamp outColor → temporalHistory for next frame's temporal pass.
        if (temporalEnabled) {
            stampTemporalHistory(stack, cmd, ctx, outColor, inDepth, temporalHistory, temporalCounter);
        }
        return true;
    }

    @Override
    public void resetHistory() {
        // Lazy-clear on next dispatch (no cmd buffer available here).
        historyCleared = false;
    }

    @Override
    public void destroy() {
        if (!ready) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx != null) {
            VkDevice vk = ctx.vk();
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
            if (temporalPipeline != 0L) VK10.vkDestroyPipeline(vk, temporalPipeline, null);
            if (temporalLayout != 0L) VK10.vkDestroyPipelineLayout(vk, temporalLayout, null);
            if (temporalPool != 0L) VK10.vkDestroyDescriptorPool(vk, temporalPool, null);
            if (temporalDsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, temporalDsl, null);
        }
        if (temp != null) { temp.destroy(); temp = null; }
        if (temporalHistory != null) { temporalHistory.destroy(); temporalHistory = null; }
        if (temporalCounter != null) { temporalCounter.destroy(); temporalCounter = null; }
        ready = false;
        width = 0;
        height = 0;
        pipeline = 0L;
        layout = 0L;
        pool = 0L;
        dsl = 0L;
        sets = new long[0];
        boundViews = new long[0][];
        temporalPipeline = 0L;
        temporalLayout = 0L;
        temporalPool = 0L;
        temporalDsl = 0L;
        temporalSet = 0L;
        historyCleared = false;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    private void barrier(MemoryStack stack, VkCommandBuffer cmd, long... images) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            barriers.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    /** Transfer → shader barrier (used after vkCmdClearColorImage to make zeros visible). */
    private void barrierTransferToShader(MemoryStack stack, VkCommandBuffer cmd, long... images) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            barriers.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private void clearTemporalHistory(MemoryStack stack, VkCommandBuffer cmd) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(RtContext.get(), cmd, label + " clear history")) {
            VkClearColorValue black = VkClearColorValue.calloc(stack);
            for (int i = 0; i < 4; i++) {
                black.float32(i, 0.0f);
            }
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, temporalHistory.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
            VK10.vkCmdClearColorImage(cmd, temporalCounter.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
        }
    }

    /**
     * Copy the final denoised color into the temporal history slot for next frame's blend,
     * and seed the counter to 1 (= 1/255 UNORM). Next frame's blend then uses
     * blendWeight = 1/(1+1) = 0.5, which is the correct Sundial semantics for the first
     * post-stamp frame (one frame of history is already available).
     */
    private void stampTemporalHistory(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx,
                                      RtImage outColor, RtImage inDepth,
                                      RtImage outHistory, RtImage outCounter) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label + " stamp history")) {
            org.lwjgl.vulkan.VkImageCopy.Buffer region = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.extent().width(width).height(height).depth(1);
            VK10.vkCmdCopyImage(cmd, outColor.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    outHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            VkClearColorValue oneCounter = VkClearColorValue.calloc(stack);
            oneCounter.float32(0, 1.0f / 255.0f);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, outCounter.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, oneCounter, range);
            barrierTransferToShader(stack, cmd, outHistory.image, outCounter.image);
        }
    }

    private void createPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                binds.get(i).binding(i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(bilateral)");
            dsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4 * passes);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(passes).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(bilateral)");
            pool = p.get(0);

            sets = new long[passes];
            boundViews = new long[passes][];
            for (int i = 0; i < passes; i++) {
                VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
                LongBuffer pSet = stack.mallocLong(1);
                check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet),
                        "vkAllocateDescriptorSets(bilateral p" + i + ")");
                sets[i] = pSet.get(0);
            }

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(bilateral)");
            layout = p.get(0);

            long module = loadModule(ctx.vk(), stack, spatialShaderName);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p), "vkCreateComputePipelines(bilateral)");
            pipeline = p.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void createTemporalPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 8 storage-image bindings: 0..5 + 7 + 8 (binding 6 is intentionally skipped
            // in the shader — reserved for future per-tile jitter guide, see FfxDenoiseBackend).
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(8, stack);
            int[] usedBindings = {0, 1, 2, 3, 4, 5, 7, 8};
            for (int i = 0; i < usedBindings.length; i++) {
                binds.get(i).binding(usedBindings[i]).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p),
                    "vkCreateDescriptorSetLayout(bilateral temporal)");
            temporalDsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(8);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p),
                    "vkCreateDescriptorPool(bilateral temporal)");
            temporalPool = p.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(temporalPool).pSetLayouts(stack.longs(temporalDsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet),
                    "vkAllocateDescriptorSets(bilateral temporal)");
            temporalSet = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(temporalDsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p),
                    "vkCreatePipelineLayout(bilateral temporal)");
            temporalLayout = p.get(0);

            long module = loadModule(ctx.vk(), stack, temporalShaderName);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(temporalLayout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p),
                    "vkCreateComputePipelines(bilateral temporal)");
            temporalPipeline = p.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void bind(RtContext ctx, int pass, long set,
                      RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage outColor) {
        long[] views = {inColor.view, inNormal.view, inDepth.view, outColor.view};
        if (Arrays.equals(boundViews[pass], views)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] images = {inColor, inNormal, inDepth, outColor};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundViews[pass] = views;
    }

    private void bindTemporal(RtContext ctx,
                              RtImage inColor, RtImage inHistory, RtImage inCounter,
                              RtImage inNormal, RtImage inDepth, RtImage inMotion,
                              RtImage outColor, RtImage outCounter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 8 writes: bindings 0,1,2,3,4,5,7,8 (6 is unused).
            RtImage[] images = {inColor, inHistory, inCounter, inNormal, inDepth, inMotion, null, outColor, outCounter};
            int[] writeBindings = {0, 1, 2, 3, 4, 5, 7, 8};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeBindings.length, stack);
            for (int i = 0; i < writeBindings.length; i++) {
                RtImage img = images[writeBindings[i]];
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(img.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(temporalSet).dstBinding(writeBindings[i]).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = BilateralDenoiseBackend.class.getResourceAsStream(SHADER_DIR + name)) {
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