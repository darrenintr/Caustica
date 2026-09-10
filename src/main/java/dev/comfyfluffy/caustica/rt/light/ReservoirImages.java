package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

/**
 * Ping-pong reservoir images for ReSTIR temporal reuse.
 * Each pixel stores a packed uvec4 reservoir (128-bit).
 * Simplified version using RtContext's existing image creation.
 *
 * <p>VRAM-first layout (2026-09): when {@code QUARTER_RES_RESERVOIR} is on, the
 * images are allocated at half width/height (1 reservoir per 2x2 tile = 1/4 the
 * pixels). The raygen maps {@code pix -> pix/2} on every reservoir/hitpos
 * access (see rgen_restir_di.glsl), so neighbours inside a tile share one
 * reservoir — a fit for Minecraft's large flat surfaces.
 */
public final class ReservoirImages {

    private RtImage current;
    private RtImage previous;
    private int storedW = -1;
    private int storedH = -1;

    /** Quarter-res tile shift: 1 = half width/height (2x2 tile shares 1 reservoir). */
    public static final int TILE_SHIFT = 1;

    public static int tiledSize(int full, boolean quarterRes) {
        return quarterRes ? Math.max(1, (full + 1) >> TILE_SHIFT) : full;
    }

    public void ensureSized(RtContext ctx, int width, int height) {
        ensureSized(ctx, width, height, false);
    }

    public void ensureSized(RtContext ctx, int width, int height, boolean quarterRes) {
        int w = tiledSize(width, quarterRes);
        int h = tiledSize(height, quarterRes);
        if (current != null && storedW == w && storedH == h) {
            return;
        }
        destroy();

        // RGBA32UI format for uvec4 storage
        current = ctx.createStorageImage(w, h,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_UINT, "reservoir-current");
        previous = ctx.createStorageImage(w, h,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_UINT, "reservoir-previous");
        storedW = w;
        storedH = h;

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
