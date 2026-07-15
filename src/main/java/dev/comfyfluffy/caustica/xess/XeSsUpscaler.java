package dev.comfyfluffy.caustica.xess;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.upscale.Upscaler;
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
 * Intel XeSS upscaler adapter. Drives the XeSS 2.0+ Vulkan API
 * ({@code xessVkCreateContext} / {@code xessSetConfig} / {@code xessVkExecute}). The
 * actual descriptor struct ({@code xess_vk_execute_params_t}) is built inline here
 * against the Intel public C layout — the XeSS SDK is small and stable enough that
 * one struct definition in Java is preferable to dragging in a sidecar native shim
 * (the FSR / DLSS shims exist because the upstream SDKs are large and the Caustica-side
 * structs are intricate; XeSS is much smaller).
 *
 * <p>Input contract: like FSR — color + depth + motion vectors, and the SDK ignores the
 * DLSS-RR-only guide buffers (diffuseAlbedo, specularAlbedo, normals, etc.) which the
 * path tracer still writes.
 */
public final class XeSsUpscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    /** Layout constants for {@code xess_vk_image_view_info_t} and
     *  {@code xess_vk_execute_params_t} (Intel XeSS SDK 2.0+, x64 C default alignment). */
    public static final class Layouts {
        // xess_vk_image_view_info_t: 40 B, align 8
        public static final long IVI_SIZE = 40;
        public static final long IVI_IMAGE = 0;
        public static final long IVI_IMAGE_VIEW = 8;
        public static final long IVI_IMAGE_LAYOUT = 16;
        public static final long IVI_ACCESS = 24;
        public static final long IVI_FORMAT = 32;

        // xess_vk_execute_params_t: 344 B, align 8
        public static final long XEP_SIZE = 344;
        public static final long XEP_COMMAND_BUFFER = 0;
        public static final long XEP_IN_COLOR = 8;
        public static final long XEP_IN_DEPTH = 48;
        public static final long XEP_IN_MOTION = 88;
        public static final long XEP_IN_EXPOSURE = 128;
        public static final long XEP_IN_REACTIVE = 168;
        public static final long XEP_IN_TRANSPARENCY = 208;
        public static final long XEP_OUT_COLOR = 248;
        public static final long XEP_INPUT_W = 288;
        public static final long XEP_INPUT_H = 292;
        public static final long XEP_OUTPUT_W = 296;
        public static final long XEP_OUTPUT_H = 300;
        public static final long XEP_QUALITY = 304;
        public static final long XEP_INPUT_FORMAT = 312;
        public static final long XEP_OUTPUT_FORMAT = 316;
        public static final long XEP_JITTER_X = 320;
        public static final long XEP_JITTER_Y = 324;
        public static final long XEP_INPUT_SCALE_X = 328;
        public static final long XEP_INPUT_SCALE_Y = 332;
        public static final long XEP_EXPOSURE_SCALE = 336;
        public static final long XEP_VELOCITY_SCALE = 340;

        private Layouts() {
        }
    }

    private final XeSsLibrary lib;
    private final VulkanDevice device;
    private final int config;
    private final int contextFlags;

    private MemorySegment context = MemorySegment.NULL;
    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;
    private boolean ready;
    private boolean failed;

    private XeSsUpscaler(XeSsLibrary lib, VulkanDevice device, int config, int contextFlags) {
        this.lib = lib;
        this.device = device;
        this.config = config;
        this.contextFlags = contextFlags;
    }

    public static XeSsUpscaler tryCreate() {
        return tryCreate(null);
    }

    public static XeSsUpscaler tryCreate(GpuVendor gpu) {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        XeSsLibrary lib = XeSsRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            return null;
        }
        XeSsUpscaler u = new XeSsUpscaler(lib, device, /*config=*/XeSsLibrary.XESS_CONFIG_PERF_DP4A, 0);
        if (!u.probe()) {
            if (gpu != null) {
                LOGGER.warn("XeSS upscaler probe failed on {}; XeSS disabled", gpu.deviceName);
            }
            return null;
        }
        return u;
    }

    private boolean probe() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outHandle = arena.allocate(ValueLayout.ADDRESS);
            int rc = lib.vkCreateContext(outHandle, device.vkDevice().address());
            if (rc != 0) {
                LOGGER.warn("xessVkCreateContext probe failed: rc={} ({})", rc, lib.lastError());
                return false;
            }
            MemorySegment probeCtx = outHandle.get(ValueLayout.ADDRESS, 0);
            if (probeCtx.equals(MemorySegment.NULL)) {
                return false;
            }
            rc = lib.setConfig(probeCtx, config);
            if (rc != 0) {
                LOGGER.warn("xessSetConfig({}, {}) probe failed: rc={} ({})", configName(config), rc, lib.lastError());
                lib.destroyContext(probeCtx);
                return false;
            }
            lib.destroyContext(probeCtx);
            LOGGER.info("XeSS upscaler probe OK (config={})", configName(config));
            return true;
        } catch (Throwable t) {
            LOGGER.warn("XeSS upscaler probe failed", t);
            return false;
        }
    }

    @Override
    public UpscalerSelector.Mode mode() {
        return UpscalerSelector.Mode.XESS;
    }

    @Override
    public boolean isReady() {
        return ready && !failed && !context.equals(MemorySegment.NULL);
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        // XeSS quality ratios: same as FSR / DLSS-RR. The SDK provides xessGetOptimalInputSize
        // for this, but we hardcode the stable ratios that match the SDK's published scales.
        int quality = CausticaConfig.Rt.Upscaler.QUALITY.value();
        float scale = switch (quality) {
            case XeSsLibrary.XESS_QUALITY_SETTING_ULTRA_QUALITY -> 1.0f;
            case XeSsLibrary.XESS_QUALITY_SETTING_QUALITY -> 1.0f / 1.5f;
            case XeSsLibrary.XESS_QUALITY_SETTING_BALANCED -> 1.0f / 1.7f;
            case XeSsLibrary.XESS_QUALITY_SETTING_PERFORMANCE -> 1.0f / 2.0f;
            case XeSsLibrary.XESS_QUALITY_SETTING_ULTRA_PERFORMANCE -> 1.0f / 3.0f;
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
        int effectiveQuality = quality >= 0 ? quality : XeSsLibrary.XESS_QUALITY_SETTING_QUALITY;
        if (ready && !context.equals(MemorySegment.NULL)
                && renderWidth == featureRenderWidth && renderHeight == featureRenderHeight
                && displayWidth == featureDisplayWidth && displayHeight == featureDisplayHeight
                && effectiveQuality == featureQuality) {
            return true;
        }
        try {
            if (!context.equals(MemorySegment.NULL)) {
                lib.destroyContext(context);
                context = MemorySegment.NULL;
            }
            // Publish the requested sizes + quality BEFORE first build descriptor, same rationale as FSR.
            featureRenderWidth = renderWidth;
            featureRenderHeight = renderHeight;
            featureDisplayWidth = displayWidth;
            featureDisplayHeight = displayHeight;
            featureQuality = effectiveQuality;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment outHandle = arena.allocate(ValueLayout.ADDRESS);
                int rc = lib.vkCreateContext(outHandle, device.vkDevice().address());
                if (rc != 0) {
                    throw new IllegalStateException("xessVkCreateContext failed: rc=" + rc);
                }
                context = outHandle.get(ValueLayout.ADDRESS, 0);
                if (context.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("xessVkCreateContext returned null");
                }
                rc = lib.setConfig(context, config);
                if (rc != 0) {
                    throw new IllegalStateException("xessSetConfig failed: rc=" + rc);
                }
            }
            ready = true;
            LOGGER.info("XeSS upscaler context created: {}x{} -> {}x{} (quality={}, config={})",
                    renderWidth, renderHeight, displayWidth, displayHeight, effectiveQuality, configName(config));
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("XeSS upscaler create failed; disabling", t);
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
            MemorySegment params = arena.allocate(Layouts.XEP_SIZE);
            params.set(ValueLayout.JAVA_LONG, Layouts.XEP_COMMAND_BUFFER, cmd);

            writeImageView(params, Layouts.XEP_IN_COLOR, color.image, color.view,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_ACCESS_SHADER_READ_BIT,
                    XeSsLibrary.XESS_COLOR_FORMAT_RGBA16_SFLOAT, color.width, color.height);
            writeImageView(params, Layouts.XEP_IN_DEPTH, depth.image, depth.view,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_ACCESS_SHADER_READ_BIT,
                    XeSsLibrary.XESS_COLOR_FORMAT_RGBA16_SFLOAT, depth.width, depth.height);
            writeImageView(params, Layouts.XEP_IN_MOTION, motion.image, motion.view,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_ACCESS_SHADER_READ_BIT,
                    XeSsLibrary.XESS_COLOR_FORMAT_RGBA16_SFLOAT, motion.width, motion.height);
            // exposure / reactive / transparency: empty (absent). Caustica's path doesn't expose them.
            writeEmptyImageView(params, Layouts.XEP_IN_EXPOSURE);
            writeEmptyImageView(params, Layouts.XEP_IN_REACTIVE);
            writeEmptyImageView(params, Layouts.XEP_IN_TRANSPARENCY);
            writeImageView(params, Layouts.XEP_OUT_COLOR, out.image, out.view,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT,
                    XeSsLibrary.XESS_COLOR_FORMAT_RGBA16_SFLOAT, out.width, out.height);

            params.set(ValueLayout.JAVA_INT, Layouts.XEP_INPUT_W, renderWidth);
            params.set(ValueLayout.JAVA_INT, Layouts.XEP_INPUT_H, renderHeight);
            params.set(ValueLayout.JAVA_INT, Layouts.XEP_OUTPUT_W, displayWidth);
            params.set(ValueLayout.JAVA_INT, Layouts.XEP_OUTPUT_H, displayHeight);
            params.set(ValueLayout.JAVA_INT, Layouts.XEP_QUALITY, featureQuality);
            params.set(ValueLayout.JAVA_INT, Layouts.XEP_INPUT_FORMAT, XeSsLibrary.XESS_COLOR_FORMAT_RGBA16_SFLOAT);
            params.set(ValueLayout.JAVA_INT, Layouts.XEP_OUTPUT_FORMAT, XeSsLibrary.XESS_COLOR_FORMAT_RGBA16_SFLOAT);
            params.set(ValueLayout.JAVA_FLOAT, Layouts.XEP_JITTER_X, jitterX);
            params.set(ValueLayout.JAVA_FLOAT, Layouts.XEP_JITTER_Y, jitterY);
            params.set(ValueLayout.JAVA_FLOAT, Layouts.XEP_INPUT_SCALE_X, 1.0f / Math.max(1, renderWidth));
            params.set(ValueLayout.JAVA_FLOAT, Layouts.XEP_INPUT_SCALE_Y, 1.0f / Math.max(1, renderHeight));
            params.set(ValueLayout.JAVA_FLOAT, Layouts.XEP_EXPOSURE_SCALE, 1.0f);
            params.set(ValueLayout.JAVA_FLOAT, Layouts.XEP_VELOCITY_SCALE, 1.0f);

            int rc = lib.vkExecute(context, params);
            if (rc != 0) {
                throw new IllegalStateException("xessVkExecute failed: rc=" + rc + " (" + lib.lastError() + ")");
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("XeSS evaluate failed; disabling upscaler", t);
            return false;
        }
    }

    @Override
    public void destroy() {
        if (!context.equals(MemorySegment.NULL)) {
            try {
                lib.destroyContext(context);
            } catch (Throwable t) {
                LOGGER.warn("xessDestroyContext failed", t);
            }
            context = MemorySegment.NULL;
        }
        ready = false;
    }

    private static void writeImageView(MemorySegment base, long offset, long image, long view,
                                       int layout, int access, int format, int width, int height) {
        base.set(ValueLayout.JAVA_LONG, offset + Layouts.IVI_IMAGE, image);
        base.set(ValueLayout.JAVA_LONG, offset + Layouts.IVI_IMAGE_VIEW, view);
        base.set(ValueLayout.JAVA_INT, offset + Layouts.IVI_IMAGE_LAYOUT, layout);
        base.set(ValueLayout.JAVA_INT, offset + Layouts.IVI_ACCESS, access);
        base.set(ValueLayout.JAVA_INT, offset + Layouts.IVI_FORMAT, format);
        // width/height don't have dedicated fields in the IVI struct — they live at the XEP level.
        // We ignore them here (the IVI just describes the resource; dimensions are per-parameter).
    }

    private static void writeEmptyImageView(MemorySegment base, long offset) {
        for (long i = 0; i < Layouts.IVI_SIZE; i += 4) {
            base.set(ValueLayout.JAVA_INT, offset + i, 0);
        }
    }

    private static String configName(int c) {
        return switch (c) {
            case XeSsLibrary.XESS_CONFIG_PERF_XMX -> "XMX";
            case XeSsLibrary.XESS_CONFIG_PERF_DP4A -> "DP4a";
            default -> "none";
        };
    }
}
