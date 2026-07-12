package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.framegen.FrameGen;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * AMD FSR Frame Generation backend (FSR 3 or FSR 4 — same descriptor layout, different
 * internal model). Shares the FFX loader with {@link FsrUpscaler}; one FFX context per FSR
 * feature (the upscaler context and the FG context are independent — they can be created /
 * destroyed in any order).
 *
 * <p>The FSR FG feature requires:
 * <ul>
 *   <li>A swapchain-bound backbuffer of known format (FFX writes its generated frames at
 *       swapchain size).</li>
 *   <li>A current backbuffer with the just-rendered content + a previous backbuffer (FFX
 *       interpolates between them).</li>
 *   <li>Depth + motion vectors at the render resolution (FFX uses them to drive its own
 *       internal optical-flow + reprojection).</li>
 *   <li>The view→clip / clip→view / clip→prev-clip / prev-clip→clip matrix chain that
 *       Minecraft's frame-pacing does not provide natively — the {@code RtFramePresenter}
 *       captures the same matrices that drive the upscaler's reprojection and forwards
 *       them here.</li>
 * </ul>
 *
 * <p>FSR 4 FG uses the same FFX descriptor structs as FSR 3 FG; the loader picks the right
 * model per device at context creation. We don't differentiate in the Java side — if the
 * upscaler selected FSR 4, this FG also runs on the FSR 4 model; otherwise FSR 3.
 */
