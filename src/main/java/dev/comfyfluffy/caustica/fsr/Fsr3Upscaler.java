/*
 * Caustica — Classic AMD FSR 3.4 Vulkan upscaler.
 * Copyright (c) 2026. Caustica contributors.
 *
 * <p>Mirrors {@link Fsr2ClassicUpscaler} but routes through the FSR 3.x SDK ABI exposed
 * by {@code libcaustica_ffx_fsr3_upscaler.so} via the in-tree Panama shim
 * {@link Fsr3Bridge}. Frame generation is deliberately NOT used — this is upscaling only.
 *
 * <p>Format contract matches FSR 2: color / output {@code R16G16B16A16_SFLOAT},
 * depth {@code R32_SFLOAT}, motion {@code R16G16B16A16_SFLOAT},
 * reactive {@code R32_SFLOAT} optional, exposure {@code R32_SFLOAT} optional.
 * Caustica's {@code RtPlateBridge} handles path-tracer HDR → RGBA16F staging on the
 * way in, and HDR-from-RGBA16F on the way out.
 *
 * <p>Inputs the bridge writes into every dispatch (full FSR 3 contract):
 * <ul>
 *   <li>Color (HDR radiance) + Depth (HW reversed-Z) + Motion (pixel-space MV)</li>
 *   <li>Reactive mask (R32F, render-res, self-derived from guide divergence + material flags)</li>
 *   <li>Exposure (1×1 R32_SFLOAT from {@link dev.comfyfluffy.caustica.rt.pipeline.RtExposure})</li>
 *   <li>Camera frustum ({@code camNear}, {@code camFar}, {@code camFovY}) from {@code viewToClip}</li>
 *   <li>Sub-pixel jitter (camera-equivalent sign convention; negated for SDKs)</li>
 *   <li>{@code dtMs} (frame delta time in ms, clamped 1-100)</li>
 *   <li>{@code preExposure} packed: {@code 2 + sharpness} for RCAS-on path, else 1.0</li>
 *   <li>{@code vs2m} (view-space → meters) = 1.0 (Minecraft world units are meters)</li>
 *   <li>{@code reset} (hard history reset on teleport / dimension change)</li>
 *   <li>{@code allowTearing} = 0 (FIFO swapchain conservative; MAILBOX users opt-in later)</li>
 * </ul>
 *
 * <p>Order of operations per frame (mirrors Fsr2ClassicUpscaler.evaluate):
 * <ol>
 *   <li>Convert raw beauty → FSR input format via {@code RtPlateBridge.convertToUpscalerInput}.</li>
 *   <li>Compute self-derived reactive mask via {@code RtPlateBridge.computeReactiveMaskIfNeeded}
 *       (skipped on RADV — same SQC GPUVM crash class as classic FSR 2).</li>
 *   <li>Clear upscaler-output staging so a partial native write can't leave NaN/black.</li>
 *   <li>Emit RADV full barrier (RT/plate writes visible before native FSR 3 dispatch).</li>
 *   <li>Native {@code caustica_ffx_fsr3_dispatch} with the full input set.</li>
 *   <li>Bridge-driven output unpack ({@code RtPlateBridge.convertFromUpscalerOutput}).</li>
 * </ol>
 *
 * <p>Sticky blackout-quarantine policy: three consecutive non-zero return codes trip the
 * session-lifetime fail-open latch; {@link #consumeFailOpen()} reports it back to
 * {@code RtComposite} so subsequent frames prefer the 1:1 blit until the user restarts.
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
 * Classic AMD FSR 3.4 Vulkan upscaler ({@code libcaustica_ffx_fsr3_upscaler.so}).
 *
 * <p>Native dispatch lives in this class; format adaptation (HDR → RGBA16F pack,
 * RGBA16F → HDR unpack, optional blackout guard, optional self-derived reactive
 * mask) lives in {@link RtPlateBridge}.
 */
