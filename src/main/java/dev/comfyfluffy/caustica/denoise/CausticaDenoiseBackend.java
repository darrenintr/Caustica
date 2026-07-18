package dev.comfyfluffy.caustica.denoise;

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

    void dispatch(MemoryStack stack, VkCommandBuffer cmd,
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
}
