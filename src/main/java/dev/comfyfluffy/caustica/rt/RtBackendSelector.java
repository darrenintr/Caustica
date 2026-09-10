package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.nativebridge.NativeRenderer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

public final class RtBackendSelector {
    private RtBackendSelector() {
    }

    public static boolean recordFirefly(RtContext ctx, long commandBuffer, long frameId,
                                        RtImage input, RtImage output) {
        if (CausticaConfig.Rt.Renderer.BACKEND.value() == CausticaConfig.RendererBackend.JAVA) {
            return false;
        }
        return NativeRenderer.INSTANCE.recordFirefly(ctx, commandBuffer, frameId, input, output);
    }

    /** Run the native frame graph over the requested pass mask. Returns true if every requested pass
     *  succeeded; otherwise the Java caller should fall back to its existing implementation for those
     *  passes in the same frame. */
    public static boolean recordFrame(RtContext ctx, long commandBuffer, long frameId,
                                      int renderW, int renderH, int displayW, int displayH,
                                      int passMask, RtImage radiance, RtImage fireflyKilled,
                                      RtImage denoisedColor, RtImage displayImage, RtImage rrOutput,
                                      RtImage mainTarget) {
        if (CausticaConfig.Rt.Renderer.BACKEND.value() == CausticaConfig.RendererBackend.JAVA) {
            return false;
        }
        return NativeRenderer.INSTANCE.recordFrame(ctx, commandBuffer, frameId, renderW, renderH,
                displayW, displayH, passMask, radiance, fireflyKilled, denoisedColor, displayImage,
                rrOutput, mainTarget);
    }

    public static void resize(int width, int height) {
        NativeRenderer.INSTANCE.resize(width, height);
    }

    public static void beginReload() {
        NativeRenderer.INSTANCE.beginReload();
    }

    public static void endReload() {
        NativeRenderer.INSTANCE.endReload();
    }

    /** Notify the native backend that the caller is about to drop all device-local GPU resources
     *  (F3+A, dimension change, world exit). Forces the native side to release the current
     *  generation's pending resources so subsequent Java {@code RtBuffer.destroy()} / {@code
     *  RtImage.destroy()} calls don't have to drain the entire set manually. */
    public static void releaseGeneration() {
        NativeRenderer.INSTANCE.beginReload();
        NativeRenderer.INSTANCE.endReload();
    }

    public static void invalidateHistory() {
        NativeRenderer.INSTANCE.invalidateHistory();
    }

    public static void shutdown() {
        NativeRenderer.INSTANCE.destroy();
    }
}
