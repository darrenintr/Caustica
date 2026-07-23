/*
 * Caustica — RT output format adaptation layer profile.
 * Copyright (c) 2026. Caustica contributors.
 *
 * Format/pipeline contract for the currently active (denoise backend, upscaler) pair.
 * Consumed by {@link RtPlateBridge} to decide whether staging images and conversion
 * compute pipelines need (re)creation, and which per-frame operations are no-ops versus
 * full pack/unpack passes.
 *
 * <p>Phase 2 covers both seams:
 *   raw beauty → denoise backend input (raw → backend-wanted format)
 *   denoise output → upscaler input (backend output → upscaler input format)
 *   upscaler output → display (RGBA16F → B10G11R11 for SDR, identity RGBA16F for HDR-PQ)
 */
package dev.comfyfluffy.caustica.rt.plate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.denoise.CausticaDenoiseBackend;
import dev.comfyfluffy.caustica.upscale.Upscaler;

import java.util.Objects;

/**
 * Per-(denoise,upscaler) format and pipeline contract consumed by {@link RtPlateBridge}.
 *
 * <p>Profiles are value-like records: {@link #equals(Object)} compares every field, so the
 * bridge can detect "no profile change" cheaply.
 *
 * <p>Three seams are described:
 * <ul>
 *   <li><b>Denoise input</b> — raw beauty (B10G11R11) → backend-wanted format. Identity when
 *       the denoise backend already reads raw HDR (Noop / Bilateral / today's HybridFfxNRD
 *       which packs from raw internally).</li>
 *   <li><b>Denoise → upscaler</b> — backend output → upscaler input. Identity when the
 *       backend produces the format the upscaler already wants (no conversion needed).</li>
 *   <li><b>Upscaler output → display</b> — RGBA16F (FSR2 native) → B10G11R11
 *       for SDR, or identity RGBA16F for the HDR-PQ mapper. The blackout guard has
 *       format-matched variants for both destinations.</li>
 * </ul>
 *
 * <p>{@code identity*Pack} / {@code identity*Unpack} tell the bridge whether the corresponding
 * stage is a no-op (vkCmdCopyImage). Everything else is a real pack/unpack shader dispatch.
 */
