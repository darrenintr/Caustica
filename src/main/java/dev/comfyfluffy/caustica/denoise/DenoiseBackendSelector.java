package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;
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
        // AUTO: cross-vendor optimized selection based on GPU vendor
        if (mode == CausticaConfig.DenoiserKind.AUTO) {
            return autoPick(gpu);
        }
        // HYBRID: Official FFX shadow+reflection → prepare NRD inputs → NRD REBLUR.
        if (mode == CausticaConfig.DenoiserKind.HYBRID) {
            return tryCreate(new HybridFfxNrdBackend(false), gpu, false);
        }
        // NRD: skip FFX entirely (Radiance-style raw layers → REBLUR). FFX: prepass+composite only.
        if (mode == CausticaConfig.DenoiserKind.NRD) {
            return tryCreate(new HybridFfxNrdBackend(true), gpu, false);
        }
        // FFX-only: shadow+reflection + spatial/temporal radiance cleanup (no NRD).
        // Pure OfficialFfx leaves secondary/GI grain and can wash contact shadows;
        // AmdFidelityFx wraps the same FFX path with a residual bilateral for leftover noise.
        if (mode == CausticaConfig.DenoiserKind.FFX) {
            CausticaMod.LOGGER.info("  → FFX shadow/reflection + temporal radiance cleanup (no NRD)");
            return tryCreate(new AmdFidelityFxDenoiseBackend(), gpu, true);
        }
        CausticaMod.LOGGER.warn("Denoise mode={} unavailable; using Noop (raw RT)", mode.key());
        return NoopDenoiseBackend.INSTANCE;
    }

    /**
     * Cross-vendor optimized denoiser selection for AUTO mode:
     * - AMD: NRD-only (FFX 2.x modular API has no denoiser provider on this loader; NRD
     *   is the only AMD path that actually runs on this build).
     * - NVIDIA: Hybrid FFX+NRD (leverages NRD's quality on Tensor cores)
     * - Intel: NRD-only (XMX acceleration for REBLUR on Arc)
     * - Unknown: NRD with Bilateral fallback (we no longer recommend FFX as a default
     *   since the 2.x modular API ships no denoiser provider on this loader).
     */
    private static CausticaDenoiseBackend autoPick(GpuVendor gpu) {
        CausticaMod.LOGGER.info("AUTO mode: selecting optimal denoiser for GPU vendor {}", gpu.vendor);
        return switch (gpu.vendor) {
            case AMD -> {
                // AMD path: NRD-only (HybridFfxNrdBackend(true) skips the FFX prepass).
                // The 2.x modular loader we bundle does not ship a denoiser effect provider,
                // so the legacy FFX/AMD_FIDELITYFX path is gone; NRD is what runs.
                CausticaMod.LOGGER.info("  → NRD-only (AMD; FFX 2.x modular has no denoiser provider on this build)");
                yield tryCreateNrdAuto(new HybridFfxNrdBackend(true), gpu);
            }
            case NVIDIA -> {
                // Hybrid: FFX shadow/reflection + NRD REBLUR = best quality on RTX
                CausticaMod.LOGGER.info("  → Hybrid FFX+NRD (NVIDIA optimized)");
                yield tryCreateNrdAuto(new HybridFfxNrdBackend(false), gpu);
            }
            case INTEL -> {
                // NRD-only: XMX accelerated REBLUR on Arc, skip FFX prepass
                CausticaMod.LOGGER.info("  → NRD-only (Intel Arc XMX optimized)");
                yield tryCreateNrdAuto(new HybridFfxNrdBackend(true), gpu);
            }
            default -> {
                // Unknown GPU: NRD with Bilateral fallback (FFX no longer recommended
                // as a default since the 2.x modular API has no denoiser provider).
                CausticaMod.LOGGER.info("  → NRD-only (unknown GPU)");
                yield tryCreateNrdAuto(new HybridFfxNrdBackend(true), gpu);
            }
        };
    }

    private static CausticaDenoiseBackend tryCreateNrdAuto(CausticaDenoiseBackend candidate, GpuVendor gpu) {
        if (NrdRuntime.INSTANCE.tryLoad().isEmpty()) {
            CausticaMod.LOGGER.warn("AUTO candidate {} has no compatible NRD native; using bilateral fallback",
                    candidate.name());
            return tryCreate(new BilateralDenoiseBackend(), gpu, false);
        }
        return tryCreate(candidate, gpu, true);
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
