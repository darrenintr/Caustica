package dev.comfyfluffy.caustica.framegen;

import org.joml.Matrix4fc;

/**
 * Provider boundary for optional frame-generation SDKs.
 *
 * <p>The renderer and presentation hooks talk only to this interface; vendor SDK ownership, capability
 * probing, feature creation, evaluation, and teardown stay inside the selected provider. Implementations
 * must return {@code false} rather than recording work when their feature is unavailable or not ready.
 */
public interface FrameGen {

    /** Human-readable provider name used in diagnostics. */
    String name();

    /** Whether the user/configuration currently requests this provider. */
    boolean isEnabled();

    /** Whether this provider can produce extra frames on the active device. */
    boolean isAvailable();

    /** Whether a feature exists for the current dimensions. */
    boolean isReady();

    /** Effective generated-frame count requested from the provider. */
    int effectiveMultiFrameCount();

    /** Whether every queued present must be preserved for generated frames to reach the display. */
    default boolean requiresFifoPresent() {
        return true;
    }

    /** Lazily query device/driver capability. Safe to call repeatedly. */
    default void probeAvailabilityOnce() {
    }

    /** Whether a live provider feature already matches the requested dimensions and format. */
    default boolean featureReadyFor(int width, int height, int renderWidth, int renderHeight,
            int backbufferFormat) {
        return false;
    }

    /** Create or resize the provider feature into the supplied recording command buffer. */
    default boolean ensureFeature(long commandBuffer, int width, int height, int renderWidth, int renderHeight,
            int backbufferFormat) {
        return false;
    }

    /**
     * Record one interpolated-frame evaluation. Optional resources use zero view/image/format handles.
     * Matrices are jitter-free clip-space transforms; providers may ignore inputs they do not consume.
     */
    default boolean interpolate(long commandBuffer,
            long backbufferView, long backbufferImage, int backbufferFormat,
            long depthView, long depthImage, int depthFormat,
            long motionView, long motionImage, int motionFormat,
            long hudlessView, long hudlessImage, int hudlessFormat,
            long uiView, long uiImage, int uiFormat,
            long outputView, long outputImage, int outputFormat,
            int width, int height, int motionDepthWidth, int motionDepthHeight,
            int generatedFrameCount, int generatedFrameIndex, float motionScaleX, float motionScaleY,
            boolean depthInverted, boolean colorBuffersHdr, boolean cameraMotionIncluded, boolean reset,
            Matrix4fc clipToPreviousClip, Matrix4fc previousClipToClip) {
        return false;
    }

    /** Release provider-owned feature state. Shared SDK runtimes are shut down by their runtime owner. */
    default void destroy() {
    }

    /** Universal compatibility fallback. */
    FrameGen NOOP = new FrameGen() {
        @Override
        public String name() {
            return "off";
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public int effectiveMultiFrameCount() {
            return 0;
        }

        @Override
        public boolean requiresFifoPresent() {
            return false;
        }
    };
}