public final class RtPlateProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    /** Format the path tracer writes today (HDR radiance plate). */
    public final int rawBeautyFormat;

    /** Format the denoise backend wants as input. Common cases: raw HDR (Noop/Bilateral), RGBA16F (NRD's prep). */
    public final int denoiseInputFormat;
    /** Format the denoise backend writes. raw HDR for passthrough; RGBA16F for HybridFfxNRD's compose. */
    public final int denoiseOutputFormat;
    /** True when {@code rawBeautyFormat == denoiseInputFormat}; the bridge's input adapter becomes a copy. */
    public final boolean denoiseIdentityPack;
    /**
     * True when {@code denoiseOutputFormat == upscalerInputFormat}; the bridge's post-denoise pack
     * becomes a copy (i.e. the upscaler can read the denoise output staging directly).
     */
    public final boolean denoiseIdentityUnpack;

    /** Format the upscaler SDK expects as input. RGBA16F for FSR-style temporal upscalers. */
    public final int upscalerInputFormat;
    /** Format the SDK writes into the bridge's output staging (== input format for FSR). */
    public final int upscalerOutputFormat;
    /** Format the caller's display-side destination expects to receive. */
    public final int displayFormat;

    /** True when {@code rawBeautyFormat == upscalerInputFormat}; the bridge's per-frame pack is a copy. */
    public final boolean identityPack;
    /** True when {@code upscalerOutputFormat == displayFormat}; the bridge's unpack is a copy. */
    public final boolean identityUnpack;

    /** SDK wants a self-derived R32F reactive mask. */
    public final boolean needsReactiveMask;
    /** FSR2-style blackout fail-open: pure-black/NaN FSR output → nearest upsample of input. */
    public final boolean needsBlackoutGuard;

    private RtPlateProfile(Builder b) {
        this.rawBeautyFormat = b.rawBeautyFormat;
        this.denoiseInputFormat = b.denoiseInputFormat;
        this.denoiseOutputFormat = b.denoiseOutputFormat;
        this.denoiseIdentityPack = b.denoiseIdentityPack;
        this.denoiseIdentityUnpack = b.denoiseIdentityUnpack;
        this.upscalerInputFormat = b.upscalerInputFormat;
        this.upscalerOutputFormat = b.upscalerOutputFormat;
        this.displayFormat = b.displayFormat;
        this.identityPack = b.identityPack;
        this.identityUnpack = b.identityUnpack;
        this.needsReactiveMask = b.needsReactiveMask;
        this.needsBlackoutGuard = b.needsBlackoutGuard;
    }

    /**
     * Build the profile for the active (denoise, upscale) pair.
     *
     * <p>Formats and optional bridge passes come from provider capabilities rather than
     * implementation classes or selector enum values. The only renderer-owned policy here is
     * the final display plate: SDR uses the raw HDR format and HDR-PQ keeps RGBA16F until
     * the display mapper performs its PQ encode.
     */
    public static RtPlateProfile resolve(int rawBeautyFormat,
                                         CausticaDenoiseBackend denoise,
                                         Upscaler upscale) {
        int upscalerInFmt = upscale != null
                ? upscale.inputColorFormat(rawBeautyFormat)
                : rawBeautyFormat;
        int upscalerOutFmt = upscale != null
                ? upscale.outputColorFormat(rawBeautyFormat)
                : upscalerInFmt;
        int denoiseInFmt = denoise != null
                ? denoise.inputColorFormat(rawBeautyFormat)
                : rawBeautyFormat;
        int denoiseOutFmt = denoise != null
                ? denoise.outputColorFormat(rawBeautyFormat)
                : rawBeautyFormat;
        boolean hdrEnabled = CausticaConfig.Rt.Hdr.enabled();
        int displayFmt = upscale != null
                ? upscale.displayColorFormat(rawBeautyFormat, hdrEnabled)
                : rawBeautyFormat;

        Builder b = new Builder();
        b.rawBeautyFormat = rawBeautyFormat;
        b.denoiseInputFormat = denoiseInFmt;
        b.denoiseOutputFormat = denoiseOutFmt;
        b.denoiseIdentityPack = (denoiseInFmt == rawBeautyFormat);
        b.denoiseIdentityUnpack = (denoiseOutFmt == upscalerInFmt);
        b.upscalerInputFormat = upscalerInFmt;
        b.upscalerOutputFormat = upscalerOutFmt;
        b.displayFormat = displayFmt;
        b.identityPack = (upscalerInFmt == rawBeautyFormat);
        b.identityUnpack = (b.upscalerOutputFormat == displayFmt);
        b.needsReactiveMask = upscale != null && upscale.needsReactiveMask();
        b.needsBlackoutGuard = upscale != null && upscale.needsBlackoutGuard();
        RtPlateProfile p = b.build();
        LOGGER.info("RtPlateProfile.resolve: denoise={}, up={}, rawFmt=0x{}, denoiseInFmt=0x{}, "
                        + "denoiseOutFmt=0x{}, upscaleInFmt=0x{}, idDenoisePack={}, idDenoiseUnpack={}, "
                        + "displayFmt=0x{}, idPack={}, idUnpack={}, reactive={}, guard={}",
                (denoise != null ? denoise.name() : "null"),
                (upscale != null ? upscale.id() : null),
                Integer.toHexString(rawBeautyFormat),
                Integer.toHexString(denoiseInFmt),
                Integer.toHexString(denoiseOutFmt),
                Integer.toHexString(upscalerInFmt),
                p.denoiseIdentityPack, p.denoiseIdentityUnpack,
                Integer.toHexString(displayFmt),
                p.identityPack, p.identityUnpack, p.needsReactiveMask, p.needsBlackoutGuard);
        return p;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int rawBeautyFormat;
        private int denoiseInputFormat;
        private int denoiseOutputFormat;
        private boolean denoiseIdentityPack;
        private boolean denoiseIdentityUnpack;
        private int upscalerInputFormat;
        private int upscalerOutputFormat;
        private int displayFormat;
        private boolean identityPack;
        private boolean identityUnpack;
        private boolean needsReactiveMask;
        private boolean needsBlackoutGuard;

        public Builder rawBeautyFormat(int v) { this.rawBeautyFormat = v; return this; }
        public Builder denoiseInputFormat(int v) { this.denoiseInputFormat = v; return this; }
        public Builder denoiseOutputFormat(int v) { this.denoiseOutputFormat = v; return this; }
        public Builder denoiseIdentityPack(boolean v) { this.denoiseIdentityPack = v; return this; }
        public Builder denoiseIdentityUnpack(boolean v) { this.denoiseIdentityUnpack = v; return this; }
        public Builder upscalerInputFormat(int v) { this.upscalerInputFormat = v; return this; }
        public Builder upscalerOutputFormat(int v) { this.upscalerOutputFormat = v; return this; }
        public Builder displayFormat(int v) { this.displayFormat = v; return this; }
        public Builder identityPack(boolean v) { this.identityPack = v; return this; }
        public Builder identityUnpack(boolean v) { this.identityUnpack = v; return this; }
        public Builder needsReactiveMask(boolean v) { this.needsReactiveMask = v; return this; }
        public Builder needsBlackoutGuard(boolean v) { this.needsBlackoutGuard = v; return this; }

        public RtPlateProfile build() {
            return new RtPlateProfile(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RtPlateProfile p)) {
            return false;
        }
        return rawBeautyFormat == p.rawBeautyFormat
                && denoiseInputFormat == p.denoiseInputFormat
                && denoiseOutputFormat == p.denoiseOutputFormat
                && denoiseIdentityPack == p.denoiseIdentityPack
                && denoiseIdentityUnpack == p.denoiseIdentityUnpack
                && upscalerInputFormat == p.upscalerInputFormat
                && upscalerOutputFormat == p.upscalerOutputFormat
                && displayFormat == p.displayFormat
                && identityPack == p.identityPack
                && identityUnpack == p.identityUnpack
                && needsReactiveMask == p.needsReactiveMask
                && needsBlackoutGuard == p.needsBlackoutGuard;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawBeautyFormat, denoiseInputFormat, denoiseOutputFormat,
                denoiseIdentityPack, denoiseIdentityUnpack,
                upscalerInputFormat, upscalerOutputFormat, displayFormat,
                identityPack, identityUnpack, needsReactiveMask, needsBlackoutGuard);
    }

    @Override
    public String toString() {
        return "RtPlateProfile{"
                + "raw=0x" + Integer.toHexString(rawBeautyFormat)
                + ", dnIn=0x" + Integer.toHexString(denoiseInputFormat)
                + ", dnOut=0x" + Integer.toHexString(denoiseOutputFormat)
                + ", upIn=0x" + Integer.toHexString(upscalerInputFormat)
                + ", upOut=0x" + Integer.toHexString(upscalerOutputFormat)
                + ", display=0x" + Integer.toHexString(displayFormat)
                + ", idDnPack=" + denoiseIdentityPack + ", idDnUnpack=" + denoiseIdentityUnpack
                + ", idPack=" + identityPack + ", idUnpack=" + identityUnpack
                + ", reactive=" + needsReactiveMask + ", guard=" + needsBlackoutGuard
                + "}";
    }
}
