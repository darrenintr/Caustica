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
            case FSR3 -> {
                // FSR 3 uses the same AMD FFX SDK Vulkan backend as classic FSR 2 and triggers
                // the same SQC GPUVM fault on RADV/NAVI33 (first real-geometry dispatch). The
                // Fsr3Upscaler.tryCreate() guard already short-circuits to null on RADV, but
                // we re-check here for defense-in-depth and a clearer log line.
                if (dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
                    LOGGER.warn("FSR 3 path known-unstable on RADV/NAVI33 (SQC GPUVM on first dispatch); routing to TAAU");
                    candidate = TaaUpscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = requested.key() + " → TAAU (RADV FSR 3 workaround)";
                    }
                } else {
                    candidate = dev.comfyfluffy.caustica.fsr.Fsr3Upscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = requested.key() + " → FSR 3.4 (FFX 3.x upscaler)";
                    } else {
                        // FSR 3 bridge failed to load (e.g. .so missing on this platform /
                        // AMD FFX Vulkan backend not shipped). Fall back to classic FSR 2, then TAAU.
                        LOGGER.warn("FSR 3 native unavailable; falling back to classic FSR 2");
                        candidate = dev.comfyfluffy.caustica.fsr.Fsr2ClassicUpscaler.tryCreate();
                        if (candidate != null) {
                            requestedReason = requested.key() + " → classic FSR 2 (FSR 3 unavailable)";
                        } else {
                            LOGGER.warn("Classic FSR 2 also unavailable; falling back to TAAU");
                            candidate = TaaUpscaler.tryCreate();
                            if (candidate != null) {
                                requestedReason = requested.key() + " → TAAU (no FSR 3 or FSR 2 native)";
                            }
                        }
                    }
                }
            }
            case FSR41 -> {
                // FSR 4.1 / FFX 4.x modular. Tries the FSR 4.1 path first
                // (wins when the FSR 4.1 Linux port's Vulkan backend is
                // shipped), then classic FSR2, then TAAU. FSR 4.1 has
                // the same RADV/NAVI33 first-dispatch crash class as
                // classic FSR2 — punt to TAAU on RADV until the FSR 4.1
                // port's transient-image workaround lands.
                if (dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
                    LOGGER.warn("FSR 4.1 path known-unstable on RADV/NAVI33; routing to TAAU");
                    candidate = TaaUpscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = requested.key() + " → TAAU (RADV FSR 4.1 workaround)";
                    }
                } else {
                    candidate = dev.comfyfluffy.caustica.fsr.Fsr41Upscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = requested.key() + " → FSR 4.1 (FFX 4.x modular)";
                    } else {
                        LOGGER.warn("FSR 4.1 shim unavailable; falling back to classic FSR2");
                        candidate = dev.comfyfluffy.caustica.fsr.Fsr2ClassicUpscaler.tryCreate();
                        if (candidate != null) {
                            requestedReason = requested.key() + " → classic FSR2 (FSR 4.1 unavailable)";
                        } else {
                            LOGGER.warn("Classic FSR2 also unavailable; falling back to TAAU");
                            candidate = TaaUpscaler.tryCreate();
                            if (candidate != null) {
                                requestedReason = requested.key() + " → TAAU (no FSR 4.1 or FSR2 native)";
                            }
                        }
                    }
                }
            }
            case FSR2 -> {
                // Mesa RADV + NAVI33: classic FSR2 native dispatch hard-recovers the device on the
                // first real-geometry frame (fixed SQC GPUVM fault) even with pure device-local RT
                // inputs and a full barrier before dispatch. TAAU uses the same RT plates via pure
                // compute and is stable — prefer it until the FSR2 native path is fixed on RADV.
                if (dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
                    LOGGER.warn("Classic FSR2 crashes on RADV/NAVI33 (GPUVM on first dispatch); using TAAU instead");
                    candidate = TaaUpscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = requested.key() + " → TAAU (RADV FSR2 workaround)";
                    }
                } else {
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
