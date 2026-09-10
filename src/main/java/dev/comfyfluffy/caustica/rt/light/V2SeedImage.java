package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.vulkan.VK10;

/**
 * ReSTIR PT Enhanced §5 V2-identifier seed image (Lin, Kettunen, Wyman, I3D 2026).
 *
 * Holds two R32 UI render-resolution ping-pong images. The {@code v2_seed_init.comp} compute
 * pass fills {@code curr} each frame; the {@code duplication_map.comp} pass then reads
 * {@code prev} (which holds last frame's seeds). After the temporal merge consumes the dup
 * map, both ping-pong pairs rotate (curr → prev).
 *
 * Bindings:
 *   gV2SeedCurr — R32 UI render-res (written by seed_init).
 *   gV2SeedPrev — R32 UI render-res (read by duplication_map).
 */
public final class V2SeedImage {

    private RtImage curr;
    private RtImage prev;

    public void ensureSized(RtContext ctx, int width, int height) {
        if (curr != null && prev != null
                && curr.width == width && curr.height == height) {
            return;
        }
        destroy();
        curr = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_UINT,
                "v2-seed-curr");
        prev = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_UINT,
                "v2-seed-prev");
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
