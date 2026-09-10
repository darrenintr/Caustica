package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.vulkan.VK10;

/**
 * Per-pixel world-space hit position image, ping-pong, RGBA32F (vec3 + 1 unused).
 *
 * <p>ReSTIR PT Enhanced §4 footprint-based reconnection (Lin, Kettunen, Wyman, I3D 2026)
 * requires the previous frame's hit position to compute the projection radius
 * {@code Rp = sqrt(|Δx|² · cosθ / 4π)}. The DI reservoir is 16 B packed (1× uvec4) and
 * has no spare room for an extra vec3, so we store the hit position in a parallel image
 * with the same lifetime + ping-pong cadence as {@link ReservoirImages}.
 *
 * <p>Format: {@code VK_FORMAT_R32G32B32A32_FLOAT} (xyz = hit position in terrain-rebased
 * space, w = 0; the extra channel is dropped on read with {@code .xyz}). Same size as
 * a GI reservoir image so adding it to the world pipeline's descriptor set does not
 * push the layout counts across any device limit.
 *
 * <p>Bindings (per docs/superpowers/specs/2026-07-29-restir-pt-enhanced.md §2 + slot plan
 * in {@code RtComposite}):
 * <ul>
 *   <li>binding 29 = {@code gHitPosCurr} — written by world.rgen at every DI reservoir
 *       write site</li>
 *   <li>binding 30 = {@code gHitPosPrev} — read by the temporal merge's footprint test</li>
 * </ul>
 *
 * <p>The swap is done at the end of {@code RtComposite.composite()} so the next frame's
 * temporal merge reads this frame's hit positions via {@code prev}.
 */
public final class ReservoirHitPosImages {

    private RtImage curr;
    private RtImage prev;
    private int storedW = -1;
    private int storedH = -1;

    /** Lazily allocate both ping-pong images at render resolution. Idempotent. */
    public void ensureSized(RtContext ctx, int width, int height) {
        ensureSized(ctx, width, height, false);
    }

    /** Quarter-res variant: half width/height, 1 hit-pos per 2x2 tile (matches ReservoirImages). */
    public void ensureSized(RtContext ctx, int width, int height, boolean quarterRes) {
        int w = ReservoirImages.tiledSize(width, quarterRes);
        int h = ReservoirImages.tiledSize(height, quarterRes);
        if (curr != null && prev != null && storedW == w && storedH == h) {
            return;
        }
        destroy();
        curr = ctx.createStorageImage(w, h,
                VK10.VK_FORMAT_R32G32B32A32_SFLOAT, "reservoir-hitpos-curr");
        prev = ctx.createStorageImage(w, h,
                VK10.VK_FORMAT_R32G32B32A32_SFLOAT, "reservoir-hitpos-prev");
        storedW = w;
        storedH = h;
    }

    /** Rotate curr → prev. One frame of lag, matching the reservoir ping-pong cadence. */
    public void swap() {
        RtImage tmp = curr;
        curr = prev;
        prev = tmp;
    }

    public RtImage curr() {
        return curr;
    }

    public RtImage prev() {
        return prev;
    }

    public void destroy() {
        if (curr != null) {
            curr.destroy();
            curr = null;
        }
        if (prev != null) {
            prev.destroy();
            prev = null;
        }
    }
}
