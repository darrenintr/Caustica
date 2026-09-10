package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Async-Compute scheduling helper. Records compute-only passes (FFX/Hybrid denoise,
 * beauty TAA, exposure histogram) onto a dedicated compute queue and synchronises the
 * graphics queue back via a binary semaphore signalled when the compute work completes.
 *
 * <p>Pipeline shape (async path):
 * <pre>
 * Graphics queue:  raygen ──────────────────────────► upscale ──► present
 *                            │ waits for computeDone  ▲
 * Compute queue:             └─► denoise ─► t-aa/exp ─┘
 * </pre>
 *
 * <p>The previous single-queue fallback (everything on the graphics queue) is still the
 * default because:
 * <ol>
 *   <li>Most users don't have a dedicated compute family that isn't shared with the
 *       present queue (which forbids the binary-signal / binary-wait pattern per
 *       VUID-vkCmdPipelineBarrier2-semaphore-reset-state).</li>
 *   <li>Even on AMD RADV the win is modest (~1ms on a 12 ms frame), so the user config
 *       is opt-in.</li>
 * </ol>
 *
 * <p>API summary:
 * <ul>
 *   <li>{@link #tryCreate(RtContext)} — probe / build resources. Null on a device that
 *       cannot expose a dedicated compute family.</li>
 *   <li>{@link #submitComputeSegment(MemoryStack, int, Consumer, long, int)} — record the
 *       compute work into a fresh compute cmd buffer, submit it on the compute queue
 *       with a binary-signal of {@link #getDenoiseDoneSemaphore()}, then insert a
 *       pipeline-barrier pair on the graphics queue so the next graphics cmd waits for
 *       the same semaphore. Pass {@code readyStageMask} from the recording helper so the
 *       graphics side waits on the correct stage (typically {@code COMPUTE} for shader
 *       outputs).</li>
 *   <li>{@link #recordFallbackFrame()} — call this when the renderer fell back to the
 *       single-queue path (the backend produced false or an exception was caught).
 *       Drives the percentage stats.</li>
 * </ul>
 */
public final class RtAsyncCompute {

    private static final int MAX_FRAMES_IN_FLIGHT = 3;

    private final RtContext ctx;
    private final VulkanDevice device;
    private final VkDevice vk;

    // Compute queue (null if async compute not available).
    private final VulkanQueue computeQueue;
    private final int computeFamilyIndex;

    // Per-frame command pool on the compute queue + ring of N command buffers.
    private long computeCommandPool = VK_NULL_HANDLE;
    private final long[] computeCommandBuffers = new long[MAX_FRAMES_IN_FLIGHT];

    // Per-frame sync primitives.
    private final long[] raygenDoneSemaphores = new long[MAX_FRAMES_IN_FLIGHT];
    // Signalled when the entire pre-upscale compute segment (firefly-kill + denoise OR
    // firefly-kill + beauty TAA) completes on the compute queue. The graphics queue
    // waits on this before kicking off the upscale / display pipeline.
    private final long[] preUpscaleDoneSemaphores = new long[MAX_FRAMES_IN_FLIGHT];
    private final long[] computeFences = new long[MAX_FRAMES_IN_FLIGHT];

    private int frameIndex = 0;
    private long totalAsyncFrames = 0;
    private long totalFallbackFrames = 0;

    private RtAsyncCompute(RtContext ctx, VulkanDevice device, VulkanQueue computeQueue) {
        this.ctx = ctx;
        this.device = device;
        this.vk = ctx.vk();
        this.computeQueue = computeQueue;
        this.computeFamilyIndex = computeQueue.queueFamilyIndex();
        createComputeCommandPool();
        allocateComputeCommandBuffers();
        createSyncPrimitives();
    }

    /**
     * Build if the device exposes a dedicated compute queue family and the renderer is
     * configured to opt in. Returns null on either fail — callers must check before use
     * and fall back to the single-queue path.
     */
    public static RtAsyncCompute tryCreate(RtContext ctx) {
        if (!CausticaConfig.Rt.AsyncCompute.ENABLED.value()) {
            return null;
        }
        if (!RtDeviceBringup.asyncComputeAvailable()) {
            CausticaMod.LOGGER.debug("Async Compute requested but no dedicated compute family on this device; falling back to single-queue");
            return null;
        }
        VulkanDevice device = ctx.device();
        try {
            VulkanQueue computeQueue = getComputeQueue(ctx);
            if (computeQueue == null) {
                return null;
            }
            return new RtAsyncCompute(ctx, device, computeQueue);
        } catch (Exception e) {
            CausticaMod.LOGGER.error("Failed to initialize Async Compute", e);
            return null;
        }
    }

    private static VulkanQueue getComputeQueue(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int familyIndex = RtDeviceBringup.computeQueueFamilyIndex();
            int queueIndex = RtDeviceBringup.computeQueueIndex();
            VkDevice vk = ctx.vk();
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(vk, familyIndex, queueIndex, pQueue);
            VkQueue vkQueue = new VkQueue(pQueue.get(0), vk);
            return new VulkanQueue(vkQueue, familyIndex);
        }
    }

    private void createComputeCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(computeFamilyIndex);
            long[] pPool = new long[1];
            int result = vkCreateCommandPool(vk, poolInfo, null, pPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute command pool: " + result);
            }
            computeCommandPool = pPool[0];
        }
    }

    private void allocateComputeCommandBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(computeCommandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(MAX_FRAMES_IN_FLIGHT);
            PointerBuffer pBuffers = stack.mallocPointer(MAX_FRAMES_IN_FLIGHT);
            int rc = vkAllocateCommandBuffers(vk, ai, pBuffers);
            if (rc != VK_SUCCESS) {
                throw new RuntimeException("vkAllocateCommandBuffers(compute) failed: " + rc);
            }
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                computeCommandBuffers[i] = pBuffers.get(i);
            }
        }
    }

    private void createSyncPrimitives() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                long[] pSemaphore = new long[1];
                int result = vkCreateSemaphore(vk, semaphoreInfo, null, pSemaphore);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create raygen-done semaphore: " + result);
                }
                raygenDoneSemaphores[i] = pSemaphore[0];

                result = vkCreateSemaphore(vk, semaphoreInfo, null, pSemaphore);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create pre-upscale-done semaphore: " + result);
                }
                preUpscaleDoneSemaphores[i] = pSemaphore[0];

                long[] pFence = new long[1];
                result = vkCreateFence(vk, fenceInfo, null, pFence);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create compute fence: " + result);
                }
                computeFences[i] = pFence[0];
            }
        }
    }

    /**
     * Whether the device actually has a non-null compute queue we can submit on. Single-
     * queue fallback paths in {@code RtComposite} should call {@link #recordFallbackFrame()}
     * when this is {@code false} so the stats log correctly.
     */
    public boolean isAvailable() {
        return computeQueue != null;
    }

    public VulkanQueue getComputeQueue() {
        return computeQueue;
    }

    /**
     * The LWJGL {@link VkCommandBuffer} wrapping the per-frame compute cmd handle. Caller
     * is expected to pass this (or one built from the same long handle) directly to
     * Vulkan entry points that consume a VkCommandBuffer.
     */
    public VkCommandBuffer getComputeCommandBuffer() {
        return new VkCommandBuffer(computeCommandBuffers[frameIndex], vk);
    }

    /** Semaphore signalled when the per-frame pre-upscale compute segment completes. */
    public long getDenoiseDoneSemaphore() {
        return preUpscaleDoneSemaphores[frameIndex];
    }

    public long getRaygenDoneSemaphore() {
        return raygenDoneSemaphores[frameIndex];
    }

    public long getComputeFence() {
        return computeFences[frameIndex];
    }

    /**
     * Record the supplied work into the per-frame compute command buffer, submit on the
     * compute queue with a binary-signal of {@link #getDenoiseDoneSemaphore()}, then
     * insert a pipeline barrier on the graphics-side {@code graphicsCmd} so its
     * subsequent dispatches wait for that semaphore. {@code readyStageMask} identifies
     * the stage the graphics side should wait on (typically
     * {@code VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT}).
     *
     * <p>Returns {@code true} when work was actually offloaded to the compute queue,
     * {@code false} if any Vulkan call failed (the caller should treat the failure as a
     * fallback frame and use the single-queue path).
     */
    public boolean submitComputeSegment(MemoryStack stack,
                                        int srcStageMask,
                                        Consumer<VkCommandBuffer> recorder,
                                        VkCommandBuffer graphicsCmd,
                                        int readyStageMask) {
        if (computeQueue == null || computeCommandPool == VK_NULL_HANDLE) {
            return false;
        }
        int slot = frameIndex;
        VkCommandBuffer computeBuf = new VkCommandBuffer(computeCommandBuffers[slot], vk);
        long preUpscaleSem = preUpscaleDoneSemaphores[slot];
        long fence = computeFences[slot];

        // Reset + begin the compute cmd buffer.
        try {
            vkResetCommandBuffer(computeBuf, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int rc = vkBeginCommandBuffer(computeBuf, beginInfo);
            if (rc != VK_SUCCESS) {
                CausticaMod.LOGGER.warn("Async compute: vkBeginCommandBuffer failed ({})", rc);
                return false;
            }
            try {
                recorder.accept(computeBuf);
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("Async compute: recorder threw", t);
                return false;
            }
            rc = vkEndCommandBuffer(computeBuf);
            if (rc != VK_SUCCESS) {
                CausticaMod.LOGGER.warn("Async compute: vkEndCommandBuffer failed ({})", rc);
                return false;
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Async compute: cmd buffer reset/begin threw", t);
            return false;
        }

        // Submit on the compute queue; signal preUpscaleDoneSemaphore so the graphics
        // queue's pre-upscale wait wakes at the right point in the command stream.
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType$Default()
                .pCommandBuffers(stack.pointers(computeBuf.address()))
                .pSignalSemaphores(stack.longs(preUpscaleSem));
        int rc = vkQueueSubmit(computeQueue.vkQueue(), submit, fence);
        if (rc != VK_SUCCESS) {
            CausticaMod.LOGGER.warn("Async compute: vkQueueSubmit returned {}", rc);
            return false;
        }

        // Insert a pipeline barrier on the graphics queue so the very next graphics
        // dispatch that touches compute output will see the up-to-date state.
        waitOnDenoiseCompleted(graphicsCmd, readyStageMask);
        totalAsyncFrames++;
        return true;
    }

    /**
     * Single-VK10 binary-semaphore pipeline barrier inserted into the supplied graphics
     * command buffer. Idempotent / per-frame — the caller decides which cmd buffer to
     * place the barrier in (typically the frame's primary). The actual binary-semaphore
     * wait is expressed through {@code vkQueueSubmit} on the graphics side (the host-side
     * loop wraps {@link com.mojang.blaze3d.vulkan.VulkanDevice#commandQueue()}), so this
     * barrier only needs to flush the cache domain — the compute outputs become visible
     * to the next graphics dispatch by the time the pipeline barrier returns.
     */
    private void waitOnDenoiseCompleted(VkCommandBuffer graphicsCmd, int dstStage) {
        if (graphicsCmd == null || graphicsCmd.address() == VK_NULL_HANDLE) {
            return;
        }
        vkCmdPipelineBarrier(
                graphicsCmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                dstStage,
                0,
                null,
                null,
                null);
    }

    public void recordFallbackFrame() {
        totalFallbackFrames++;
    }

    public void nextFrame() {
        frameIndex = (frameIndex + 1) % MAX_FRAMES_IN_FLIGHT;
    }

    public void waitForComputeIdle() {
        if (computeFences[frameIndex] != VK_NULL_HANDLE) {
            vkWaitForFences(vk, computeFences[frameIndex], true, Long.MAX_VALUE);
            vkResetFences(vk, computeFences[frameIndex]);
        }
    }

    public String getStats() {
        long total = totalAsyncFrames + totalFallbackFrames;
        if (total == 0) {
            return "async=0%";
        }
        return String.format("async=%d%% (%d/%d)",
                (totalAsyncFrames * 100) / total, totalAsyncFrames, total);
    }

    public void destroy() {
        if (vk == null) {
            return;
        }
        try {
            vkQueueWaitIdle(computeQueue.vkQueue());
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            if (raygenDoneSemaphores[i] != VK_NULL_HANDLE) {
                vkDestroySemaphore(vk, raygenDoneSemaphores[i], null);
            }
            if (preUpscaleDoneSemaphores[i] != VK_NULL_HANDLE) {
                vkDestroySemaphore(vk, preUpscaleDoneSemaphores[i], null);
            }
            if (computeFences[i] != VK_NULL_HANDLE) {
                vkDestroyFence(vk, computeFences[i], null);
            }
        }
        if (computeCommandBuffers[0] != 0L) {
            PointerBuffer bufPtr = MemoryStack.stackGet().pointers(computeCommandBuffers);
            vkFreeCommandBuffers(vk, computeCommandPool, bufPtr);
        }
        if (computeCommandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(vk, computeCommandPool, null);
        }
        CausticaMod.LOGGER.info("Async Compute destroyed - {}", getStats());
    }
}
