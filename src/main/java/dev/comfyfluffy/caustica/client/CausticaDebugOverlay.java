package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the multi-line text drawn on top of the main render target by {@code InGameHudMixin}. Pure function
 * of the current {@link RtComposite} + {@link RtFrameStats} + {@link RtDlssRr} state — no side effects, no
 * GL/Vulkan calls. Cheap to call every frame; only invoked when {@code CausticaConfig.Rt.DebugOverlay.ENABLED}
 * is true (the mixin gates the call).
 *
 * <p>The output is meant to be readable in a screenshot. Each line is annotated with a hint about which
 * field it is showing, so a single photo can answer "is the denoise on?", "did DLSS-RR throw and why?",
 * "is the denoise temporal history fresh?", and "which frame-stage dominates the frame?". The exact format
 * is intentionally short — full debug logs go to {@code latest.log} via {@link CausticaMod#LOGGER}.
 */
public final class CausticaDebugOverlay {
    private CausticaDebugOverlay() {
    }

    public static List<String> build() {
        List<String> out = new ArrayList<>(12);
        RtComposite c = RtComposite.INSTANCE;
        UpscalerSelector.Mode mode = UpscalerSelector.resolvedMode();
        String modeStr = mode == null ? "?" : mode.key();
        String dlssRcStr = c.getLastRrOk()
                ? "ok"
                : (c.getLastRrRc() == 0 ? "fallback" : String.format("0x%X", c.getLastRrRc()));
        // Show *resolved* upscaler (what actually runs), not only the config key.
        String cfgUpscaler = CausticaConfig.Rt.Upscaler.MODE.value().key();
        String pathHint;
        if (c.getLastUpscalerPath()) {
            pathHint = "ran";
        } else if (mode == UpscalerSelector.Mode.TAAU
                || mode == UpscalerSelector.Mode.DLSS_RR
                || mode == UpscalerSelector.Mode.FSR_3
                || mode == UpscalerSelector.Mode.FSR_4
                || mode == UpscalerSelector.Mode.XESS) {
            pathHint = "fallback-blit";
        } else {
            pathHint = "blit+taa";
        }
        String rrInfo = String.format("%s/%s (cfg=%s rc=%s)",
                modeStr, pathHint, cfgUpscaler, dlssRcStr);
        if (mode == UpscalerSelector.Mode.DLSS_RR && RtDlssRr.INSTANCE.evaluateFailureCount() > 0) {
            rrInfo += String.format("  failures=%d", RtDlssRr.INSTANCE.evaluateFailureCount());
        }
        out.add("§6Caustica RT debug");
        out.add(" §7upscaler: §r" + rrInfo);
        out.add(" §7denoise: §r" + CausticaConfig.Rt.Denoise.MODE.get()
                + " §8(" + (c.getLastDenoiseOn()
                ? "§a" + c.getLastDenoisePath()
                : "§7off") + "§8)");
        out.add(" §7NRD prepare: §r" + status(c.getLastNrdPrepareOk()));
        out.add(" §7NRD dispatch: §r" + status(c.getLastNrdDispatchOk()));
        out.add(" §7NRD compose: §r" + status(c.getLastNrdComposeOk()));
        out.add(" §7TAAU evaluate: §r" + status(mode == UpscalerSelector.Mode.TAAU
                && c.getLastUpscalerPath()));
        out.add(String.format(" §7render: §r%d×%d → %d×%d",
                c.getRenderWidth(), c.getRenderHeight(), c.getDisplayWidth(), c.getDisplayHeight()));
        out.add(String.format(" §7jitter: §r(%+.4f, %+.4f) render px (applied to primary ray)",
                c.getLastJitterPixelsX(), c.getLastJitterPixelsY()));
        // Render/display resolution is private on RtComposite; debugSummary exposes upscale + denoise +
        // render/display res + frame counter + RR status. Misleading to label this "geometry" — it's the
        // composite state, not the geometry pass.
        out.add(" §7composite: §r" + c.debugSummary());
        // Frame-stage timing — only meaningful when frame-stats is on, but reading is safe when it's off.
        long traceMs = RtFrameStats.FRAME.stageTotalMs("frame.trace");
        long upMs = RtFrameStats.FRAME.stageTotalMs("frame.upscale");
        out.add(" §7stages: §rtrace=" + traceMs + "ms upscale=" + upMs + "ms");
        return out;
    }

    private static String status(boolean ok) {
        return ok ? "§aok" : "§cfailed/not-run";
    }
}
