/*
 * Caustica — Classic AMD FSR 2.2 Vulkan upscaler.
 * Copyright (c) 2026. Caustica contributors.
 *
 * The native bridge declares its color and output resources as
 * {@code R16G16B16A16_SFLOAT}, while Caustica's bandwidth-oriented beauty plates are
 * {@code B10G11R11_UFLOAT}. These formats are not view-compatible, so a layer of compute
 * passes is needed to convert to and from RGBA16F around the SDK dispatch.
 *
 * <p>Phase 1 of the format-adaptation refactor: all of that conversion logic
 * (pack/unpack/reactive/guard) now lives in {@link dev.comfyfluffy.caustica.rt.plate.RtPlateBridge}.
 * This class owns the native FSR2 lifecycle and dispatch, plus the
 * blackout-quarantine policy. {@link dev.comfyfluffy.caustica.rt.RtComposite} injects
 * its shared bridge plus the actual input VkFormat each frame, allowing Hybrid's
 * RGBA16F compose output to pass by identity while raw B10G11R11 fallbacks are packed.
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
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classic AMD FSR 2.2 Vulkan upscaler ({@code libffx_fsr2_caustica.so}).
 *
 * <p>Native dispatch lives in this class; format adaptation (HDR → RGBA16F pack,
 * RGBA16F → HDR unpack, optional blackout guard, optional self-derived reactive
 * mask) lives in {@link RtPlateBridge}.
 */
