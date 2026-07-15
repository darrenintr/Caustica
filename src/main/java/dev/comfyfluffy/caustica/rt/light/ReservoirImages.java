package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

/**
 * Ping-pong reservoir images for ReSTIR temporal reuse.
 * Each pixel stores a packed uvec4 reservoir (128-bit).
 * Simplified version using RtContext's existing image creation.
 */
public final class ReservoirImages {

    private RtImage current;
    private RtImage previous;

    public void ensureSized(RtContext ctx, int width, int height) {
        if (current != null && current.width == width && current.height == height) {
            return;
        }
        destroy();

        // RGBA32UI format for uvec4 storage
        current = ctx.createStorageImage(width, height,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_UINT, "reservoir-current");
        previous = ctx.createStorageImage(width, height,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_UINT, "reservoir-previous");

        // Note: Images are created in GENERAL layout by RtContext, no need to transition
        // They will be cleared to 0 on first use (empty reservoir with lightIndex=0xFFFFFFFF)
    }

    /**
     * Swap current ↔ previous for next frame's temporal reuse.
     */
    public void swap() {
        RtImage temp = current;
        current = previous;
        previous = temp;
    }

    public void destroy() {
        if (current != null) {
            current.destroy();
            current = null;
        }
        if (previous != null) {
            previous.destroy();
            previous = null;
        }
    }

    public RtImage current() {
        return current;
    }

    public RtImage previous() {
        return previous;
    }
}
