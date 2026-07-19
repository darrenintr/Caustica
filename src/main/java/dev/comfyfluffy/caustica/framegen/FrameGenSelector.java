package dev.comfyfluffy.caustica.framegen;

import dev.comfyfluffy.caustica.upscale.UpscalerSelector;

/**
 * Frame generation stub. Always returns {@link FrameGen#NOOP} -- DLSS-FG / FSR-FG / XeSS-FG
 * have been removed in this build (TAAU-only). Kept as a no-op so call sites in
 * {@code RtComposite} / {@code RtFramePresenter} compile.
 */
public final class FrameGenSelector {

    private FrameGenSelector() {
    }

    public static FrameGen current() {
        return FrameGen.NOOP;
    }

    /** Always returns {@link UpscalerSelector.Mode#TAAU} for compatibility. */
    public static UpscalerSelector.Mode resolvedMode() {
        return UpscalerSelector.Mode.TAAU;
    }

    /** Compatibility no-op: invalidates the cached choice (nothing to invalidate). */
    public static void invalidate() {
    }

    /** Compatibility no-op: resolves the (no-op) frame gen. */
    public static FrameGen resolve(dev.comfyfluffy.caustica.vendor.GpuVendor gpu, UpscalerSelector.Mode sourceMode) {
        return FrameGen.NOOP;
    }
}