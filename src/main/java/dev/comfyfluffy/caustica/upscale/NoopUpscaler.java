package dev.comfyfluffy.caustica.upscale;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upscaler that does nothing — passthrough 1:1. The RT composite's caller falls back to a linear blit when
 * {@code Upscaler.isReady()} is false on this upscaler; this class exists so the rest of the code can
 * uniformly call {@code upscaler.evaluate(...)} without null-checks. {@link #ensureFeature} is a no-op
 * that returns true so the caller considers the feature "ready" and proceeds to the blit.
 */
public final class NoopUpscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    public static final NoopUpscaler INSTANCE = new NoopUpscaler();

    private boolean warned;

    private NoopUpscaler() {
    }

    @Override
    public Mode mode() {
        return Mode.OFF;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        return new int[] { displayWidth, displayHeight };
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                int quality, int featureFlags) {
        return true;
    }

    @Override
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage specularHitDistance, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!warned) {
            warned = true;
            CausticaMod.LOGGER.info("Upscaler is OFF; RT composite will fall back to a 1:1 blit (no denoise / upscale).");
        }
        return false;
    }

    @Override
    public void destroy() {
    }
}