public final class FsrFrameGen implements FrameGen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    /** FSR 3 / FSR 4 use a hard cap of 4 generated frames per real frame in the 2.1 SDK. */
    private static final int MAX_MULTI_FRAME_COUNT = 4;

    private final FsrLibrary lib;
    private final VulkanDevice device;
    private final boolean isFsr4;
    private final UpscalerSelector.Mode sourceUpscaler;
    private final int contextFlags;

    private MemorySegment context = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean available;

    private int featureWidth = -1;
    private int featureHeight = -1;
    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureBackbufferFormat = Integer.MIN_VALUE;
    private int effectiveMultiFrameCount;

    private FsrFrameGen(FsrLibrary lib, VulkanDevice device, boolean isFsr4,
                        UpscalerSelector.Mode sourceUpscaler, int contextFlags) {
        this.lib = lib;
        this.device = device;
        this.isFsr4 = isFsr4;
        this.sourceUpscaler = sourceUpscaler;
        this.contextFlags = contextFlags;
    }

    /**
     * Try to create the FSR 3 FG context. Returns null if the SDK didn't load or the device
     * doesn't support FG (FSR 3 requires RDNA 2+ on AMD and SM 6.4+ on NVIDIA — the loader
     * performs the device check, and a fail here just means the runtime rejected the
     * context).
     */
    public static FsrFrameGen tryCreateFsr3(GpuVendor gpu) {
        return tryCreate(gpu, /*forceFsr3=*/true);
    }

    /** Try to create the FSR 4 FG context. FSR 4 FG requires RDNA 3+; on RDNA 2 / older we
     *  silently fall back to FSR 3. */
    public static FsrFrameGen tryCreateFsr4(GpuVendor gpu) {
        if (gpu != null && !gpu.canRunFsr41()) {
            LOGGER.warn("FSR 4 FG requested but the device ({}) does not support it; falling back to FSR 3 FG",
                    gpu.deviceName);
        }
        return tryCreate(gpu, /*forceFsr3=*/false);
    }

    private static FsrFrameGen tryCreate(GpuVendor gpu, boolean forceFsr3) {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        FsrLibrary lib = FsrRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            return null;
        }
        boolean isFsr4 = !forceFsr3 && FsrRuntime.INSTANCE.isFsr4Loaded();
        // Probe: try to create and immediately destroy a tiny context just to confirm FG works.
        // Real dimensions are provided later in {@link #ensureFeature}.
        FsrFrameGen g = new FsrFrameGen(lib, device, isFsr4,
                isFsr4 ? UpscalerSelector.Mode.FSR_4 : UpscalerSelector.Mode.FSR_3, 0);
        g.probe();
        if (!g.isAvailable()) {
            if (isFsr4) {
                LOGGER.warn("FSR 4 FG probe failed on {}; retrying with FSR 3 FG", gpu != null ? gpu.deviceName : "?");
                return tryCreate(gpu, /*forceFsr3=*/true);
            }
            return null;
        }
        return g;
    }

    private void probe() {
        if (available || failed) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            // Small probe: 64x64 RGBA8 SDR backbuffer at the same size for the depth/MV.
            // The probe is just to confirm the SDK accepts a context on this device; we throw
            // the context away immediately.
            MemorySegment ctxOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment desc = FsrFgDescriptors.buildCreateContextDescFrameGeneration(
                    arena, /*displayW=*/64, /*displayH=*/64, /*renderW=*/64, /*renderH=*/64,
                    /*allowHudless=*/false, /*allowAsyncCompute=*/false, /*hdr=*/false);
            int rc = lib.createContext(ctxOut, desc, MemorySegment.NULL);
            if (rc != 0) {
                LOGGER.warn("FSR FG context probe failed: rc={}", rc);
                failed = true;
                return;
            }
            MemorySegment probeCtx = ctxOut.get(ValueLayout.ADDRESS, 0);
            if (!probeCtx.equals(MemorySegment.NULL)) {
                lib.destroyContext(probeCtx, MemorySegment.NULL);
            }
            available = true;
            LOGGER.info("FSR {} Frame Generation probe OK", isFsr4 ? "4" : "3");
        } catch (Throwable t) {
            LOGGER.warn("FSR FG probe failed", t);
            failed = true;
        }
    }

    @Override
    public UpscalerSelector.Mode sourceMode() {
        return sourceUpscaler;
    }

    @Override
    public boolean isReady() {
        return available && initialized && !failed && !context.equals(MemorySegment.NULL);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public int effectiveMultiFrameCount() {
        return effectiveMultiFrameCount;
    }

    @Override
    public boolean ensureFeature(long cmd, int width, int height, int renderWidth, int renderHeight,
                                 int backbufferFormat, int multiFrameCount) {
        if (failed || !available) {
            return false;
        }
        int requestedMulti = Math.max(1, multiFrameCount);
        int effective = Math.min(MAX_MULTI_FRAME_COUNT, requestedMulti);
        if (initialized && !context.equals(MemorySegment.NULL)
                && width == featureWidth && height == featureHeight
                && renderWidth == featureRenderWidth && renderHeight == featureRenderHeight
                && backbufferFormat == featureBackbufferFormat
                && effective == effectiveMultiFrameCount) {
            return true;
        }
        try {
            if (!context.equals(MemorySegment.NULL)) {
                lib.destroyContext(context, MemorySegment.NULL);
                context = MemorySegment.NULL;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ctxOut = arena.allocate(ValueLayout.ADDRESS);
                boolean hdr = isHdrFormat(backbufferFormat);
                MemorySegment desc = FsrFgDescriptors.buildCreateContextDescFrameGeneration(
                        arena, width, height, renderWidth, renderHeight,
                        /*allowHudless=*/false, /*allowAsyncCompute=*/true, hdr);
                int rc = lib.createContext(ctxOut, desc, MemorySegment.NULL);
                if (rc != 0) {
                    throw new IllegalStateException("ffxCreateContext(FG) failed: " + rc);
                }
                context = ctxOut.get(ValueLayout.ADDRESS, 0);
                if (context.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("ffxCreateContext returned a null FG context");
                }
            }
            featureWidth = width;
            featureHeight = height;
            featureRenderWidth = renderWidth;
            featureRenderHeight = renderHeight;
            featureBackbufferFormat = backbufferFormat;
            effectiveMultiFrameCount = effective;
            initialized = true;
            LOGGER.info("FSR {} FG context created: display={}x{} render={}x{} format=0x{} multi={}",
                    isFsr4 ? "4" : "3", width, height, renderWidth, renderHeight,
                    Integer.toHexString(backbufferFormat), effective);
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR {} FG create failed; disabling FG", isFsr4 ? "4" : "3", t);
            return false;
        }
    }

    @Override
    public boolean interpolate(long cmd,
                               long colorView, long colorImage, int colorFormat,
                               long depthView, long depthImage, int depthFormat,
                               long mvView, long mvImage, int mvFormat,
                               long hudlessView, long hudlessImage, int hudlessFormat,
                               long uiView, long uiImage, int uiFormat,
                               long outView, long outImage, int outFormat,
                               long prevColorView, long prevColorImage, int prevColorFormat,
                               int width, int height, int index, int multiFrameCount,
                               boolean hdrBackbuffer,
                               Matrix4fc viewToClip, Matrix4fc clipToView,
                               Matrix4fc clipToPrevClip, Matrix4fc prevClipToClip) {
        if (!isReady() || outImage == 0L) {
            return false;
        }
        if (!ensureFeature(cmd, width, height, featureRenderWidth, featureRenderHeight, colorFormat, multiFrameCount)) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            // Caustica's frame pacing: each generated frame is identified by the global frame counter
            // at the time the real frame is presented. FFX uses this for its internal history tagging.
            long frameId = dev.comfyfluffy.caustica.rt.RtComposite.frameCounter();
            MemorySegment desc = FsrFgDescriptors.buildDispatchDescFrameGeneration(
                    arena, colorImage, colorFormat,
                    depthImage, depthFormat, mvImage, mvFormat,
                    hudlessImage, hudlessFormat,
                    prevColorImage, prevColorFormat,
                    /*prevHudless=*/0L, /*prevHudlessFormat=*/0,
                    outImage, outFormat,
                    featureRenderWidth, featureRenderHeight, width, height,
                    index, effectiveMultiFrameCount,
                    frameId, /*reset=*/index == 1, hdrBackbuffer,
                    viewToClip, clipToView, clipToPrevClip, prevClipToClip);
            int rc = lib.dispatch(context, desc);
            if (rc != 0) {
                throw new IllegalStateException("ffxDispatch(FG) failed: " + rc);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR {} FG interpolate failed; disabling FG", isFsr4 ? "4" : "3", t);
            return false;
        }
    }

    @Override
    public void destroy() {
        if (!context.equals(MemorySegment.NULL)) {
            try {
                lib.destroyContext(context, MemorySegment.NULL);
            } catch (Throwable t) {
                LOGGER.warn("ffxDestroyContext(FG) failed", t);
            }
            context = MemorySegment.NULL;
        }
        initialized = false;
    }

    private static boolean isHdrFormat(int vkFormat) {
        // ST.2084 / HDR10 backbuffers. Caustica's RT uses R16G16B16A16_SFLOAT as the PQ-encoded
        // backbuffer; other HDR formats (e.g. R10G10B10A2_UNORM with HDR color space) are also
        // possible. We don't need to distinguish them here — FFX's HIGH_HDR flag is set when the
        // backbuffer is in an HDR color space, which Caustica's surface is.
        // For safety we treat any format with bits > 8 as a candidate for HDR (the caller passes
        // the actual backbuffer format, so a SDR R8G8B8A8 backbuffer won't see this flag set).
        return vkFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                || vkFormat == VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    }
}
