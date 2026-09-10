/*
 * Caustica — FSR 4.1 / FFX 4.x modular upscaler.
 * Copyright (c) 2026. Caustica contributors.
 *
 * <p>Mirrors {@link Fsr2ClassicUpscaler} but routes through the FFX 4.1
 * API surface exposed by the in-tree shim
 * ({@code libffx_fsr41_caustica.so}). The shim is only PROBE_ONLY at
 * the moment — the FSR 4.1 Linux port's Vulkan backend is still being
 * assembled. While PROBE_ONLY is in effect, create returns 1 and every
 * dispatch returns -100, which we treat as a fail-open latch that flips
 * the upscale to a 1:1 blit (the same policy the classic FSR2 path
 * uses on RADV/NAVI33).
 *
 * <p>Format contract: color / output
 * {@code R16G16B16A16_SFLOAT}, depth {@code R32_SFLOAT}, motion
 * {@code R16G16B16A16_SFLOAT}, reactive {@code R32_SFLOAT} optional.
 * Caustica's {@code RtPlateBridge} handles path-tracer HDR → RGBA16F
 * staging on the way in, and HDR-from-RGBA16F on the way out.
 */
package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.plate.RtPlateBridge;
import dev.comfyfluffy.caustica.upscale.Upscaler;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * FSR 4.1 / FFX 4.x modular upscaler.
 *
 * <p>Provider id: {@code "fsr41"}. Lazy-loads the shim via
 * {@link Fsr41Runtime}. Until the FSR 4.1 Linux port's
 * {@code ffxCreateContext} / {@code ffxDispatch} land, every dispatch
 * returns the {@code -100} "not FULL" sentinel and the
 * {@link #evaluate(long, RtImage, RtImage, RtImage, RtImage, RtImage, RtImage, RtImage, RtImage, RtImage, int, int, int, int, float, float, Matrix4fc, Matrix4fc)}
 * falls back to the 1:1 blit (the {@code consumeFailOpen()} latch).
 * The wiring is in place so when the FSR 4.1 Linux port's
 * {@code libffx_fsr41_linux.a} is built, a rebuild with
 * {@code -DCAUSTICA_FFX_FSR41_FULL=ON} lights up the native path
 * without any Java changes.
 */
public final class Fsr41Upscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    // FFX 4.1 quality enum (matches FFX_API_FSR_QUALITY_MODE_*).
    private static final int Q_NATIVEAA = 0;
    private static final int Q_QUALITY = 1;
    private static final int Q_BALANCED = 2;
    private static final int Q_PERFORMANCE = 3;
    private static final int Q_ULTRA_PERFORMANCE = 4;

    // FfxApiCreateContextUpscaleFlags bits used by Caustica defaults.
    //   bit0  HDR                       — beauty plate is RGBA16F HDR
    //   bit3  DEPTH_INVERTED            — reverse-Z path-traced depth
    //   bit4  DEPTH_INFINITE            — Caustica uses infinite far
    // We do NOT enable AUTO_EXPOSURE (bit5) — see Fsr2ClassicUpscaler
    // for the RADV-blackout rationale.
    private static final int FLAGS_DEFAULT = (1 << 0) | (1 << 3) | (1 << 4);

    private final Fsr41Library lib;
    private final VulkanDevice device;
    private MemorySegment ctx = MemorySegment.NULL;
    private int featureRenderW = -1, featureRenderH = -1;
    private int featureDisplayW = -1, featureDisplayH = -1;
    private boolean ready;
    private boolean failed;
    private long frameIndex;
    private boolean hardReset = true;
    /** After repeated blackout returns, skip native and let composite blit. */
    private boolean blackoutFailOpen;
    private int consecutiveBlackouts;
    private boolean blackoutLogged;

    private long lastFrameNanos = -1;
    private float lastDeltaTimeMs = 16.6f;

    /** Guides injected by the composite for reactive-mask derivation. */
    private RtImage guideViewZ;
    private RtImage guideDisocclusionMix;
    private RtImage guideSpecAlbedo;
    private RtImage guideEmission;
    private RtImage guideMaterialFlags;

    private RtPlateBridge plate;
    private int inputColorFormat = RtContext.HDR_RADIANCE_FORMAT;

    private Fsr41Upscaler(Fsr41Library lib, VulkanDevice device) {
        this.lib = lib;
        this.device = device;
    }

    public static Fsr41Upscaler tryCreate() {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        java.util.OptionalInt ver = Fsr41Runtime.INSTANCE.tryLoad();
        if (ver.isEmpty()) {
            LOGGER.info("FSR 4.1 shim unavailable; classic FSR2 / TAAU will be used");
            return null;
        }
        try {
            Fsr41Library lib = Fsr41Runtime.INSTANCE.library();
            // Probe already happened in tryLoad; just bind the library.
            return new Fsr41Upscaler(lib, device);
        } catch (Throwable t) {
            LOGGER.warn("FSR 4.1 init failed", t);
            return null;
        }
    }

    @Override
    public String id() {
        return "fsr41";
    }

    @Override
    public String displayName() {
        return "FSR 4.1 (FFX 4.x modular)";
    }

    @Override
    public boolean performsTemporalReconstruction() {
        return true;
    }

    @Override
    public int inputColorFormat(int rawBeautyFormat) {
        return VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    }

    @Override
    public int displayColorFormat(int rawBeautyFormat, boolean hdrEnabled) {
        return hdrEnabled ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : rawBeautyFormat;
    }

    @Override
    public boolean needsReactiveMask() {
        return true;
    }

    @Override
    public boolean needsBlackoutGuard() {
        return true;
    }

    @Override
    public boolean includesSharpening() {
        return true;
    }

    @Override
    public boolean isReady() {
        return ready && !failed && !ctx.equals(MemorySegment.NULL);
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        int cfgQ = CausticaConfig.Rt.Upscaler.QUALITY.value();
        if (cfgQ <= 0) {
            return new int[]{displayWidth, displayHeight};
        }
        int q = mapQuality(cfgQ);
        float ratio = lib.queryUpscaleRatio(q);
        if (ratio <= 0) {
            ratio = 1.5f;
        }
        int w = Math.max(1, Math.round(displayWidth / ratio));
        int h = Math.max(1, Math.round(displayHeight / ratio));
        return new int[]{w, h};
    }

    private static int mapQuality(int causticaQuality) {
        return switch (causticaQuality) {
            case 0 -> Q_QUALITY;
            case 1 -> Q_QUALITY;
            case 2 -> Q_BALANCED;
            case 3 -> Q_PERFORMANCE;
            case 4 -> Q_ULTRA_PERFORMANCE;
            default -> Q_QUALITY;
        };
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight,
                                 int displayWidth, int displayHeight,
                                 int quality, int featureFlags) {
        if (failed) {
            return false;
        }
        if (ready && renderWidth == featureRenderW && renderHeight == featureRenderH
                && displayWidth == featureDisplayW && displayHeight == featureDisplayH) {
            return plate != null && plate.profile() != null;
        }
        if (plate == null || plate.profile() == null) {
            LOGGER.warn("FSR 4.1 feature requested before RtComposite injected its plate bridge");
            return false;
        }
        try {
            if (!ctx.equals(MemorySegment.NULL)) {
                lib.destroy(ctx);
                ctx = MemorySegment.NULL;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
                long dev = device.vkDevice().address();
                long phys = device.vkDevice().getPhysicalDevice().address();
                int rc = lib.create(dev, phys, FLAGS_DEFAULT,
                        renderWidth, renderHeight, displayWidth, displayHeight, out);
                if (rc != 0) {
                    // The shim's PROBE_ONLY build returns 1 here (context
                    // shell allocated but native dispatch stubbed). That's
                    // still "we have a context" for the purposes of
                    // isReady() — the actual sentinel -100 is what
                    // dispatch() returns below.
                    if (rc == -100) {
                        throw new IllegalStateException("FSR 4.1 native probe-only; rebuild with CAUSTICA_FFX_FSR41_FULL=ON");
                    }
                }
                ctx = out.get(ValueLayout.ADDRESS, 0);
                if (ctx.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("null FSR 4.1 context");
                }
            }
            featureRenderW = renderWidth;
            featureRenderH = renderHeight;
            featureDisplayW = displayWidth;
            featureDisplayH = displayHeight;
            ready = true;
            hardReset = true;
            frameIndex = 0;
            blackoutFailOpen = false;
            blackoutLogged = false;
            consecutiveBlackouts = 0;
            if (plate.profile() == null) {
                LOGGER.error("FSR 4.1 feature created but injected plate bridge has no profile");
                ready = false;
                return false;
            }
            LOGGER.info(
                    "FSR 4.1 context: {}x{} → {}x{} (probe-only mode if rc=1; "
                            + "native dispatch returns -100 until FSR 4.1 Linux port's Vulkan backend ships)",
                    renderWidth, renderHeight, displayWidth, displayHeight);
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR 4.1 ensureFeature failed; disabling", t);
            return false;
        }
    }

    @Override
    public boolean evaluate(long cmdAddr, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage specularHitDistance, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!isReady() || color == null || depth == null || motion == null || out == null) {
            return false;
        }
        if (blackoutFailOpen) {
            if (!blackoutLogged) {
                blackoutLogged = true;
                LOGGER.error(
                        "FSR 4.1 native path quarantined this session (probe-only / dispatch -100). "
                                + "Renderer is using 1:1 blit upscale; restart game or rebuild the shim "
                                + "with CAUSTICA_FFX_FSR41_FULL=ON to retry native FSR 4.1.");
            }
            return false;
        }
        if (plate == null) {
            return false;
        }
        try {
            long currentNanos = System.nanoTime();
            if (lastFrameNanos > 0) {
                float deltaMs = (currentNanos - lastFrameNanos) / 1_000_000.0f;
                deltaMs = Math.max(1.0f, Math.min(100.0f, deltaMs));
                lastDeltaTimeMs = deltaMs;
            }
            lastFrameNanos = currentNanos;

            float jx = jitterX;
            float jy = jitterY;
            float fovY = (float) Math.toRadians(70.0);
            float cameraNear = 0.05f;
            if (viewToClip != null) {
                float m11 = viewToClip.m11();
                if (Math.abs(m11) > 1e-5f) {
                    fovY = 2.0f * (float) Math.atan(1.0f / Math.abs(m11));
                }
                float m22 = viewToClip.m22();
                float m32 = viewToClip.m32();
                if (Math.abs(m22) < 0.001f && m32 < 0) {
                    cameraNear = -m32;
                }
            }

            float sharp = CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    ? Math.max(0f, Math.min(1f, CausticaConfig.Rt.Upscaler.SHARPNESS.value()))
                    : -1f;
            float preExpPacked = sharp < 0f ? 1.0f : (2.0f + sharp);

            org.lwjgl.vulkan.VkCommandBuffer cmd =
                    new org.lwjgl.vulkan.VkCommandBuffer(cmdAddr, device.vkDevice());

            RtImage fsrIn = plate.convertToUpscalerInput(cmd, color, inputColorFormat);

            // Reactive mask (motion+depth+normals → R32F). On RADV/NAVI33
            // the reactive compute + FSR path hard-recovers the device
            // on the first real-geometry frame; mirror the FSR2
            // policy and skip reactive on RADV until that's fixed.
            boolean reactiveRan = false;
            if (!dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
                reactiveRan = plate.computeReactiveMaskIfNeeded(
                        cmd, motion, depth, normals, guideViewZ, guideDisocclusionMix,
                        guideSpecAlbedo, guideEmission, guideMaterialFlags);
            }
            RtImage fsrReactive = reactiveRan ? plate.reactiveMask() : null;

            plate.clearUpscalerOutput(cmd);
            RtImage fsrOut = plate.upscalerOutputColor();

            if (dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    org.lwjgl.vulkan.VkMemoryBarrier.Buffer mem =
                            org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default()
                                    .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT
                                            | org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT
                                            | org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                                    .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT
                                            | org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT);
                    org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(cmd,
                            org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                            org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                            0, mem, null, null);
                }
            }

            int rc = lib.dispatch(ctx, cmdAddr,
                    fsrIn.image, fsrIn.view,
                    depth.image, depth.view,
                    motion.image, motion.view,
                    fsrOut.image, fsrOut.view,
                    fsrReactive != null ? fsrReactive.image : 0L,
                    fsrReactive != null ? fsrReactive.view : 0L,
                    renderWidth, renderHeight,
                    displayWidth, displayHeight,
                    jx, jy, lastDeltaTimeMs, preExpPacked,
                    cameraNear, 1_000_000.0f, fovY,
                    hardReset ? 1 : 0);
            if (frameIndex < 5 || frameIndex % 300 == 0) {
                LOGGER.info(
                        "FSR 4.1 dispatch #{} rc={} reactive={} jitter=({}, {}) fovY={}° near={} dt={}ms sharp={} "
                                + "render={}x{} → {}x{} reset={}",
                        frameIndex, rc, reactiveRan,
                        String.format(java.util.Locale.ROOT, "%.3f", jx),
                        String.format(java.util.Locale.ROOT, "%.3f", jy),
                        String.format(java.util.Locale.ROOT, "%.1f", Math.toDegrees(fovY)),
                        String.format(java.util.Locale.ROOT, "%.3f", cameraNear),
                        String.format(java.util.Locale.ROOT, "%.2f", lastDeltaTimeMs),
                        String.format(java.util.Locale.ROOT, "%.2f", sharp),
                        renderWidth, renderHeight, displayWidth, displayHeight, hardReset ? 1 : 0);
            }
            hardReset = false;
            frameIndex++;
            if (rc != 0) {
                consecutiveBlackouts++;
                if (consecutiveBlackouts >= 3) {
                    blackoutFailOpen = true;
                }
                throw new IllegalStateException("caustica_ffx_fsr41_dispatch failed: " + rc);
            }

            plate.convertFromUpscalerOutput(cmd, out);
            consecutiveBlackouts = 0;
            return true;
        } catch (Throwable t) {
            LOGGER.error("FSR 4.1 evaluate failed — composite will blit-fallback", t);
            consecutiveBlackouts++;
            if (consecutiveBlackouts >= 3) {
                blackoutFailOpen = true;
            }
            return false;
        }
    }

    @Override
    public boolean consumeFailOpen() {
        return blackoutFailOpen;
    }

    @Override
    public void setReactiveMaskGuides(RtImage viewZ, RtImage disocclusionMix) {
        this.guideViewZ = viewZ;
        this.guideDisocclusionMix = disocclusionMix;
    }

    @Override
    public void setMaterialGuides(RtImage specAlbedo, RtImage emission, RtImage materialFlags) {
        this.guideSpecAlbedo = specAlbedo;
        this.guideEmission = emission;
        this.guideMaterialFlags = materialFlags;
    }

    @Override
    public void setPlateBridge(RtPlateBridge bridge) {
        this.plate = bridge;
    }

    @Override
    public void setInputColorFormat(int format) {
        this.inputColorFormat = format;
    }

    @Override
    public void requestResetHistory() {
        hardReset = true;
        blackoutFailOpen = false;
        consecutiveBlackouts = 0;
    }

    @Override
    public void destroy() {
        if (!ctx.equals(MemorySegment.NULL)) {
            try {
                lib.destroy(ctx);
            } catch (Throwable ignored) {
            }
            ctx = MemorySegment.NULL;
        }
        plate = null;
        ready = false;
    }
}
