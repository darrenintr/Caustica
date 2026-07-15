package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.vendor.GpuVendor;

/**
 * Resolves and caches the active {@link CausticaDenoiseBackend} based on
 * {@link CausticaConfig.Rt.Denoise#MODE}. Mirrors {@code UpscalerSelector}:
 * a resolved-once latch that re-runs when {@link #invalidate()} is invoked
 * (e.g. when the user flips the Video Settings picker).
 *
 * <p>{@link #current()} returns {@link NoopDenoiseBackend} when:
 * <ul>
 *   <li>{@code MODE == OFF}</li>
 *   <li>backend creation failed (logged, never throws)</li>
 * </ul>
 */
public final class DenoiseBackendSelector {

    private static volatile CausticaDenoiseBackend active = NoopDenoiseBackend.INSTANCE;
    private static volatile boolean resolvedOnce;
    private static volatile com.mojang.blaze3d.vulkan.VulkanDevice resolvedDevice;

    private DenoiseBackendSelector() {
    }

    public static synchronized CausticaDenoiseBackend current(com.mojang.blaze3d.vulkan.VulkanDevice device) {
        if (resolvedOnce && resolvedDevice == device && active != null) {
            return active;
        }
        if (resolvedDevice != null && resolvedDevice != device) {
            invalidate();
        }
        return resolve(device);
    }

    public static synchronized void invalidate() {
        if (active != null && active != NoopDenoiseBackend.INSTANCE) {
            try {
                active.destroy();
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("Denoise backend destroy failed during invalidate", t);
            }
        }
        active = NoopDenoiseBackend.INSTANCE;
        resolvedOnce = false;
        resolvedDevice = null;
    }

    private static synchronized CausticaDenoiseBackend resolve(com.mojang.blaze3d.vulkan.VulkanDevice device) {
        CausticaConfig.DenoiserKind mode = CausticaConfig.Rt.Denoise.MODE.value();
        CausticaDenoiseBackend backend = pick(mode, GpuVendor.detect());
        backend.init(0L, 0L);
        active = backend;
        resolvedOnce = true;
        resolvedDevice = device;
        CausticaMod.LOGGER.info("Denoise backend selected: {} (mode={})", backend.name(), mode.key());
        return backend;
    }

    private static CausticaDenoiseBackend pick(CausticaConfig.DenoiserKind mode, GpuVendor gpu) {
        if (mode == CausticaConfig.DenoiserKind.OFF) {
            return NoopDenoiseBackend.INSTANCE;
        }
        // AUTO/HYBRID: Official FFX shadow+reflection → prepare NRD inputs → NRD REBLUR.
        // NRD: skip FFX entirely (Radiance-style raw layers → REBLUR). FFX: prepass+composite only.
        if (mode == CausticaConfig.DenoiserKind.AUTO
                || mode == CausticaConfig.DenoiserKind.HYBRID) {
            return tryCreate(new HybridFfxNrdBackend(false), gpu, false);
        }
        if (mode == CausticaConfig.DenoiserKind.NRD) {
            return tryCreate(new HybridFfxNrdBackend(true), gpu, false);
        }
        if (mode == CausticaConfig.DenoiserKind.FFX) {
            return tryCreate(new OfficialFfxDenoiseBackend(), gpu, false);
        }
        CausticaMod.LOGGER.warn("Denoise mode={} unavailable; using Noop (raw RT)", mode.key());
        return NoopDenoiseBackend.INSTANCE;
    }

    private static CausticaDenoiseBackend autoPick(GpuVendor gpu) {
        return tryCreate(new OfficialFfxDenoiseBackend(), gpu, false);
    }

    /**
     * Try the candidate backend (FFx or NRD). Both are written so their constructors and
     * {@code init(0,0)} are safe even when the underlying SDK is missing; failure happens when
     * the backend's {@code ensureSized} tries to load native libraries or build pipelines that
     * touch missing resources.
     *
     * <p>If {@code fallbackBilateral} is true and the candidate fails its probe, fall back to
     * {@link BilateralDenoiseBackend} instead of {@link NoopDenoiseBackend}. The bilateral is a
     * pure SPIR-V 3x3 spatial filter with zero native-SDK dependency, so it always works and
     * produces a smoothed (not noise) image even when the AMD/Intel/NVIDIA vendor libraries
     * are missing.
     */
    private static CausticaDenoiseBackend tryCreate(CausticaDenoiseBackend candidate, GpuVendor gpu, boolean fallbackBilateral) {
        try {
            candidate.init(0L, 0L);
            return candidate;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Denoise backend {} failed to probe; falling back to {}",
                    candidate.name(), fallbackBilateral ? "BilateralDenoiseBackend (spatial-only)" : "NoopDenoiseBackend");
            CausticaDenoiseBackend fallback = fallbackBilateral ? new BilateralDenoiseBackend() : NoopDenoiseBackend.INSTANCE;
            try {
                fallback.init(0L, 0L);
                return fallback;
            } catch (Throwable t2) {
                CausticaMod.LOGGER.warn("Fallback denoise backend {} also failed; giving up to Noop", fallback.name(), t2);
                return NoopDenoiseBackend.INSTANCE;
            }
        }
    }
}
