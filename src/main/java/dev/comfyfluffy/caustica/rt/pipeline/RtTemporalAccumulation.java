package dev.comfyfluffy.caustica.rt.pipeline;

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
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
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
 * Single-pass temporal accumulation (TAA-style) for the noisy path-traced color. Each frame:
 * <ol>
 *   <li>Reproject the previous frame's accumulated color into the current pixel grid along the
 *       per-pixel motion vector ({@code history = sample(prevAccum, uv + mv * mvScale)}).</li>
 *   <li>Blend with the current frame: {@code accumulated = mix(history, current, alpha)},
 *       skipping the history contribution on disocclusion (depth mismatch / out of bounds / sky)
 *       so moving occluder edges don't smear.</li>
 *   <li>Write the result to {@code outAccum} (visible) and to a fresh history slot (alpha carries
 *       the current reversed-Z depth for next frame's disocclusion test).</li>
 * </ol>
 *
 * <p>History is a ping-pong ring of {@link #HISTORY_RING} {@code rgba16f} storage images; the slot
 * a frame reads from is {@code (frameCounter + RING - 1) % RING} and the slot it writes is
 * {@code frameCounter % RING}, so they never alias while frames remain in flight (vanilla MC ≤ 3
 * frames in flight; RING = 4 gives margin). The class owns its descriptor pool, sampler, pipeline,
 * and history ring; {@link #ensureSized(int, int)} (re)allocates on resize and {@link #destroy()}
 * tears everything down.
 *
 * <p>Motion-vector convention matches {@code world.rgen}'s {@code gMotion}
 * ({@code (prevNdc - curNdc) * 0.5 * size} = pixel offset current→previous, same as DLSS/FSR)
 * and {@code ffx_reproject.comp}: {@code prevUV = uv + mv * motionVectorScale}, with
 * {@code motionVectorScale = (1/w, 1/h)}. Using minus inverts the sample and produces multi-frame
 * ghost trails. Depth is compared against current {@code gDepth} (HW reversed-Z, near=1); sky
 * pixels (length(normal) &lt; 0.5) bypass the blend.
 */
public final class RtTemporalAccumulation {
    private static final String SHADER_DIR = "/caustica/rt/";
    private static final int HISTORY_RING = 4;
    // 8x8 workgroups — matches ffx_reproject / ffx_resolve_temporal.
    private static final int WG = 8;
    // push_constant block (std140): vec2 + 5*float padded to 32 bytes (vec2 alignment 8 rounds
    // the struct size up). Range covers all 32 bytes; the last 4 bytes are unused padding.
    private static final int PUSH_BYTES = 32;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final long sampler;

    private RtImage[] history;
    private int width;
    private int height;
    private long frameCounter;
    private boolean historyCleared;

    // Each history-ring state owns a descriptor set. Once populated, a set is never rewritten
    // while an older frame may still be using it on the GPU.
    private final long[][] boundViews;

    private RtTemporalAccumulation(RtContext ctx, long dsl, long pool, long[] sets,
                                   long layout, long pipeline, long sampler) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSets = sets;
        this.boundViews = new long[sets.length][7];
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
        this.sampler = sampler;
    }

    public static RtTemporalAccumulation create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 0..3: storage images (inColor/inNormal/inDepth/inMotion).
            // 4: combined image sampler (historyIn).
            // 5,6: storage images (outAccum writeonly, historyOut writeonly).
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(7, stack);
            for (int i = 0; i < 4; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            binds.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(temporal accum)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "temporal accum descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(6 * HISTORY_RING);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(HISTORY_RING);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(HISTORY_RING).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(temporal accum)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "temporal accum descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl, dsl, dsl, dsl));
            LongBuffer pSet = stack.mallocLong(HISTORY_RING);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(temporal accum)");
            long[] sets = new long[HISTORY_RING];
            for (int i = 0; i < HISTORY_RING; i++) {
                sets[i] = pSet.get(i);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[i],
                        "temporal accum descriptor set " + i);
            }

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(temporal accum)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "temporal accum pipeline layout");

            long sampler = createBilinearClampSampler(vk, stack);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, "temporal accum bilinear sampler");

            long module = loadModule(vk, stack, "temporal_accumulate.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "temporal accum shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(temporal accum)");
            long pipeline = pPipeline.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "temporal accum compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new RtTemporalAccumulation(ctx, dsl, pool, sets, layout, pipeline, sampler);
        }
    }

    /** (Re)allocate the history ring for {w,h} render res; resets the frame counter and clear latch. */
    public void ensureSized(int w, int h) {
        if (history != null && this.width == w && this.height == h) {
            return;
        }
        for (int i = 0; i < (history == null ? 0 : history.length); i++) {
            if (history[i] != null) {
                history[i].destroy();
            }
        }
        history = new RtImage[HISTORY_RING];
        for (int i = 0; i < HISTORY_RING; i++) {
            history[i] = ctx.createStorageImage(w, h, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "temporal accum history " + i + " " + w + "x" + h);
        }
        this.width = w;
        this.height = h;
        this.frameCounter = 0;
        this.historyCleared = false;
        // Views change after realloc so every ring-local binding cache is stale.
        for (long[] views : boundViews) {
            Arrays.fill(views, 0L);
        }
    }

    /**
     * Record one temporal-accumulation dispatch: read the previous frame's accumulated history
     * (slot {@code (frameCounter + RING - 1) % RING}) and write the new accumulated image +
     * next-frame history (slot {@code frameCounter % RING}). {@code outAccum} receives the visible
     * accumulated color (alpha = 1); {@code historyOut} is the class's own history slot for next
     * frame. {@code mvScaleX/Y} are typically {@code 1/w, 1/h}.
     */
    public void dispatch(MemoryStack stack, VkCommandBuffer cmd, RtImage inColor, RtImage inNormal,
                          RtImage inDepth, RtImage inMotion, float mvScaleX, float mvScaleY,
                          float alpha, float disocclusionThreshold, RtImage outAccum) {
        if (history == null || history[0] == null) {
            return; // not sized yet
        }
        if (!historyCleared) {
            clearHistoryToZero(stack, cmd);
            historyCleared = true;
        }
        int readSlot = (int) ((frameCounter + HISTORY_RING - 1) % HISTORY_RING);
        int writeSlot = (int) (frameCounter % HISTORY_RING);
        long historyInView = history[readSlot].view;
        long historyOutView = history[writeSlot].view;
        bind(stack, writeSlot, inColor.view, inNormal.view, inDepth.view, inMotion.view,
                historyInView, outAccum.view, historyOutView);

        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "temporal accumulate")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descriptorSets[writeSlot]), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putFloat(0, mvScaleX);
            push.putFloat(4, mvScaleY);
            push.putFloat(8, disocclusionThreshold);
            push.putFloat(12, alpha);
            push.putFloat(16, frameCounter > 0 ? 1.0f : 0.0f);
            push.putFloat(20, 0.0f);
            push.putFloat(24, 0.0f);
            push.putFloat(28, 0.0f);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + WG - 1) / WG, (height + WG - 1) / WG, 1);
        }
        // The history write must be visible to this same image's next-frame read; the outAccum write
        // must be visible to the downstream denoise/upscaler reads. A compute-read-after-write
        // barrier over the touched images is sufficient (same queue, in-order submission).
        barrierCompute(stack, cmd, outAccum.image, history[writeSlot].image);
        frameCounter++;
    }

    private void bind(MemoryStack stack, int setIndex,
                       long inColorView, long inNormalView, long inDepthView,
                       long inMotionView, long historyInView, long outAccumView, long historyOutView) {
        long[] views = {inColorView, inNormalView, inDepthView, inMotionView,
                historyInView, outAccumView, historyOutView};
        if (Arrays.equals(boundViews[setIndex], views)) {
            return;
        }
        long descriptorSet = descriptorSets[setIndex];

        long[] storageViews = {inColorView, inNormalView, inDepthView, inMotionView, outAccumView, historyOutView};
        int[] storageBindings = {0, 1, 2, 3, 5, 6};
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(7, stack);
        for (int i = 0; i < 6; i++) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageView(storageViews[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(storageBindings[i])
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
        }
        VkDescriptorImageInfo.Buffer samplerInfo = VkDescriptorImageInfo.calloc(1, stack);
        samplerInfo.get(0).sampler(sampler).imageView(historyInView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(6).sType$Default().dstSet(descriptorSet).dstBinding(4).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(samplerInfo);
        VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        System.arraycopy(views, 0, boundViews[setIndex], 0, views.length);
    }

    private void clearHistoryToZero(MemoryStack stack, VkCommandBuffer cmd) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "temporal accum init clear")) {
            org.lwjgl.vulkan.VkClearColorValue.Buffer black =
                    org.lwjgl.vulkan.VkClearColorValue.calloc(1, stack);
            for (int i = 0; i < 4; i++) {
                black.get(0).float32(i, 0.0f);
            }
            org.lwjgl.vulkan.VkImageSubresourceRange.Buffer range =
                    org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            for (int i = 0; i < HISTORY_RING; i++) {
                VK10.vkCmdClearColorImage(cmd, history[i].image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        black.get(0), range);
            }
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(HISTORY_RING, stack);
            for (int i = 0; i < HISTORY_RING; i++) {
                barriers.get(i).sType$Default()
                        .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .image(history[i].image)
                        .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
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
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    /**
     * Clear the temporal history. Called by {@link RtComposite} on a hard cut (dimension change,
     * teleport, world unload) so the next frame's history blend starts from the current frame
     * rather than the previous view's accumulated color (which would otherwise smear for a few
     * frames after the cut). Idempotent; does not allocate or resize.
     */
    public void resetHistory() {
        historyCleared = false;
        frameCounter = 0;
    }

    public void destroy() {
        VkDevice vk = ctx.vk();
        if (pipeline != 0L) {
            VK10.vkDestroyPipeline(vk, pipeline, null);
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        }
        if (descriptorPool != 0L) {
            VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        }
        if (descriptorSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        }
        if (sampler != 0L) {
            VK10.vkDestroySampler(vk, sampler, null);
        }
        if (history != null) {
            for (int i = 0; i < history.length; i++) {
                if (history[i] != null) {
                    history[i].destroy();
                    history[i] = null;
                }
            }
            history = null;
        }
        width = 0;
        height = 0;
        frameCounter = 0;
    }

    private static long createBilinearClampSampler(VkDevice vk, MemoryStack stack) {
        VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR)
                .minFilter(VK10.VK_FILTER_LINEAR)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateSampler(vk, sci, null, p), "vkCreateSampler(temporal accum)");
        return p.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtTemporalAccumulation.class.getResourceAsStream(SHADER_DIR + name)) {
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
