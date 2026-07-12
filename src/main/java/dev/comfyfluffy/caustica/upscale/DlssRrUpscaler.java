package dev.comfyfluffy.caustica.upscale;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around the existing {@code RtDlssRr} that adapts its 9-input contract to the
 * {@link Upscaler} interface. The existing class is kept for now (mixin code paths still reference it
 * directly) and this adapter calls into it; once all call sites are migrated, {@code RtDlssRr} can be
 * folded into this class.
 */
public final class DlssRrUpscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    public static DlssRrUpscaler tryCreate() {
        // Defer to the existing static accessor — the real NGX init lives there, and we don't want to
        // double up on lifecycle. Until someone migrates the call sites, this is the source of truth.
        if (!NgxRuntime.INSTANCE.isInitialized()) {
            // We can still try to initialise here so the upscaler selector can pre-probe the device.
            try {
                com.mojang.blaze3d.systems.RenderSystem.getDevice(); // ensures device exists
            } catch (Throwable t) {
                return null;
            }
        }
        return new DlssRrUpscaler();
    }

    @Override
    public Mode mode() {
        return Mode.DLSS_RR;
    }

    @Override
    public boolean isReady() {
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.isReady();
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.queryOptimalRenderSize(displayWidth, displayHeight);
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                int quality, int featureFlags) {
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.ensureFeature(cmd, renderWidth, renderHeight, displayWidth, displayHeight);
    }

    @Override
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage specularHitDistance, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.evaluate(cmd, color, depth, motion,
                diffuseAlbedo, specularAlbedo, normals, specularMotion, out,
                renderWidth, renderHeight, displayWidth, displayHeight,
                jitterX, jitterY, worldToView, viewToClip);
    }

    @Override
    public void destroy() {
        dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.destroy();
    }
}
