package dev.comfyfluffy.caustica.framegen;

import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import org.joml.Matrix4fc;

/**
 * Interpolation pass that produces extra displayed frames between two real (rendered) frames. The present
 * engine ({@code RtFramePresenter}) acquires extra swapchain images and calls
 * {@link #interpolate(long, int, int, int, boolean)} for each generated frame.
 *
 * <p>Inputs: the just-rendered frame's {@code color} (the swapchain-bound SDR/PQ backbuffer or a
 * hudless copy) + its depth + MVs + (when supported) the previous hudless frame. Implementations are
 * free to ignore inputs they don't need; the swapchain-format-dependent layer is the caller's problem.
 */
public interface FrameGen {

    /** The upscaler mode whose frame-gen this is; the same enum so the config UI groups them. */
    UpscalerSelector.Mode sourceMode();

    /** True if the SDK initialised AND a feature for the current size/format has been created. */
    boolean isReady();

    /** Whether the runtime says frame gen is supported on this device (driver, hardware, etc.). */
    boolean isAvailable();

    /** The effective multi-frame count (1 = 2x, 2 = 3x, ...); 0 if unavailable. */
    int effectiveMultiFrameCount();

    /**
     * (Re)create the feature if dimensions / format changed. Returns true on success; on failure the
     * implementation latches to "unavailable" so {@link #interpolate} becomes a duplicate-frame fallback
     * rather than crashing.
     */
    boolean ensureFeature(long cmd, int width, int height, int renderWidth, int renderHeight,
                          int backbufferFormat, int multiFrameCount);

    /**
     * Compute the {@code index}-th generated frame (1-based; 1 = first generated frame after the
     * previous real, {@code multiFrameCount} = the one immediately before the new real). The result is
     * written to the {@code outImage}/{@code outView} arguments. The implementation is responsible for
     * issuing any GPU work into {@code cmd} that needs to land before the swapchain blit that consumes
     * the output.
     *
     * <p>{@code hdrBackbuffer} tells the implementation that the source backbuffer is PQ-encoded (the
     * present path's HDR variant) — XeSS-FG and FSR FG both require the input to be in the swapchain's
     * native color space, so the source image has already been written in that space by the RT composite.
     */
    boolean interpolate(long cmd,
                        long colorView, long colorImage, int colorFormat,
                        long depthView, long depthImage, int depthFormat,
                        long mvView, long mvImage, int mvFormat,
                        long hudlessView, long hudlessImage, int hudlessFormat,
                        long uiView, long uiImage, int uiFormat,
                        long outView, long outImage, int outFormat,
                        long prevColorView, long prevColorImage, int prevColorFormat,
                        int width, int height,
                        int index, int multiFrameCount,
                        boolean hdrBackbuffer,
                        Matrix4fc viewToClip, Matrix4fc clipToView,
                        Matrix4fc clipToPrevClip, Matrix4fc prevClipToClip);

    /** Release the feature. */
    void destroy();
}