public final class Fsr3Upscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    // FSR 3 quality modes (matches FFX_API_FSR_QUALITY_MODE_*).
    // FSR 3 supports ULTRA_PERFORMANCE (4) which FSR 2 didn't have.
    private static final int Q_NATIVEAA = 0;
    private static final int Q_QUALITY = 1;
    private static final int Q_BALANCED = 2;
    private static final int Q_PERFORMANCE = 3;
    private static final int Q_ULTRA_PERFORMANCE = 4;

    // Full classic FSR 3 flags for path-traced HDR + reverse-Z infinite depth.
    //   bit0  HDR                       — beauty plate is RGBA16F HDR
    //   bit3  DEPTH_INVERTED            — reverse-Z path-traced depth
    //   bit4  DEPTH_INFINITE            — Caustica uses infinite far
    // We do NOT enable AUTO_EXPOSURE (bit5): we bind our own 1×1 R32F exposure image
    // explicitly via setExposureImage; on RADV the auto-exposure fallback returns
    // FFX_OK with a pure-black output plate (same class of bug as classic FSR 2).
    // We do NOT enable DISPLAY_RESOLUTION_MOTION_VECTORS: gMotion is render-res pixels.
    private static final int FLAGS_DEFAULT = (1 << 0) | (1 << 3) | (1 << 4);

    // Minecraft world units are meters; FSR 3's viewSpaceToMetersFactor expects this
    // for the projection-derivation math. Anything non-zero works; 1.0 keeps MV deltas
    // in their native pixel-space units at the SDK boundary.
    private static final float VIEW_SPACE_TO_METERS = 1.0f;

    private final Fsr3Bridge bridge;
    private final VulkanDevice device;
    private MemorySegment ctx = MemorySegment.NULL;
    private int featureRenderW = -1, featureRenderH = -1;
    private int featureDisplayW = -1, featureDisplayH = -1;
    private boolean ready;
    private boolean failed;
    private long frameIndex;
    private boolean hardReset = true;
    /**
     * After a pure-black FSR output (rc=0 but no energy), stay on blit fail-open until
     * a hard reset / recreate proves the path healthy again. Prevents a permanent black screen.
     */
    private boolean blackoutFailOpen;
    private int consecutiveBlackouts;
    private boolean blackoutLogged;

    private long lastFrameNanos = -1;
    private float lastDeltaTimeMs = 16.6f;

    /**
     * Guides injected via {@link #setReactiveMaskGuides} — the bridge consumes them when
     * calling computeReactiveMaskIfNeeded. null guide → reactive pass is skipped.
     */
    private RtImage guideViewZ;
    private RtImage guideDisocclusionMix;

    /**
     * v2 material guides injected via {@link #setMaterialGuides} for enhanced reactive mask.
     */
    private RtImage guideSpecAlbedo;
    private RtImage guideEmission;
    private RtImage guideMaterialFlags;

    /**
     * Compositor-owned 1×1 R32_SFLOAT exposure image (linear scale multiplier).
     * null → FSR 3 falls back to {@code preExposure} scalar (1.0).
     */
    private RtImage exposureImage;

    /** Composite-owned, non-owning format bridge injected before ensure/evaluate. */
    private RtPlateBridge plate;
    /** Actual VkFormat of the color image supplied to evaluate(). */
    private int inputColorFormat = RtContext.HDR_RADIANCE_FORMAT;

    private Fsr3Upscaler(Fsr3Bridge bridge, VulkanDevice device) {
        this.bridge = bridge;
        this.device = device;
    }

    /**
     * Probe + bind. Returns null when the AMD FFX Vulkan backend isn't shipped on this
     * platform, when the bridge failed to load, or when running on RADV (same SQC
     * GPUVM-fault class as classic FSR 2; see project_radv_rt_pipeline_crash.md).
     */
    public static Fsr3Upscaler tryCreate() {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        if (dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
            LOGGER.warn("FSR 3 native known-unstable on RADV/NAVI33 (SQC GPUVM fault); "
                    + "routing to TAAU per UpscalerSelector fallback chain");
            return null;
        }
        try {
            Fsr3Bridge b = Fsr3Bridge.load();
            if (b == null) {
                LOGGER.info("FSR 3 native bridge unavailable; classic FSR 2 / TAAU will be used");
                return null;
            }
            LOGGER.info("FSR 3.4 native loaded (FFX version packed={})", b.versionPacked());
            return new Fsr3Upscaler(b, device);
        } catch (Throwable t) {
            LOGGER.warn("FSR 3 init failed", t);
            return null;
        }
    }

    @Override
    public String id() {
        return "fsr3";
    }

    @Override
    public String displayName() {
        return "Classic FSR 3.4 (FFX 3.x upscaler)";
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
        float ratio = queryUpscaleRatio(q);
        if (ratio <= 0) {
            ratio = 1.5f;
        }
        int w = Math.max(1, Math.round(displayWidth / ratio));
        int h = Math.max(1, Math.round(displayHeight / ratio));
        return new int[]{w, h};
    }

    private static int mapQuality(int causticaQuality) {
        return switch (causticaQuality) {
            case 0 -> Q_NATIVEAA;
            case 1 -> Q_QUALITY;
            case 2 -> Q_BALANCED;
            case 3 -> Q_PERFORMANCE;
            case 4 -> Q_ULTRA_PERFORMANCE;
            default -> Q_QUALITY;
        };
    }

    private float queryUpscaleRatio(int qualityMode) {
        try {
            // FSR 3 native stores ratio as int percent (×100) to keep ABI stable.
            int ratioPct = bridge.queryUpscaleRatio(qualityMode);
            return ratioPct <= 0 ? 1.5f : ratioPct / 100.0f;
        } catch (Throwable t) {
            LOGGER.warn("FSR 3 queryUpscaleRatio failed; using 1.5x", t);
            return 1.5f;
        }
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                 int quality, int featureFlags) {
        if (failed) {
            return false;
        }
        if (ready && renderWidth == featureRenderW && renderHeight == featureRenderH
                && displayWidth == featureDisplayW && displayHeight == featureDisplayH) {
            return plate != null && plate.profile() != null;
        }
        if (plate == null || plate.profile() == null) {
            LOGGER.warn("FSR 3 feature requested before RtComposite injected its plate bridge");
            return false;
        }
        try {
            if (!ctx.equals(MemorySegment.NULL)) {
                bridge.destroy(ctx);
                ctx = MemorySegment.NULL;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
                long dev = device.vkDevice().address();
                long phys = device.vkDevice().getPhysicalDevice().address();
                int rc = bridge.create(dev, phys, FLAGS_DEFAULT,
                        renderWidth, renderHeight,
                        displayWidth, displayHeight,
                        out);
                if (rc != 0) {
                    throw new IllegalStateException("caustica_ffx_fsr3_create failed: " + rc);
                }
                ctx = out.get(ValueLayout.ADDRESS, 0);
                if (ctx.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("null FSR 3 context");
                }
            }
            featureRenderW = renderWidth;
            featureRenderH = renderHeight;
            featureDisplayW = displayWidth;
            featureDisplayH = displayHeight;
            ready = true;
            hardReset = true;
            frameIndex = 0;
            // New context: allow native FSR 3 again (clear any prior session quarantine).
            blackoutFailOpen = false;
            blackoutLogged = false;
            consecutiveBlackouts = 0;
            if (plate.profile() == null) {
                LOGGER.error("FSR 3 feature created but injected plate bridge has no profile");
                ready = false;
                return false;
            }
            LOGGER.info(
                    "FSR 3.4 context: {}x{} → {}x{} (full native path, RGBA16F α=1 pack + blackout guard, "
                            + "v2 reactive (motion+depth+viewZ+disoccl+specAlbedo+emission+materialFlags))",
                    renderWidth, renderHeight, displayWidth, displayHeight);
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR 3 ensureFeature failed; disabling", t);
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
        // Sticky quarantine: after repeated native blackouts, skip native and let composite blit.
        if (blackoutFailOpen) {
            if (!blackoutLogged) {
                blackoutLogged = true;
                LOGGER.error(
                        "FSR 3 native path quarantined this session after blackout(s). "
                                + "Using 1:1 blit upscale; denoise still runs. "
                                + "Restart game or toggle upscaler to retry native FSR 3.");
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

            // Camera-derived FOV + near plane (mirror pre-refactor derivation).
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

            // Sharpness packed as (2 + sharpness) — same encoding as classic FSR 2.
            // FSR 3 native ABI expects > 1.0 to enable RCAS at that strength.
            float sharp = CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    ? Math.max(0f, Math.min(1f, CausticaConfig.Rt.Upscaler.SHARPNESS.value()))
                    : -1f;
            float preExpPacked = sharp < 0f ? 1.0f : (2.0f + sharp);

            // Allow tearing only when the swapchain is MAILBOX / IMMEDIATE; FIFO default → 0.
            // Blaze3D doesn't expose this directly; conservative default is 0 (no tearing).
            int allowTearing = 0;

            org.lwjgl.vulkan.VkCommandBuffer cmd =
                    new org.lwjgl.vulkan.VkCommandBuffer(cmdAddr, device.vkDevice());

            // Bridge chooses identity for RGBA16F Hybrid output, or packs raw
            // B10G11R11 fallback/other denoisers into RGBA16F staging.
            RtImage fsrIn = plate.convertToUpscalerInput(cmd, color, inputColorFormat);

            // Optional reactive mask (motion+depth+normals+viewZ+disocclMix + specAlbedo/emission/materialFlags
            // → R32F). The bridge's v2 path boosts reactive values for metallic / emissive / transparent surfaces.
            // On RADV/NAVI33 the reactive compute + FSR 3 native dispatch hard-recovers the device on the
            // first real-geometry frame (same fixed SQC GPUVM fault as classic FSR 2). The RADV guard in
            // tryCreate() already prevents reaching this code on RADV; the runtime check is defense-in-depth.
            boolean reactiveRan = false;
            if (!dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
                reactiveRan = plate.computeReactiveMaskIfNeeded(
                        cmd, motion, depth, normals, guideViewZ, guideDisocclusionMix,
                        guideSpecAlbedo, guideEmission, guideMaterialFlags);
            }
            RtImage fsrReactive = reactiveRan ? plate.reactiveMask() : null;

            // Pre-clear upscaler-output staging so a partial native write cannot leave NaN/black.
            plate.clearUpscalerOutput(cmd);
            RtImage fsrOut = plate.upscalerOutputColor();

            // Exposure: pass the 1×1 R32_SFLOAT image (image + view handles). When
            // exposureImage is null, FSR 3 falls back to the preExposure scalar.
            long exposureImgHandle = exposureImage != null ? exposureImage.image : 0L;
            long exposureViewHandle = exposureImage != null ? exposureImage.view : 0L;

            // Reactive: pass the mask image+view when reactiveRan, otherwise 0/0
            // (FSR 3 treats 0/0 as "no reactive mask" and runs the default path).
            long reactiveImgHandle = fsrReactive != null ? fsrReactive.image : 0L;
            long reactiveViewHandle = fsrReactive != null ? fsrReactive.view : 0L;

            // RADV: full barrier so RT/plate writes are visible before the native FSR 3 dispatch
            // samples color/depth/motion/exposure/reactive (same graphics queue; no implicit
            // RT→compute sync). Defense-in-depth — tryCreate() already rejects RADV, but a
            // secondary RADV detection (e.g. via env var override) could still reach here.
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

            // ----- Native FSR 3 dispatch -----
            // FSR 3's reverse-infinite transform derives its scale from min/max(cameraNear, cameraFar);
            // passing far=0 makes that scale 0 after the inverted-depth swap. A finite positive sentinel
            // keeps the infinite-depth permutation well-defined (the flag, not this magnitude, selects
            // the infinite projection formula).
            int rc = bridge.dispatch(ctx, cmdAddr,
                    fsrIn.image, fsrIn.view,
                    depth.image, depth.view,
                    motion.image, motion.view,
                    fsrOut.image, fsrOut.view,
                    reactiveImgHandle, reactiveViewHandle,
                    exposureImgHandle, exposureViewHandle,
                    renderWidth, renderHeight,
                    displayWidth, displayHeight,
                    jx, jy, lastDeltaTimeMs, preExpPacked,
                    cameraNear, 1_000_000.0f, fovY,
                    VIEW_SPACE_TO_METERS, hardReset ? 1 : 0, allowTearing);

            if (frameIndex < 5 || frameIndex % 300 == 0) {
                LOGGER.info(
                        "FSR 3 dispatch #{} rc={} path={} jitter=({}, {}) fovY={}° near={} dt={}ms sharp={} "
                                + "render={}x{} → {}x{} reset={} vs2m={} tearing={} exposure={} reactive={}",
                        frameIndex, rc,
                        reactiveRan ? "v2+reactive" : "v1",
                        String.format(java.util.Locale.ROOT, "%.3f", jx),
                        String.format(java.util.Locale.ROOT, "%.3f", jy),
                        String.format(java.util.Locale.ROOT, "%.1f", Math.toDegrees(fovY)),
                        String.format(java.util.Locale.ROOT, "%.3f", cameraNear),
                        String.format(java.util.Locale.ROOT, "%.2f", lastDeltaTimeMs),
                        String.format(java.util.Locale.ROOT, "%.2f", sharp),
                        renderWidth, renderHeight, displayWidth, displayHeight, hardReset ? 1 : 0,
                        VIEW_SPACE_TO_METERS, allowTearing,
                        exposureImage != null ? "image" : "scalar-fallback",
                        reactiveRan ? "computed" : "skipped");
            }
            hardReset = false;
            frameIndex++;
            if (rc != 0) {
                consecutiveBlackouts++;
                if (consecutiveBlackouts >= 3) {
                    blackoutFailOpen = true;
                }
                throw new IllegalStateException("caustica_ffx_fsr3_dispatch failed: " + rc);
            }

            // ----- Bridge-driven output unpack (with optional blackout-guard fallback) -----
            plate.convertFromUpscalerOutput(cmd, out);

            consecutiveBlackouts = 0;
            return true;
        } catch (Throwable t) {
            LOGGER.error("FSR 3 evaluate failed — composite will blit-fallback", t);
            consecutiveBlackouts++;
            if (consecutiveBlackouts >= 3) {
                blackoutFailOpen = true;
            }
            return false;
        }
    }

    /**
     * Sticky latch: true while native FSR is quarantined so composite prefers blit.
     */
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
    public void setExposureImage(RtImage exposure) {
        this.exposureImage = exposure;
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
                bridge.destroy(ctx);
            } catch (Throwable ignored) {
            }
            ctx = MemorySegment.NULL;
        }
        // RtComposite owns and destroys the shared bridge.
        plate = null;
        exposureImage = null;
        guideViewZ = null;
        guideDisocclusionMix = null;
        guideSpecAlbedo = null;
        guideEmission = null;
        guideMaterialFlags = null;
        ready = false;
    }
}