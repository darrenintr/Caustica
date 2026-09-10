package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.vulkan.VK10;

/**
 * ReSTIR PT Enhanced §5 duplication map (Lin, Kettunen, Wyman, I3D 2026).
 *
 * Holds two R8 UNORM render-resolution ping-pong images. Each pixel stores the local duplication
 * density — the fraction of the surrounding 17×17 window that shares the receiver's
 * Variance-Reduction (V2) seed. A pixel whose neighbourhood is mostly V2-identical reuses the
 * previous frame's temporal sample more aggressively (higher {@code M_2} cap); a pixel in a
 * heterogeneous neighbourhood caps reuse more tightly to avoid pulling wrong history.
 *
 * Bindings (per docs/superpowers/specs/2026-07-29-restir-pt-enhanced.md §2):
 *   gDupMapCurr   — binding 30, R8 UNORM rw, render-res.
 *   gDupMapPrev   — binding 31, R8 UNORM ro, render-res.
 *
 * The {@code curr → prev} swap is done at end of {@code composite()} so the next frame reads the
 * map that was current during *this* frame's temporal merge. There is a one-frame lag (matches
 * the lag of the reservoir ping-pong), which is what the temporal merge formula expects.
 */
public final class DuplicationMap {

    private RtImage curr;
    private RtImage prev;

    /** Lazily (re)allocate the two ping-pong images at the render resolution. Idempotent. */
    public void ensureSized(RtContext ctx, int width, int height) {
        if (curr != null && prev != null
                && curr.width == width && curr.height == height) {
            return;
        }
        destroy();
        curr = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8_UNORM,
                "dup-map-curr");
        prev = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8_UNORM,
                "dup-map-prev");
    }

    /** Rotate curr → prev. Called once per frame from {@code composite()}. */
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
