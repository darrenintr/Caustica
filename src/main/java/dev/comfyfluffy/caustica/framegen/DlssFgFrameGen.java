package dev.comfyfluffy.caustica.framegen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import org.joml.Matrix4fc;

/**
 * Adapter that exposes the existing {@code RtDlssFg} through the {@link FrameGen} interface. Same
 * pattern as {@code DlssRrUpscaler}: we don't duplicate the lifecycle; we just forward to the
 * existing static accessor until someone migrates the present call sites to call {@link #current()}
 * directly.
 */
public final class DlssFgFrameGen implements FrameGen {
    public static DlssFgFrameGen tryCreate() {
        try {
            if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
                return null;
            }
            // Defer to NgxRuntime for init; the existing RtDlssFg hooks into the shared NGX handle.
            if (NgxRuntime.INSTANCE.acquire(device) == null) {
                return null;
            }
            return new DlssFgFrameGen();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public UpscalerSelector.Mode sourceMode() {
        return UpscalerSelector.Mode.DLSS_RR;
    }

    @Override
    public boolean isReady() {
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.isReady();
    }

    @Override
    public boolean isAvailable() {
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.isAvailable();
    }

    @Override
    public int effectiveMultiFrameCount() {
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.effectiveMultiFrameCount();
    }

    @Override
    public boolean ensureFeature(long cmd, int width, int height, int renderWidth, int renderHeight,
                                int backbufferFormat, int multiFrameCount) {
        // The existing path lazily ensures during probeAvailabilityOnce(); we still let the present
        // path trigger ensureFeature on the active FrameGen as a no-op so the API is uniform.
        return true;
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
        return dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.isReady();
    }

    @Override
    public void destroy() {
        dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.destroy();
    }
}
