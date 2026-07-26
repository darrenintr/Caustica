package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;

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
        CausticaDenoiseBackend backend = pick(mode);
        active = backend;
        resolvedOnce = true;
        resolvedDevice = device;
        CausticaMod.LOGGER.info("Denoise backend selected: {} (mode={})", backend.name(), mode.key());
        return backend;
    }

    private static CausticaDenoiseBackend pick(CausticaConfig.DenoiserKind mode) {
        if (mode == CausticaConfig.DenoiserKind.OFF) {
            return NoopDenoiseBackend.INSTANCE;
        }
        // AUTO: capability-first cross-vendor provider probe
        if (mode == CausticaConfig.DenoiserKind.AUTO) {
            return autoPick();
        }
        // HYBRID: Official FFX shadow+reflection → prepare NRD inputs → NRD REBLUR.
        if (mode == CausticaConfig.DenoiserKind.HYBRID) {
            return tryCreate(new HybridFfxNrdBackend(false), false);
        }
        // NRD: skip FFX entirely (Radiance-style raw layers → REBLUR). FFX: prepass+composite only.
        if (mode == CausticaConfig.DenoiserKind.NRD) {
            return tryCreate(new HybridFfxNrdBackend(true), false);
        }
        // FFX-only: shadow+reflection + spatial/temporal radiance cleanup (no NRD).
        // Delegates to {@link OfficialFfxDenoiseBackend} (the canonical AMD FidelityFX SDK pipeline)
        // wrapped by {@link AmdFidelityFxDenoiseBackend}, which adds a residual bilateral pass to
        // clean up the secondary/GI grain that Pure OfficialFfx leaves behind.
        if (mode == CausticaConfig.DenoiserKind.FFX) {
            CausticaMod.LOGGER.info("  → FFX shadow/reflection + temporal radiance cleanup (no NRD)");
            return tryCreate(new AmdFidelityFxDenoiseBackend(), true);
        }
        // BILATERAL: explicit spatial-only denoise. Portable performance floor — about 0.5 ms,
        // no NRD native dispatch and no SDK call.
        // Quality: edge-stopped 3x3 filter, no temporal. Leaves some grain on flat surfaces
        // but never produces fireflies. The user-visible difference vs NRD is most apparent
        // on textures with high-frequency noise — foliage, distant geometry, sea lanterns.
        if (mode == CausticaConfig.DenoiserKind.BILATERAL) {
            CausticaMod.LOGGER.info("  → Bilateral spatial-only (pure SPIR-V, ~0.5 ms, no temporal)");
            return tryCreate(new BilateralDenoiseBackend(), false);
        }
        // RELAX: NRD 4.x attention-based denoiser (sibling of REBLUR). Same vendor-portable
        // story, slightly higher cost (~7-9 ms vs REBLUR's ~6-8 ms on RX 7600) but ~15-20%
        // better quality on fine geometry and material boundaries. Falls back to REBLUR-only
        // NRD if the bundled shim predates RELAX support.
        if (mode == CausticaConfig.DenoiserKind.RELAX) {
            dev.comfyfluffy.caustica.nrd.NrdRuntime.INSTANCE.tryLoad();
            if (!dev.comfyfluffy.caustica.nrd.NrdRuntime.INSTANCE.isRelaxAvailable()) {
                CausticaMod.LOGGER.warn("  → NRD RELAX requested but shim lacks RELAX support; "
                        + "falling back to NRD REBLUR (rebuild with RELAX enabled)");
                return tryCreate(new HybridFfxNrdBackend(true), true);
            }
            CausticaMod.LOGGER.info("  → NRD RELAX (NRD 4.x attention-based, higher cost / better quality)");
            return tryCreate(new HybridFfxNrdBackend(true, true), true);
        }
        CausticaMod.LOGGER.warn("Denoise mode={} unavailable; using Noop (raw RT)", mode.key());
        return NoopDenoiseBackend.INSTANCE;
    }

    /**
     * Capability-first AUTO path. NRD's Vulkan implementation is cross-vendor and performs its own native
     * availability probe, so selecting a different graph from PCI vendor names only creates accidental
     * GPU-vendor policy. Explicit FFX/HYBRID/RELAX modes remain available to users who want them.
     */
    private static CausticaDenoiseBackend autoPick() {
        CausticaMod.LOGGER.info("AUTO mode: probing cross-vendor NRD, with pure-SPIR-V bilateral fallback");
        return tryCreateNrdAuto(new HybridFfxNrdBackend(true));
    }

    private static CausticaDenoiseBackend tryCreateNrdAuto(CausticaDenoiseBackend candidate) {
        if (NrdRuntime.INSTANCE.tryLoad().isEmpty()) {
            CausticaMod.LOGGER.warn("AUTO candidate {} has no compatible NRD native; using bilateral fallback",
                    candidate.name());
            return tryCreate(new BilateralDenoiseBackend(), false);
        }
        return tryCreate(candidate, true);
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
     * produces a smoothed (not noise) image even when optional native libraries are missing.
     */
    private static CausticaDenoiseBackend tryCreate(CausticaDenoiseBackend candidate, boolean fallbackBilateral) {
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
