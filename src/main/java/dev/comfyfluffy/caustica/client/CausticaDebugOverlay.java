package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.upscale.Upscaler;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the multi-line text drawn on top of the main render target by {@code InGameHudMixin}. Pure function
 * of the current {@link RtComposite}, selected provider, and {@link RtFrameStats} state — no side effects, no
 * GL/Vulkan calls. Cheap to call every frame; only invoked when {@code CausticaConfig.Rt.DebugOverlay.ENABLED}
 * is true (the mixin gates the call).
 *
 * <p>The output is meant to be readable in a screenshot. Each line is annotated with a hint about which
 * field it is showing, so a single photo can answer "is the denoise on?", "did the upscaler fall back?",
 * "is the denoise temporal history fresh?", and "which frame-stage dominates the frame?". The exact format
 * is intentionally short — full debug logs go to {@code latest.log} via {@link CausticaMod#LOGGER}.
 */
public final class CausticaDebugOverlay {
    private CausticaDebugOverlay() {
    }

    public static List<String> build() {
        List<String> out = new ArrayList<>(12);
        RtComposite c = RtComposite.INSTANCE;
        Upscaler upscaler = UpscalerSelector.current();
        String modeStr = upscaler != null ? upscaler.id() : "?";
        String upscalerRcStr = c.getLastUpscalerOk()
                ? "ok"
                : (c.getLastUpscalerRc() == 0 ? "fallback" : Integer.toString(c.getLastUpscalerRc()));
        // Show *resolved* upscaler (what actually runs), not only the config key.
        String cfgUpscaler = CausticaConfig.Rt.Upscaler.MODE.value().key();
        String pathHint;
        if (c.getLastUpscalerPath()) {
            pathHint = "ran";
        } else if (upscaler != null && upscaler.performsTemporalReconstruction()) {
            pathHint = "fallback-blit";
        } else {
            pathHint = "blit+taa";
        }
        String upscalerInfo = String.format("%s/%s (cfg=%s rc=%s)",
                modeStr, pathHint, cfgUpscaler, upscalerRcStr);
        out.add("§6Caustica RT debug");
        out.add(" §7upscaler: §r" + upscalerInfo);
        out.add(" §7denoise: §r" + CausticaConfig.Rt.Denoise.MODE.get()
                + " §8(" + (c.getLastDenoiseOn()
                ? "§a" + c.getLastDenoisePath()
                : "§7off") + "§8)");
        out.add(" §7NRD prepare: §r" + status(c.getLastNrdPrepareOk()));
        out.add(" §7NRD dispatch: §r" + status(c.getLastNrdDispatchOk()));
        out.add(" §7NRD compose: §r" + status(c.getLastNrdComposeOk()));
        out.add(" §7upscaler evaluate: §r" + status(upscaler != null
                && upscaler.performsTemporalReconstruction() && c.getLastUpscalerPath()));
        out.add(String.format(" §7render: §r%d×%d → %d×%d",
                c.getRenderWidth(), c.getRenderHeight(), c.getDisplayWidth(), c.getDisplayHeight()));
        out.add(String.format(" §7jitter: §r(%+.4f, %+.4f) render px (applied to primary ray)",
                c.getLastJitterPixelsX(), c.getLastJitterPixelsY()));
        // Render/display resolution is private on RtComposite; debugSummary exposes upscale + denoise +
        // render/display res + frame counter + upscaler status. Misleading to label this "geometry" — it's the
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
