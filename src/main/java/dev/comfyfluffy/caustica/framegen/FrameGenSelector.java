package dev.comfyfluffy.caustica.framegen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.fsr.FsrFrameGen;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import dev.comfyfluffy.caustica.xess.XeSsFrameGen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects the active {@link FrameGen} from the upscaler + GPU vendor. Independent of
 * {@code UpscalerSelector} (FSR 4 with FSR 3 frame gen, or vice versa, is a valid mix on some devices,
 * but the upscaler picker here defaults to "match the active upscaler" since that gives the best
 * quality/feature parity).
 */
public final class FrameGenSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    public enum Mode {
        OFF("off"),
        AUTO("auto"),
        DLSSG("dlssg"),
        FSR_3("fsr-3"),
        FSR_4("fsr-4"),
        XESS("xess");

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

    private static volatile FrameGen active;
    private static volatile Mode resolvedMode;

    private FrameGenSelector() {
    }

    public static synchronized FrameGen resolve(GpuVendor gpu, UpscalerSelector.Mode activeUpscaler) {
        Mode requested = Mode.fromKey(CausticaConfig.Rt.FrameGen.MODE.value().key());
        String reason = requested.key;
        FrameGen candidate = null;
        switch (requested) {
            case OFF -> {
                resolvedMode = Mode.OFF;
                return setActive(NoopFrameGen.INSTANCE);
            }
            case DLSSG -> candidate = DlssFgFrameGen.tryCreate();
            case FSR_3 -> candidate = FsrFrameGen.tryCreateFsr3(gpu);
            case FSR_4 -> candidate = FsrFrameGen.tryCreateFsr4(gpu);
            case XESS -> candidate = XeSsFrameGen.tryCreate(gpu);
            case AUTO -> {
                // Match the active upscaler — best feature parity (same SDK, same config UI).
                if (activeUpscaler == UpscalerSelector.Mode.DLSS_RR) {
                    candidate = DlssFgFrameGen.tryCreate();
                    reason = "auto: matches DLSS-RR → DLSSG";
                } else if (activeUpscaler == UpscalerSelector.Mode.FSR_4) {
                    candidate = FsrFrameGen.tryCreateFsr4(gpu);
                    reason = "auto: matches FSR 4 → FSR 4 FG";
                } else if (activeUpscaler == UpscalerSelector.Mode.FSR_3) {
                    candidate = FsrFrameGen.tryCreateFsr3(gpu);
                    reason = "auto: matches FSR 3 → FSR 3 FG";
                } else if (activeUpscaler == UpscalerSelector.Mode.XESS) {
                    candidate = XeSsFrameGen.tryCreate(gpu);
                    reason = "auto: matches XeSS → XeSS-FG";
                }
            }
        }
        if (candidate == null || !candidate.isAvailable()) {
            LOGGER.warn("Requested frame gen mode '{}' unavailable; falling back to off", requested.key);
            resolvedMode = Mode.OFF;
            return setActive(NoopFrameGen.INSTANCE);
        }
        resolvedMode = requested;
        LOGGER.info("Frame gen selected: {} ({})", resolvedMode.key, reason);
        return setActive(candidate);
    }

    public static FrameGen current() {
        return active != null ? active : NoopFrameGen.INSTANCE;
    }

    public static Mode resolvedMode() {
        return resolvedMode != null ? resolvedMode : Mode.OFF;
    }

    public static synchronized void shutdown() {
        if (active != null && active != NoopFrameGen.INSTANCE) {
            try {
                active.destroy();
            } catch (Throwable t) {
                LOGGER.warn("Frame gen shutdown failed for {}", active.sourceMode().key(), t);
            }
        }
        active = null;
        resolvedMode = null;
    }

    private static FrameGen setActive(FrameGen g) {
        active = g;
        return g;
    }
}
