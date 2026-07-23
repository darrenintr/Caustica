package dev.comfyfluffy.caustica.upscale;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vendor-neutral upscaler selector. AUTO uses portable compute TAAU; FSR modes prefer the classic FSR2
 * bridge and fall back to TAAU; unsupported and legacy mode keys also resolve to TAAU so old config files
 * remain bootable. OFF selects the no-op provider.
 */
public final class UpscalerSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    private static volatile Upscaler active;

    private UpscalerSelector() {
    }

    /**
     * Resolve the active upscaler. Providers probe their own Vulkan/native requirements; selection does not
     * infer capabilities from a PCI vendor name. Call again after config invalidation or device recreation.
     */
    public static synchronized Upscaler resolve() {
        return resolve0();
    }

    private static Upscaler resolve0() {
        CausticaConfig.UpscalerMode requested = CausticaConfig.Rt.Upscaler.MODE.value();
        // The legacy legacy FFX-only AMD preset denoise preset (FFX + FSR2) was removed in commit 1
        // (2026-07-20): the 2.x modular loader we bundle has no denoiser effect
        // provider, so AMD AUTO now routes to NRD via DenoiseBackendSelector.
        // There is no FSR2-forcing behavior to keep — NRD doesn't need a specific
        // upscaler partner. AMD vendors are free to pair NRD with any upscaler.
        Upscaler candidate = null;
        String requestedReason = requested.key();
        switch (requested) {
            case OFF -> {
                return setActive(NoopUpscaler.INSTANCE);
            }
            case TAAU -> candidate = TaaUpscaler.tryCreate();
            case FSR2 -> {
                candidate = dev.comfyfluffy.caustica.fsr.Fsr2ClassicUpscaler.tryCreate();
                if (candidate != null) {
                    requestedReason = requested.key() + " → classic FSR2";
                } else {
                    LOGGER.warn("Classic FSR2 unavailable; falling back to TAAU");
                    candidate = TaaUpscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = requested.key() + " → TAAU fallback (no FSR2 native)";
                    }
                }
            }
            case AUTO -> {
                // Default AUTO stays on TAAU (always available). The AMD FidelityFX denoise
                // preset rewrites requested → FSR_3 above; explicit mode=fsr2 also selects FSR2.
                candidate = TaaUpscaler.tryCreate();
                if (candidate != null) {
                    requestedReason = "auto: → TAAU (pure compute)";
                }
            }
        }
        if (candidate == null) {
            LOGGER.warn("Requested upscaler mode '{}' did not initialise on this device; falling back to none.",
                    requested.key());
            return setActive(NoopUpscaler.INSTANCE);
        }
        LOGGER.info("Upscaler selected: {} ({})", candidate.displayName(), requestedReason);
        return setActive(candidate);
    }

    /**
     * Resolve a no-op upscaler (e.g. when the RT context isn't ready yet). Does not log.
     */
    public static Upscaler none() {
        return setActive(NoopUpscaler.INSTANCE);
    }

    public static Upscaler current() {
        return active != null ? active : NoopUpscaler.INSTANCE;
    }

    private static Upscaler setActive(Upscaler u) {
        Upscaler prev = active;
        active = u;
        if (prev != null && prev != u && prev != NoopUpscaler.INSTANCE) {
            // Lazily destroy on a different thread later? For now best-effort: most upscale SDKs are
            // idempotent on teardown (the next device swap will catch this). Defer real destruction
            // to {@link #shutdown()}.
        }
        return u;
    }

    public static synchronized void shutdown() {
        if (active != null && active != NoopUpscaler.INSTANCE) {
            try {
                active.destroy();
            } catch (Throwable t) {
                LOGGER.warn("Upscaler shutdown failed for {}", active.id(), t);
            }
        }
        active = null;
    }
}
