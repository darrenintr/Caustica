package dev.comfyfluffy.caustica.upscale;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.fsr.Fsr2ClassicUpscaler;
import dev.comfyfluffy.caustica.fsr.FsrUpscaler;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import dev.comfyfluffy.caustica.xess.XeSsUpscaler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects the active {@link Upscaler} at session start based on the user-requested mode (config) and the
 * detected GPU vendor. Falls back to the most capable available upscaler on the current device when the
 * configured mode is incompatible, and to a 1:1 "none" upscaler (no-op upscale) when no upscaler SDK
 * initialised.
 *
 * <p>Selection rules (in order of preference, applied per device):
 * <ol>
 *   <li>User forced a specific mode in config → use it if its runtime initialised; otherwise warn and fall
 *       through.</li>
 *   <li>User set {@code AUTO} → pick the best mode the device supports, in this order:
 *       RDNA 3/4 → FSR 4.1 INT8; RDNA 2 / older AMD / Intel Arc → FSR 4.1 if available else FSR 3;
 *       any vendor → XeSS DP4a; NVIDIA → DLSS-RR; finally NONE.</li>
 *   <li>Forced off → NONE.</li>
 * </ol>
 */
public final class UpscalerSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    public enum Mode {
        OFF("off"),
        AUTO("auto"),
        DLSS_RR("dlss-rr"),
        FSR_3("fsr-3"),
        FSR_4("fsr-4"),
        XESS("xess"),
        NIS("nis");

        final String key;
        Mode(String key) { this.key = key; }

        public String key() { return key; }

        public static Mode fromKey(String key) {
            if (key == null) {
                return AUTO;
            }
            for (Mode m : values()) {
                if (m.key.equalsIgnoreCase(key) || m.name().equalsIgnoreCase(key)) {
                    return m;
                }
            }
            return AUTO;
        }
    }

    private static volatile Upscaler active;
    private static volatile Mode resolvedMode;
    private static volatile GpuVendor cachedGpu;

    private UpscalerSelector() {
    }

    /**
     * Resolve the active upscaler. Call after each device init; on hot-reload of config call again to
     * re-pick. Returns the (possibly shared) singleton — a single {@link Upscaler} is alive at a time.
     */
    public static synchronized Upscaler resolve(GpuVendor gpu) {
        cachedGpu = gpu;
        return resolve0(gpu);
    }

    /**
     * Resolve the active upscaler, auto-detecting the GPU vendor if not cached. The first call probes
     * the physical device via {@code vkGetPhysicalDeviceProperties2} (cached on {@link com.mojang.blaze3d.vulkan.VulkanDevice});
     * subsequent calls reuse the cached value. Returns the singleton upscaler.
     */
    public static synchronized Upscaler resolve() {
        if (cachedGpu == null) {
            cachedGpu = detectGpu();
        }
        return resolve0(cachedGpu);
    }

    private static GpuVendor detectGpu() {
        return GpuVendor.detect();
    }

    private static Upscaler resolve0(GpuVendor gpu) {
        Mode requested = CausticaConfig.Rt.Upscaler.MODE.valueEnum();
        Upscaler candidate = null;
        String requestedReason = requested.key;
        switch (requested) {
            case OFF -> {
                resolvedMode = Mode.OFF;
                return setActive(NoopUpscaler.INSTANCE);
            }
            case DLSS_RR -> candidate = DlssRrUpscaler.tryCreate();
            case FSR_3 -> {
                candidate = FsrUpscaler.tryCreateFsr3(gpu);
                if (candidate == null) {
                    candidate = Fsr2ClassicUpscaler.tryCreate(gpu);
                    if (candidate != null) {
                        requestedReason = "fsr-3 → classic FSR2 (Vulkan)";
                    }
                }
            }
            case FSR_4 -> {
                candidate = FsrUpscaler.tryCreateFsr4(gpu);
                if (candidate == null) {
                    candidate = Fsr2ClassicUpscaler.tryCreate(gpu);
                    if (candidate != null) {
                        requestedReason = "fsr-4 unavailable → classic FSR2 (Vulkan)";
                    }
                }
            }
            case XESS -> candidate = XeSsUpscaler.tryCreate();
            case NIS -> candidate = NisUpscaler.tryCreate();
            case AUTO -> {
                // Best-mode picker.
                if (gpu.canRunDlss()) {
                    candidate = DlssRrUpscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = "auto: NVIDIA → DLSS-RR";
                    }
                }
                if (candidate == null && gpu.canRunFsr41()) {
                    candidate = FsrUpscaler.tryCreateFsr4(gpu);
                    if (candidate != null) {
                        requestedReason = "auto: AMD RDNA 3/4 → FSR 4.1";
                    }
                }
                if (candidate == null && gpu.canRunFsr3()) {
                    candidate = FsrUpscaler.tryCreateFsr3(gpu);
                    if (candidate != null) {
                        requestedReason = "auto: → modular FSR 3";
                    }
                }
                // Linux Vulkan: modular FSR often has no provider — classic FSR2 is the real path.
                if (candidate == null) {
                    candidate = Fsr2ClassicUpscaler.tryCreate(gpu);
                    if (candidate != null) {
                        requestedReason = "auto: → classic FSR2 (Vulkan)";
                    }
                }
                if (candidate == null && gpu.canRunXeSs()) {
                    candidate = XeSsUpscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = "auto: → XeSS";
                    }
                }
                // Final fallback: NIS (always works, pure shader)
                if (candidate == null) {
                    candidate = NisUpscaler.tryCreate();
                    if (candidate != null) {
                        requestedReason = "auto: → NIS (cross-vendor fallback)";
                    }
                }
            }
        }
        if (candidate == null) {
            LOGGER.warn("Requested upscaler mode '{}' did not initialise on this device; falling back to none.",
                    requested.key);
            resolvedMode = Mode.OFF;
            return setActive(NoopUpscaler.INSTANCE);
        }
        resolvedMode = candidate.mode();
        LOGGER.info("Upscaler selected: {} ({})", resolvedMode.key, requestedReason);
        return setActive(candidate);
    }

    /**
     * Resolve a no-op upscaler (e.g. when the RT context isn't ready yet). Does not log.
     */
    public static Upscaler none() {
        resolvedMode = Mode.OFF;
        return setActive(NoopUpscaler.INSTANCE);
    }

    public static Upscaler current() {
        return active != null ? active : NoopUpscaler.INSTANCE;
    }

    public static Mode resolvedMode() {
        return resolvedMode != null ? resolvedMode : Mode.OFF;
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
                LOGGER.warn("Upscaler shutdown failed for {}", active.mode().key, t);
            }
        }
        active = null;
        resolvedMode = null;
    }
}
