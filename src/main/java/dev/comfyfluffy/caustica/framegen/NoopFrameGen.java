package dev.comfyfluffy.caustica.framegen;

import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import org.joml.Matrix4fc;

/**
 * No-op frame gen. {@link #interpolate} returns false to signal "duplicate the real frame for this
 * one" — the present path then falls back to a plain blit, which preserves display order without
 * dropping the extra presents on a tick where no generated frame is available.
 */
public final class NoopFrameGen implements FrameGen {
    public static final NoopFrameGen INSTANCE = new NoopFrameGen();

    private NoopFrameGen() {
    }

    @Override
    public UpscalerSelector.Mode sourceMode() {
        return UpscalerSelector.Mode.OFF;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public int effectiveMultiFrameCount() {
        return 0;
    }

    @Override
    public boolean ensureFeature(long cmd, int width, int height, int renderWidth, int renderHeight,
                                int backbufferFormat, int multiFrameCount) {
        return false;
    }

    @Override
    public boolean interpolate(long cmd, long colorView, long colorImage, int colorFormat,
                               long depthView, long depthImage, int depthFormat,
                               long mvView, long mvImage, int mvFormat,
                               long hudlessView, long hudlessImage, int hudlessFormat,
                               long uiView, long uiImage, int uiFormat,
                               long outView, long outImage, int outFormat,
                               long prevColorView, long prevColorImage, int prevColorFormat,
                               int width, int height, int index, int multiFrameCount,
                               boolean hdrBackbuffer, Matrix4fc viewToClip, Matrix4fc clipToView,
                               Matrix4fc clipToPrevClip, Matrix4fc prevClipToClip) {
        return false;
    }

    @Override
    public void destroy() {
    }
}
