package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.rt.RtAsyncCompute;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * One image-domain denoiser backend. Each implementation owns its descriptors,
 * pipelines, samplers, history buffers, and its internal pipeline barriers.
 * {@link RtComposite} treats the backend as opaque: it does not see the
 * intermediate images or the barrier sequence.
 */
public interface CausticaDenoiseBackend {

    String name();

    void init(long vkDevice, long vkPhysicalDevice);

    void ensureSized(int width, int height);

    /**
     * Records the denoise pass and returns whether {@code outColor} will contain a denoised result.
     * A backend must return {@code false} for every early-out or pass-through path. This is the
     * runtime source of truth used by the compositor; readiness alone is not success.
     */
    boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                     RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                     float mvScaleX, float mvScaleY,
                     RtImage outColor);

    /**
     * Reset the backend's temporal history. Called by {@link RtComposite} when it detects a
     * hard cut (dimension change, teleport, world unload, the user flips the denoise mode in
     * video settings, etc.) — anything that makes the previous frame's accumulated buffer
     * not correspond to the current view. Implementations without a temporal history (e.g.
     * {@link BilateralDenoiseBackend}, {@link NoopDenoiseBackend}) should make this a no-op.
     *
     * <p>Idempotent: safe to call multiple times in a row, or when no reset is actually needed.
     * Does not allocate or destroy — only clears the in-flight history surfaces.
     */
    default void resetHistory() {
        // Backends without temporal history do not need to do anything.
    }

    void destroy();

    boolean isReady();

    /**
     * Returns true if this backend supports async compute (denoise on separate compute queue).
     * If true, {@link RtComposite} will use {@link #dispatchAsync} instead of {@link #dispatch}.
     * Default implementation returns false for backward compatibility.
     */
    default boolean supportsAsyncCompute() {
        return false;
    }

    /**
     * Dispatch denoise work on a separate compute queue for async execution.
     * Only called if {@link #supportsAsyncCompute()} returns true.
     *
     * @param computeCmd Command buffer from compute queue
     * @param asyncCompute Async compute manager (provides semaphores, etc.)
     * @param stack Memory stack for temporary allocations
     * @param inColor Input color from raygen
     * @param inNormal Input normal
     * @param inDepth Input depth
     * @param inMotion Input motion vectors
     * @param mvScaleX Motion vector X scale
     * @param mvScaleY Motion vector Y scale
     * @param outColor Output denoised color
     */
    default void dispatchAsync(
            VkCommandBuffer computeCmd,
            RtAsyncCompute asyncCompute,
            MemoryStack stack,
            RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
            float mvScaleX, float mvScaleY,
            RtImage outColor) {
        throw new UnsupportedOperationException(
                name() + " does not support async compute");
    }
}
