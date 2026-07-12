package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.upscale.Upscaler;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * AMD FidelityFX Super Resolution (FSR 3 or FSR 4) upscaler, adapted to the {@link Upscaler} interface.
 * Dispatches via the FFX C API (SDK 2.1+): one opaque context per FSR backend (upscaler), per-frame
 * {@code ffxDispatch} with an {@code ffxApiDispatchDescUpscaleVulkan} descriptor, and {@code ffxQuery} to
 * discover the optimal render size per quality.
 *
 * <p>The FFX upscaler only needs color + depth + motion vectors — the {@code diffuseAlbedo},
 * {@code specularAlbedo}, {@code normals}, {@code specularMotion}, {@code specularHitDistance} parameters
 * of {@link Upscaler#evaluate} are DLSS-RR-only and are passed to this upscaler as {@code null}, which we
 * silently ignore. The path tracer still emits them every frame (the GPU cost is small); only DLSS-RR
 * consumes them.
 *
 * <p>FSR 4.1 INT8 vs FSR 3 selection: the AMD modular loader exposes both models in the
 * {@code amd_fidelityfx_upscaler.dll} content (or ships them as separate DLLs depending on the bundle
 * version); the loader picks the right one per device at context creation. We pre-probe the loaded patch
 * via {@link FsrRuntime#loadedUpscalerPatch()} so the user can see which model is active.
 */
public final class FsrUpscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    /**
     * FFX API quality modes (from {@code ffx_api.h} / {@code ffx_upscale.h}). Native ints — we marshal
     * directly to FFX's enum.
     */
    public static final int FFX_UPSCALE_QUALITY_MODE_NATIVE = 0;
    public static final int FFX_UPSCALE_QUALITY_MODE_QUALITY = 1;
    public static final int FFX_UPSCALE_QUALITY_MODE_BALANCED = 2;
    public static final int FFX_UPSCALE_QUALITY_MODE_PERFORMANCE = 3;
    public static final int FFX_UPSCALE_QUALITY_MODE_ULTRA_PERFORMANCE = 4;

    /**
     * FFX upscale creation flags (bit set in {@code ffxCreateContextDescUpscale.flags}). FSR 4 needs
     * {@code FFX_UPSCALER_FLAG_RELAXED_HDR_PQ} when upscaling HDR10 PQ content; SDR doesn't need it.
     * FSR 4.1 INT8 path on RDNA 3 doesn't need any special flag — the loader picks the INT8 model.
     */
    public static final int FFX_UPSCALE_FLAG_NONE = 0;
    public static final int FFX_UPSCALE_FLAG_RELAXED_HDR_PQ = 1 << 0;

    private final FsrLibrary lib;
    private final VulkanDevice device;
    private final boolean isFsr4;
    private final int contextFlags;

    /** Opaque FFX context — backed by native memory; treat as a handle. */
    private MemorySegment context = MemorySegment.NULL;
    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;
    private boolean ready;
    private boolean failed;

    private FsrUpscaler(FsrLibrary lib, VulkanDevice device, boolean isFsr4, int contextFlags) {
        this.lib = lib;
        this.device = device;
        this.isFsr4 = isFsr4;
        this.contextFlags = contextFlags;
    }

    /**
     * Try to initialise an FSR 3 upscaler on the given device. Returns null if the SDK didn't load.
     * For "FSR 3 mode but the loader actually has FSR 4" — we still succeed but log a warning (the
     * resulting upscale is FSR 4 quality, which is strictly better).
     */
    public static FsrUpscaler tryCreateFsr3(GpuVendor gpu) {
        return tryCreate(gpu, /*forceFsr3=*/true);
    }

    /** Try to initialise an FSR 4 (or FSR 4.1 INT8) upscaler. Returns null if SDK didn't load or device
     *  doesn't support FSR 4. */
    public static FsrUpscaler tryCreateFsr4(GpuVendor gpu) {
        if (!gpu.canRunFsr41()) {
            LOGGER.warn("FSR 4 requested but the device ({}) does not support it; falling back to FSR 3",
                    gpu.deviceName);
            return null;
        }
        return tryCreate(gpu, /*forceFsr3=*/false);
    }

    private static FsrUpscaler tryCreate(GpuVendor gpu, boolean forceFsr3) {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        FsrLibrary lib = FsrRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            return null;
        }
        boolean isFsr4 = !forceFsr3 && FsrRuntime.INSTANCE.isFsr4Loaded();
        // First-frame dispatch probe: ask the runtime to create a no-op upscaler context just to confirm
        // the device can actually run FSR (some FSR 4 features are RDNA-4-only and will fail on RDNA 3).
        // On probe failure, fall back to the opposite model.
        FsrUpscaler u = new FsrUpscaler(lib, device, isFsr4, FFX_UPSCALE_FLAG_NONE);
        if (!u.probeCreate()) {
            if (isFsr4) {
                LOGGER.warn("FSR 4 create-context probe failed on {}; retrying with FSR 3", gpu.deviceName);
                return tryCreate(gpu, /*forceFsr3=*/true);
            }
            return null;
        }
        return u;
    }

    /** Quick "can I create a context?" probe — creates and immediately destroys a throwaway context. */
    private boolean probeCreate() {
        if (RtContext.get() == null) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctxOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment desc = FsrDescriptors.buildCreateContextDescUpscaleVulkan(arena, this, /*probe=*/true);
            int rc = lib.createContext(ctxOut, desc, MemorySegment.NULL);
            if (rc != 0) {
                return false;
            }
            MemorySegment probeCtx = ctxOut.get(ValueLayout.ADDRESS, 0);
            if (!probeCtx.equals(MemorySegment.NULL)) {
                lib.destroyContext(probeCtx, MemorySegment.NULL);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("FSR upscaler probe failed", t);
            return false;
        }
    }

    @Override
    public Mode mode() {
        return isFsr4 ? Mode.FSR_4 : Mode.FSR_3;
    }

    @Override
    public boolean isReady() {
        return ready && !failed && !context.equals(MemorySegment.NULL);
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!ready) {
            return new int[] { displayWidth, displayHeight };
        }
        // FSR 3 / FSR 4 use stable per-quality ratios; FSR doesn't have a runtime queryOptimal but does
        // document the expected scales. We hardcode the standard ratios; FSR 4 INT8 path uses the same
        // input grid. (FSR 4.0+ supports additional modes that change the scale, but the published
        // public ratios are stable across 3.x and 4.x.)
        int quality = CausticaConfig.Rt.Upscaler.QUALITY.value();
        float scale = switch (quality) {
            case FFX_UPSCALE_QUALITY_MODE_NATIVE -> 1.0f;
            case FFX_UPSCALE_QUALITY_MODE_QUALITY -> 1.0f / 1.5f;
            case FFX_UPSCALE_QUALITY_MODE_BALANCED -> 1.0f / 1.7f;
            case FFX_UPSCALE_QUALITY_MODE_PERFORMANCE -> 1.0f / 2.0f;
            case FFX_UPSCALE_QUALITY_MODE_ULTRA_PERFORMANCE -> 1.0f / 3.0f;
            default -> 1.0f / 1.5f;
        };
        int w = Math.max(1, Math.round(displayWidth * scale));
        int h = Math.max(1, Math.round(displayHeight * scale));
        return new int[] { w, h };
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                int quality, int featureFlags) {
        if (failed) {
            return false;
        }
        int effectiveQuality = quality >= 0 ? quality : FFX_UPSCALE_QUALITY_MODE_QUALITY;
        if (ready && !context.equals(MemorySegment.NULL)
                && renderWidth == featureRenderWidth && renderHeight == featureRenderHeight
                && displayWidth == featureDisplayWidth && displayHeight == featureDisplayHeight
                && effectiveQuality == featureQuality) {
            return true;
        }
        try {
            // (Re)create the context if the size or quality changed.
            if (!context.equals(MemorySegment.NULL)) {
                lib.destroyContext(context, MemorySegment.NULL);
                context = MemorySegment.NULL;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ctxOut = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment desc = FsrDescriptors.buildCreateContextDescUpscaleVulkan(arena, this, /*probe=*/false);
                int rc = lib.createContext(ctxOut, desc, MemorySegment.NULL);
                if (rc != 0) {
                    throw new IllegalStateException("ffxCreateContext(upscaler) failed: " + rc);
                }
                context = ctxOut.get(ValueLayout.ADDRESS, 0);
                if (context.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("ffxCreateContext returned a null context");
                }
            }
            featureRenderWidth = renderWidth;
            featureRenderHeight = renderHeight;
            featureDisplayWidth = displayWidth;
            featureDisplayHeight = displayHeight;
            featureQuality = effectiveQuality;
            ready = true;
            LOGGER.info("FSR {} upscaler context created: {}x{} -> {}x{} (quality={}, model={})",
                    isFsr4 ? "4" : "3", renderWidth, renderHeight, displayWidth, displayHeight,
                    effectiveQuality, isFsr4 ? "ML/INT8" : "spatial-temporal");
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR {} upscaler create failed; disabling", isFsr4 ? "4" : "3", t);
            return false;
        }
    }

    @Override
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage specularHitDistance, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!isReady() || color == null || depth == null || motion == null || out == null) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = FsrDescriptors.buildDispatchDescUpscaleVulkan(arena, this, cmd,
                    color, depth, motion, out,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    jitterX, jitterY);
            int rc = lib.dispatch(context, desc);
            if (rc != 0) {
                throw new IllegalStateException("ffxDispatch(upscaler) failed: " + rc);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR {} evaluate failed; disabling upscaler", isFsr4 ? "4" : "3", t);
            return false;
        }
    }

    @Override
    public void destroy() {
        if (!context.equals(MemorySegment.NULL)) {
            try {
                lib.destroyContext(context, MemorySegment.NULL);
            } catch (Throwable t) {
                LOGGER.warn("ffxDestroyContext(upscaler) failed", t);
            }
            context = MemorySegment.NULL;
        }
        ready = false;
    }

    // ---- Accessors used by FsrDescriptors ----

    public VulkanDevice device() { return device; }
    public boolean isFsr4() { return isFsr4; }
    public int contextFlags() { return contextFlags; }
    public int featureRenderWidth() { return featureRenderWidth; }
    public int featureRenderHeight() { return featureRenderHeight; }
    public int featureDisplayWidth() { return featureDisplayWidth; }
    public int featureDisplayHeight() { return featureDisplayHeight; }
    public int featureQuality() { return featureQuality; }
}
