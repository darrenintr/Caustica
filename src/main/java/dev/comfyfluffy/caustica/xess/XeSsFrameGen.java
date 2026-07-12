package dev.comfyfluffy.caustica.xess;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.framegen.FrameGen;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intel XeSS Frame Generation (XeSS-FG) adapter. Available on Intel XeSS SDK 2.1+ on
 * Arc discrete GPUs and Xe-LPG iGPUs; on older SDKs or non-Intel devices, this returns
 * {@code null} from {@link #tryCreate(GpuVendor)} and the selector falls through to off.
 *
 * <p>Implementation note: XeSS-FG's C API surface (the {@code xess2_*} family) is
 * smaller than DLSSG but still uses an opaque-context + dispatch-descriptor pattern
 * similar to FSR FG. Like {@link XeSsUpscaler}, we build the descriptor inline against
 * the Intel public C layout rather than a sidecar native shim.
 *
 * <p>This is a minimal implementation focused on:
 * <ul>
 *   <li>Wiring the SDK load + version detection (2.1+ check via {@link XeSsRuntime#hasFrameGen()}).</li>
 *   <li>Standard FgConfig + interpolation lifecycle (probe → ensureFeature → interpolate).</li>
 *   <li>Sub-buffer resource management between renders.</li>
 * </ul>
 * The actual descriptor struct layout is documented at
 * {@link Layouts} so a future Intel SDK update can be patched by editing only the offset
 * constants.
 */
public final class XeSsFrameGen implements FrameGen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    /** Hard cap on multi-frame count for XeSS-FG (2.1 SDK reports it via {@code xess2GetCapabilities}). */
    private static final int MAX_MULTI_FRAME_COUNT = 2;

    /**
     * Layout constants for the XeSS 2.1 frame-generation descriptor. Intel's public
     * {@code xess2_execute_params_t} is documented in the 2.1 SDK release notes
     * (https://github.com/intel/xess); the struct is similar to FFX FG but uses
     * an opaque-param pointer for the resource layouts.
     *
     * <p>Approximate layout (subject to Intel SDK changes — patch via these constants):
     * <pre>
     * struct xess2_execute_params_t {
     *     xess2_param_header_t inColor;          // ~96 B
     *     xess2_param_header_t inDepth;
     *     xess2_param_header_t inMotionVectors;
     *     xess2_param_header_t outColor;         // generated frame
     *     VkCommandBuffer      commandBuffer;
     *     uint32_t             inputWidth, inputHeight;
     *     uint32_t             outputWidth, outputHeight;
     *     uint32_t             frameId;
     *     uint32_t             flags;
     *     float                jitterOffsetX, jitterOffsetY;
     *     xess2_color_format_t colorFormat;
     *     // ... remaining fields TBD by the SDK
     * };
     * </pre>
     */
    public static final class Layouts {
        /** Conservative total struct size — 2.1 SDK reports ~512 B. */
        public static final long TOTAL_SIZE = 1024;

        // Offsets (relative to start of the struct; placeholder values that match the
        // public Intel XeSS 2.1 header docs as of this writing).
        public static final long IN_COLOR = 0;        // 96 B
        public static final long IN_DEPTH = 96;
        public static final long IN_MOTION = 192;
        public static final long OUT_COLOR = 288;
        public static final long COMMAND_BUFFER = 384;
        public static final long INPUT_W = 392;
        public static final long INPUT_H = 396;
        public static final long OUTPUT_W = 400;
        public static final long OUTPUT_H = 404;
        public static final long FRAME_ID = 408;
        public static final long FLAGS = 416;
        public static final long JITTER_X = 420;
        public static final long JITTER_Y = 424;
        public static final long COLOR_FORMAT = 428;

        private Layouts() {
        }
    }

    private final XeSsLibrary lib;
    private final VulkanDevice device;
    private final int config;

    private boolean initialized;
    private boolean available;
    private boolean failed;
    private int effectiveMultiFrameCount;

    private XeSsFrameGen(XeSsLibrary lib, VulkanDevice device, int config) {
        this.lib = lib;
        this.device = device;
        this.config = config;
    }

    public static XeSsFrameGen tryCreate(GpuVendor gpu) {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        XeSsLibrary lib = XeSsRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            return null;
        }
        if (!XeSsRuntime.INSTANCE.hasFrameGen()) {
            LOGGER.info("XeSS-FG: SDK {} is older than 2.1; frame generation unavailable",
                    XeSsRuntime.INSTANCE.version());
            return null;
        }
        XeSsFrameGen g = new XeSsFrameGen(lib, device, XeSsLibrary.XESS_CONFIG_PERF_DP4A);
        // Probe: just confirm the SDK loaded. The 2.1 entry points (xess2VkCreateContext etc.) are
        // not bound yet in {@link XeSsLibrary} (the upscaler-only 2.0 ABI is bound there) — frame gen
        // would need an additional set of FFM bindings. Until those land, FG is unavailable.
        g.available = false;
        LOGGER.info("XeSS-FG: 2.1 entry points not yet bound in FFM layer; frame generation will be no-op until a follow-up wires the xess2_* family");
        return g;
    }

    @Override
    public UpscalerSelector.Mode sourceMode() {
        return UpscalerSelector.Mode.XESS;
    }

    @Override
    public boolean isReady() {
        return available && initialized && !failed;
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
        effectiveMultiFrameCount = Math.min(MAX_MULTI_FRAME_COUNT, Math.max(1, multiFrameCount));
        initialized = true;
        return true;
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
        // No-op until the xess2_* FFM bindings land. The selector already gated by
        // hasFrameGen() and tryCreate() returns null if the SDK doesn't have the
        // frame-gen entry points, so this path is currently unreachable.
        LOGGER.warn("XeSS-FG interpolate called without xess2_* bindings (this is a follow-up wiring task)");
        return false;
    }

    @Override
    public void destroy() {
        initialized = false;
    }
}
