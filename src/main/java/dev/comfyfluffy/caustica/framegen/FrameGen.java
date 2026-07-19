package dev.comfyfluffy.caustica.framegen;

/**
 * Frame generation stub. The previous implementation wrapped DLSS-FG / FSR-FG / XeSS-FG;
 * those SDKs are no longer supported in this build (TAAU-only). All implementations
 * returned by {@link FrameGenSelector} are no-ops that report unavailable.
 */
public interface FrameGen {

    /** Whether this frame generator can produce extra frames right now. */
    boolean isAvailable();

    /** Whether this frame generator has a feature created for the current dimensions. */
    boolean isReady();

    /** Active source mode this frame gen would consume (e.g. DLSS-RR, TAAU). */
    dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode sourceMode();

    /** Effective multi-frame count (1 = no frame gen). */
    int effectiveMultiFrameCount();

    /**
     * Run the frame-gen pass (FSR / XeSS-FG style). Compatibility no-op in this build -- always
     * returns false so the caller falls through to a 1:1 blit.
     */
    default boolean interpolate(Object... args) { return false; }

    /** No-op implementation returned when no real frame-gen backend is available. */
    FrameGen NOOP = new FrameGen() {
        @Override public boolean isAvailable() { return false; }
        @Override public boolean isReady() { return false; }
        @Override public dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode sourceMode() {
            return dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode.TAAU;
        }
        @Override public int effectiveMultiFrameCount() { return 1; }
    };
}