package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.vulkan.VK10;

/**
 * Paired spatial-reuse textures for ReSTIR PT Enhanced (Lin, Kettunen, Wyman §3, I3D 2026).
 *
 * Holds three 512×512 R16G16 UI "pair table" textures (one U-flipped) plus a 512×512 R32 UI shuffle
 * table. The pair table is generated once on the GPU at startup by
 * {@code shaders/world/paired_reuse_generator.comp} (random walk Gaussian offset over 254 candidate
 * neighbours; the compute pass writes each image with a different seed so the three textures are
 * independent samples of the unit ring). The shuffle table is regenerated only on the
 * user-configured shuffle period (default 4 frames).
 *
 * Bindings used (per docs/superpowers/specs/2026-07-29-restir-pt-enhanced.md §2):
 *   gPairedReuseTex0..2 — bindings 27..29, point sampler, no linear filter, wrap UV.
 *   gPairedShuffle      — binding 30 (consumed by the per-frame dispatch index).
 *
 * This class is intentionally cheap: it never participates in the dispatch graph directly; the
 * shuffle index is read from {@link #shuffleIndex()} in the push constants uploaded each frame.
 */
public final class PairedReuseTextures {

    /** Pair-table edge length. Paper §3.2 confirms 512 is enough for any reasonable render res. */
    public static final int TABLE_SIZE = 512;

    /**
     * Number of distinct candidate neighbours per pixel. The paper recommends ≈254 to keep the
     * table bandwidth-compatible with ReSTIR spatial reuse, while leaving headroom for mirrors.
     */
    public static final int PAIRS = 254;

    private RtImage tex0;
    private RtImage tex1;
    private RtImage tex2;
    private RtImage shuffle;

    /** Current shuffle seed; flips every {@code shuffle-period} frames. */
    private int shuffleIndex = 0;

    /**
     * Lazily create the three pair tables + shuffle image at 512×512 (fixed — the compute pass
     * handles tiling via wraparound UVs). Idempotent.
     */
    public void ensureSized(RtContext ctx) {
        if (tex0 != null && tex1 != null && tex2 != null && shuffle != null) {
            return;
        }
        destroy();

        tex0 = ctx.createStorageImage(TABLE_SIZE, TABLE_SIZE,
                VK10.VK_FORMAT_R16G16_UINT, "paired-reuse-tex0");
        tex1 = ctx.createStorageImage(TABLE_SIZE, TABLE_SIZE,
                VK10.VK_FORMAT_R16G16_UINT, "paired-reuse-tex1");
        tex2 = ctx.createStorageImage(TABLE_SIZE, TABLE_SIZE,
                VK10.VK_FORMAT_R16G16_UINT, "paired-reuse-tex2");
        shuffle = ctx.createStorageImage(TABLE_SIZE, TABLE_SIZE,
                VK10.VK_FORMAT_R32_UINT, "paired-reuse-shuffle");
    }

    /** Bump the shuffle seed. Cheap — actual image rewrite is done by the compute pass. */
    public void advanceShuffle(int frameIndex, int period) {
        int periodClamped = Math.max(1, period);
        shuffleIndex = (frameIndex / periodClamped) % PAIRS;
    }

    public int shuffleIndex() {
        return shuffleIndex;
    }

    public RtImage tex0() {
        return tex0;
    }

    public RtImage tex1() {
        return tex1;
    }

    public RtImage tex2() {
        return tex2;
    }

    public RtImage shuffle() {
        return shuffle;
    }

    public void destroy() {
        if (tex0 != null) {
            tex0.destroy();
            tex0 = null;
        }
        if (tex1 != null) {
            tex1.destroy();
            tex1 = null;
        }
        if (tex2 != null) {
            tex2.destroy();
            tex2 = null;
        }
        if (shuffle != null) {
            shuffle.destroy();
            shuffle = null;
        }
    }
}