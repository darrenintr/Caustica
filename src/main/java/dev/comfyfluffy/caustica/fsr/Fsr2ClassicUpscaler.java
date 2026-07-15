package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.upscale.Upscaler;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Classic AMD FSR 2.2 Vulkan upscaler ({@code libffx_fsr2_caustica.so}).
 * Used when modular FSR 3/4 loader has no Vulkan provider (Linux open-source path).
 */
public final class Fsr2ClassicUpscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final String LIB = "libffx_fsr2_caustica.so";

    // Match FfxFsr2QualityMode (no NATIVE in FSR2; 1=Quality … 4=UltraPerf)
    private static final int Q_QUALITY = 1;
    private static final int Q_BALANCED = 2;
    private static final int Q_PERF = 3;
    private static final int Q_ULTRA = 4;

    // HDR | DEPTH_INVERTED | DEPTH_INFINITE | AUTO_EXPOSURE
    // (render-res MVs: do NOT set DISPLAY_RESOLUTION_MOTION_VECTORS)
    private static final int FLAGS_DEFAULT = (1 << 0) | (1 << 3) | (1 << 4) | (1 << 5);

    private final Fsr2ClassicLibrary lib;
    private final VulkanDevice device;
    private MemorySegment ctx = MemorySegment.NULL;
    private int featureRenderW = -1, featureRenderH = -1;
    private int featureDisplayW = -1, featureDisplayH = -1;
    private boolean ready;
    private boolean failed;
    private long frameIndex;
    private boolean hardReset = true;

    private Fsr2ClassicUpscaler(Fsr2ClassicLibrary lib, VulkanDevice device) {
        this.lib = lib;
        this.device = device;
    }

    public static Fsr2ClassicUpscaler tryCreate(GpuVendor gpu) {
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
            LOGGER.info("Classic FSR2 native loaded (probe={}) from {} on {}", ver, so, gpu.deviceName);
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
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                    Files.write(target, bytes);
                    target.toFile().setExecutable(true);
                }
                return target;
            }
        }
        // Dev / pre-seeded
        if (Files.isRegularFile(target) && Files.size(target) > 50_000) {
            return target;
        }
        Path dev = Path.of("src/main/resources/caustica/natives/linux-x64").resolve(LIB);
        return Files.isRegularFile(dev) ? dev.toAbsolutePath() : null;
    }

    @Override
    public Mode mode() {
        return Mode.FSR_3; // report as fsr-3 quality path in UI (classic FSR2 temporal)
    }

    @Override
    public boolean isReady() {
        return ready && !failed && !ctx.equals(MemorySegment.NULL);
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        int cfgQ = CausticaConfig.Rt.Upscaler.QUALITY.value();
        // 0 = true 1:1 (no scale) — sharpest; FSR still runs temporal lock at display res.
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
        // Caustica: 0=native 1:1, 1=quality(~1.5×), 2=balanced, 3=perf, 4=ultra
        return switch (causticaQuality) {
            case 0 -> Q_QUALITY; // unused when query returns 1:1
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
            return true;
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
            LOGGER.info("FSR2 classic context: {}x{} → {}x{}", renderWidth, renderHeight, displayWidth, displayHeight);
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR2 classic create failed; disabling", t);
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
        try {
            // RtComposite convention: pass (-appliedJitter) for raw PT. When the color plate is
            // already NRD-resolved, the caller passes (0,0) — re-applying Halton on a stable
            // denoise plate causes whole-frame camera swim/shake.
            float jx = -jitterX;
            float jy = -jitterY;
            // Vertical FOV from the real projection (m11 = 1/tan(fovy/2) for JOML perspective).
            float fovY = (float) Math.toRadians(70.0);
            if (viewToClip != null) {
                float m11 = viewToClip.m11();
                if (Math.abs(m11) > 1e-5f) {
                    fovY = 2.0f * (float) Math.atan(1.0f / Math.abs(m11));
                }
            }
            // Pack sharpness into preExposure channel: 2.0 + sharpness (export enables RCAS).
            float sharp = CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    ? Math.max(0f, Math.min(1f, CausticaConfig.Rt.Upscaler.SHARPNESS.value()))
                    : -1f; // negative → export disables sharpening
            float preExpPacked = sharp < 0f ? -1f : (2.0f + sharp);
            int rc = lib.dispatch(ctx, cmd,
                    color.image, color.view,
                    depth.image, depth.view,
                    motion.image, motion.view,
                    out.image, out.view,
                    renderWidth, renderHeight,
                    jx, jy, 16.6f, preExpPacked,
                    // Reverse-Z infinite: near plane + far=0 with DEPTH_INFINITE flag.
                    0.05f, 0.0f, fovY,
                    hardReset ? 1 : 0);
            if (frameIndex < 5 || frameIndex % 300 == 0) {
                LOGGER.info("FSR2 dispatch #{} rc={} jitter=({}, {}) fovY={}° sharp={} render={}x{} → {}x{} reset={}",
                        frameIndex, rc, jx, jy, Math.toDegrees(fovY), sharp,
                        renderWidth, renderHeight, displayWidth, displayHeight, hardReset);
            }
            hardReset = false;
            frameIndex++;
            if (rc != 0) {
                throw new IllegalStateException("caustica_ffx_fsr2_dispatch failed: " + rc);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("FSR2 classic evaluate failed", t);
            return false;
        }
    }

    @Override
    public void requestResetHistory() {
        hardReset = true;
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
        ready = false;
    }
}
