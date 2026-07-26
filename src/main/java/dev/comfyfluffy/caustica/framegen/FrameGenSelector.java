package dev.comfyfluffy.caustica.framegen;

import dev.comfyfluffy.caustica.upscale.Upscaler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the single active frame-generation provider for the current device/session.
 *
 * <p>Unsupported devices, disabled configuration, and incompatible upscaler contracts resolve to
 * {@link FrameGen#NOOP}; presentation and client lifecycle code therefore never need vendor-specific
 * branches. Additional providers can be added here without changing those call sites.
 */
public final class FrameGenSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    private static volatile FrameGen active = FrameGen.NOOP;
    private static volatile String sourceUpscalerId = "off";

    private FrameGenSelector() {
    }

    public static FrameGen current() {
        return active;
    }

    public static String sourceUpscalerId() {
        return sourceUpscalerId;
    }

    /** Drop the current provider so the next composite pass can resolve against fresh configuration. */
    public static synchronized void invalidate() {
        setActive(FrameGen.NOOP, "off");
    }

    /** Select a provider whose input contract matches the resolved upscaler output. */
    public static synchronized FrameGen resolve(Upscaler source) {
        FrameGen candidate = VulkanMotionFrameGen.INSTANCE.isEnabled()
                ? VulkanMotionFrameGen.INSTANCE
                : FrameGen.NOOP;
        String sourceId = source != null ? source.id() : "off";

        setActive(candidate, candidate == FrameGen.NOOP ? "off" : sourceId);
        if (candidate == FrameGen.NOOP) {
            LOGGER.info("Frame generation disabled by configuration");
        } else {
            candidate.probeAvailabilityOnce();
            LOGGER.info("Frame generation provider selected: {} (source={})",
                    candidate.name(), sourceId);
        }
        return candidate;
    }

    /** Probe the selected provider without exposing it to client lifecycle code. */
    public static void probeAvailabilityOnce() {
        FrameGen provider = active;
        if (provider.isEnabled()) {
            provider.probeAvailabilityOnce();
        }
    }

    /** Release the selected provider and reset to the universal no-op fallback. */
    public static synchronized void shutdown() {
        setActive(FrameGen.NOOP, "off");
    }

    private static void setActive(FrameGen next, String sourceId) {
        FrameGen previous = active;
        active = next;
        sourceUpscalerId = sourceId;
        if (previous != null && previous != next && previous != FrameGen.NOOP) {
            try {
                previous.destroy();
            } catch (Throwable t) {
                LOGGER.warn("Frame generation shutdown failed for {}", previous.name(), t);
            }
        }
    }
}