public final class Fsr2ClassicUpscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final String LIB = "libffx_fsr2_caustica.so";

    private static final int Q_QUALITY = 1;
    private static final int Q_BALANCED = 2;
    private static final int Q_PERF = 3;
    private static final int Q_ULTRA = 4;

    // Full classic FSR2 flags for path-traced HDR + reverse-Z infinite depth.
    // bit0 HDR | bit3 DEPTH_INVERTED | bit4 DEPTH_INFINITE
    // Do NOT enable AUTO_EXPOSURE (bit5): we never bind an exposure texture; on RADV
    // that combination returns FFX_OK with a pure-black output plate.
    // Do NOT enable DISPLAY_RESOLUTION_MOTION_VECTORS: gMotion is render-res pixels.
    private static final int FLAGS_DEFAULT = (1 << 0) | (1 << 3) | (1 << 4);

    private final Fsr2ClassicLibrary lib;
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

    /** Composite-owned, non-owning format bridge injected before ensure/evaluate. */
    private RtPlateBridge plate;
    /** Actual VkFormat of the color image supplied to evaluate(). */
    private int inputColorFormat = RtContext.HDR_RADIANCE_FORMAT;

    private Fsr2ClassicUpscaler(Fsr2ClassicLibrary lib, VulkanDevice device) {
        this.lib = lib;
        this.device = device;
    }

    public static Fsr2ClassicUpscaler tryCreate() {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        try {
            Path so = resolveLibrary();
            if (so == null) {
                LOGGER.info("Classic FSR2 native {} not found; skipping FSR2 path", LIB);
                return null;
            }
            Fsr2ClassicLibrary lib = Fsr2ClassicLibrary.load(so);
            int ver = lib.probe();
            LOGGER.info("Classic FSR2 native loaded (probe={}) from {}", ver, so);
            return new Fsr2ClassicUpscaler(lib, device);
        } catch (Throwable t) {
            LOGGER.warn("Classic FSR2 init failed", t);
            return null;
        }
    }

    private static Path resolveLibrary() throws IOException {
        String override = System.getProperty("caustica.fsr2.path");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        Path dir = FabricLoader.getInstance().getGameDir()
                .resolve("caustica-fsr").resolve("natives").resolve("linux-x64");
        Files.createDirectories(dir);
        Path target = dir.resolve(LIB);
        try (InputStream in = Fsr2ClassicUpscaler.class.getResourceAsStream("/caustica/natives/linux-x64/" + LIB)) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                // Always overwrite: size-only checks leave stale SO with wrong MV/format
                // claims that black the FSR path on RADV while still returning rc=0.
                boolean rewrite = !Files.isRegularFile(target) || Files.size(target) != bytes.length;
                if (!rewrite && Files.isRegularFile(target)) {
                    byte[] existing = Files.readAllBytes(target);
                    rewrite = existing.length != bytes.length || !java.util.Arrays.equals(existing, bytes);
                }
                if (rewrite) {
                    Files.write(target, bytes);
                    target.toFile().setExecutable(true);
                    LOGGER.info("Extracted FSR2 native to {} ({} bytes)", target, bytes.length);
                }
                return target;
            }
        }
        if (Files.isRegularFile(target) && Files.size(target) > 50_000) {
            return target;
        }
        Path dev = Path.of("src/main/resources/caustica/natives/linux-x64").resolve(LIB);
        return Files.isRegularFile(dev) ? dev.toAbsolutePath() : null;
    }

    @Override
    public String id() {
        return "fsr2";
    }

    @Override
    public String displayName() {
        return "Classic FSR 2.2";
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
        float ratio = lib.upscaleRatio(q);
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
            case 3 -> Q_PERF;
            case 4 -> Q_ULTRA;
            default -> Q_QUALITY;
        };
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
            LOGGER.warn("FSR2 classic feature requested before RtComposite injected its plate bridge");
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
                    throw new IllegalStateException("caustica_ffx_fsr2_create failed: " + rc);
                }
                ctx = out.get(ValueLayout.ADDRESS, 0);
                if (ctx.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("null FSR2 context");
                }
            }
            featureRenderW = renderWidth;
            featureRenderH = renderHeight;
            featureDisplayW = displayWidth;
            featureDisplayH = displayHeight;
            ready = true;
            hardReset = true;
            frameIndex = 0;
            // New context: allow native FSR2 again (clear any prior session quarantine).
            blackoutFailOpen = false;
            blackoutLogged = false;
            consecutiveBlackouts = 0;
            if (plate.profile() == null) {
                LOGGER.error("FSR2 feature created but injected plate bridge has no profile");
                ready = false;
                return false;
            }
            LOGGER.info(
                    "FSR2 classic context: {}x{} → {}x{} (full native path, RGBA16F α=1 pack + blackout guard)",
                    renderWidth, renderHeight, displayWidth, displayHeight);
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR2 classic ensureFeature failed; disabling", t);
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
                        "FSR2 native path quarantined this session after blackout(s). "
                                + "Using 1:1 blit upscale; denoise still runs. "
                                + "Restart game or toggle upscaler to retry native FSR2.");
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

            // Sharpness packed as (2 + sharpness) — FSR2 native ABI expects > 1.0 to enable RCAS at that strength.
            float sharp = CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    ? Math.max(0f, Math.min(1f, CausticaConfig.Rt.Upscaler.SHARPNESS.value()))
                    : -1f;
            float preExpPacked = sharp < 0f ? 1.0f : (2.0f + sharp);

            org.lwjgl.vulkan.VkCommandBuffer cmd =
                    new org.lwjgl.vulkan.VkCommandBuffer(cmdAddr, device.vkDevice());

            // Bridge chooses identity for RGBA16F Hybrid output, or packs raw
            // B10G11R11 fallback/other denoisers into RGBA16F staging.
            RtImage fsrIn = plate.convertToUpscalerInput(cmd, color, inputColorFormat);

            // Optional reactive mask (motion+depth+normals+viewZ+disocclMix -> R32F).
            // On RADV/NAVI33 the reactive compute + FSR2 v2 path hard-recovers the device on the
            // first real-geometry frame (fixed SQC GPUVM fault). Skip reactive and use v1 only.
            boolean reactiveRan = false;
            if (!dev.comfyfluffy.caustica.rt.RtDeviceBringup.isRadv()) {
                reactiveRan = plate.computeReactiveMaskIfNeeded(
                        cmd, motion, depth, normals, guideViewZ, guideDisocclusionMix);
            }
            RtImage fsrReactive = reactiveRan ? plate.reactiveMask() : null;

            // Pre-clear upscaler-output staging so a partial native write cannot leave NaN/black.
            plate.clearUpscalerOutput(cmd);
            RtImage fsrOut = plate.upscalerOutputColor();

            // RADV: full barrier so RT/plate writes are visible before the native FSR2 dispatch
            // samples color/depth/motion (same graphics queue; no implicit RT→compute sync).
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

            // ----- Native FSR2 dispatch (v1 without reactive, v2 with reactive) -----
            // FSR2's reverse-infinite transform derives its scale from min/max(cameraNear, cameraFar);
            // passing far=0 makes that scale 0 after the inverted-depth swap. A finite positive sentinel
            // keeps the infinite-depth permutation well-defined (the flag, not this magnitude, selects
            // the infinite projection formula).
            int rc;
            if (reactiveRan) {
                if (!lib.hasV2Dispatch() || fsrReactive == null) {
                    throw new IllegalStateException(
                            "reactive v2 dispatch unavailable (v2 symbol missing or reactive image null)");
                }
                rc = lib.dispatchV2(ctx, cmdAddr,
                        fsrIn.image, fsrIn.view,
                        depth.image, depth.view,
                        motion.image, motion.view,
                        fsrOut.image, fsrOut.view,
                        fsrReactive.image, fsrReactive.view,
                        renderWidth, renderHeight,
                        jx, jy, lastDeltaTimeMs, preExpPacked,
                        cameraNear, 1_000_000.0f, fovY,
                        hardReset ? 1 : 0);
            } else {
                rc = lib.dispatch(ctx, cmdAddr,
                        fsrIn.image, fsrIn.view,
                        depth.image, depth.view,
                        motion.image, motion.view,
                        fsrOut.image, fsrOut.view,
                        renderWidth, renderHeight,
                        jx, jy, lastDeltaTimeMs, preExpPacked,
                        cameraNear, 1_000_000.0f, fovY,
                        hardReset ? 1 : 0);
            }
            if (frameIndex < 5 || frameIndex % 300 == 0) {
                LOGGER.info(
                        "FSR2 dispatch #{} rc={} path={} jitter=({}, {}) fovY={}° near={} dt={}ms sharp={} "
                                + "render={}x{} → {}x{} reset={}",
                        frameIndex, rc, reactiveRan ? "v2+reactive" : "v1",
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
                throw new IllegalStateException(
                        reactiveRan ? "caustica_ffx_fsr2_dispatch_v2 failed: " + rc
                                   : "caustica_ffx_fsr2_dispatch failed: " + rc);
            }

            // ----- Bridge-driven output unpack (with optional blackout-guard fallback) -----
            plate.convertFromUpscalerOutput(cmd, out);

            consecutiveBlackouts = 0;
            return true;
        } catch (Throwable t) {
            LOGGER.error("FSR2 classic evaluate failed — composite will blit-fallback", t);
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
        // RtComposite owns and destroys the shared bridge.
        plate = null;
        ready = false;
    }
}
