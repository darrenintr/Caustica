/*
 * Caustica — FSR3 Upscaler (real AMD ffx-api Vulkan backend) bridge.
 *
 * Loads `libcaustica_ffx_fsr3_upscaler.so` which links against the AMD
 * FidelityFX SDK Vulkan backend (libamd_fidelityfx_vk.a + vk backend).
 * The shim exposes the same C ABI as {@link Fsr41Library} so the existing
 * {@link Fsr41Upscaler} Java code can dispatch through it without
 * modification.
 *
 * The native shim's symbol list:
 *   caustica_ffx_fsr3_create / destroy / dispatch
 *   caustica_ffx_fsr3_probe / query_upscale_ratio /
 *   query_jitter_phase_count / query_jitter_offset
 *
 * Until FSR 4.1 is wired through this same FSR3 lib (no-op for now),
 * we keep the entry points named fsr3 to make migration obvious.
 */

package dev.comfyfluffy.caustica.fsr;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.comfyfluffy.caustica.nativebridge.NativePlatform;
import java.util.OptionalInt;

public final class Fsr3Bridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    /** Canonical Linux-style library name; resolved via {@link NativePlatform#forLibrary}. */
    private static final String LIB_NAME = "libcaustica_ffx_fsr3_upscaler.so";

    /** Packed FFX version (e.g. 30400 = 3.4.0). */
    private static final int FFX_VERSION_PACKED = 30400;

    private final SymbolLookup lookup;
    private final MethodHandle probe;
    private final MethodHandle queryRatio;
    private final MethodHandle queryPhaseCount;
    private final MethodHandle queryPhaseOffset;
    private final MethodHandle create;
    private final MethodHandle destroy;
    private final MethodHandle dispatch;

    private Fsr3Bridge(SymbolLookup lookup) {
        this.lookup = lookup;
        this.probe = req(lookup, "caustica_ffx_fsr3_probe",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.queryRatio = req(lookup, "caustica_ffx_fsr3_query_upscale_ratio",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.queryPhaseCount = req(lookup, "caustica_ffx_fsr3_query_jitter_phase_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.queryPhaseOffset = req(lookup, "caustica_ffx_fsr3_query_jitter_offset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.create = req(lookup, "caustica_ffx_fsr3_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));
        this.destroy = req(lookup, "caustica_ffx_fsr3_destroy",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // dispatch(...
        //          uint64 vk_command_buffer,
        //          uint64 color_img, color_view,
        //          uint64 depth_img, depth_view,
        //          uint64 motion_img, motion_view,
        //          uint64 output_img, output_view,
        //          uint64 reactive_img, reactive_view,
        //          uint64 exposure_img, exposure_view,
        //          uint32 render_w, render_h,
        //          uint32 upscale_w, upscale_h,
        //          float jx, jy, dtMs, preExposure,
        //          float camNear, camFar, camFov,
        //          float vs2m, reset, allow_tearing)
        this.dispatch = req(lookup, "caustica_ffx_fsr3_dispatch",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    public static Fsr3Bridge load() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final Fsr3Bridge INSTANCE = tryLoad();
    }

    private static Fsr3Bridge tryLoad() {
        String override = System.getProperty("caustica.fsr3.path");
        Path so;
        try {
            so = resolveLibraryPath(override);
        } catch (IOException e) {
            LOGGER.warn("FSR3 bridge: failed to resolve library path", e);
            return null;
        }
        if (so == null) {
            LOGGER.info("FSR3 bridge library {} not found; FSR3 path disabled",
                    LIB_NAME);
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(so, arena);
            // probe once so we eagerly report failure to load here.
            try {
                int versionPacked = invokeProbe(lookup);
                LOGGER.info("FSR3 bridge loaded (FFX version packed={}) from {}", versionPacked, so);
            } catch (Throwable t) {
                LOGGER.warn("FSR3 bridge present but probe failed; path disabled", t);
                return null;
            }
            return new Fsr3Bridge(lookup);
        } catch (Throwable t) {
            LOGGER.warn("FSR3 bridge load failed; path disabled", t);
            return null;
        }
    }

    private static Path resolveLibraryPath(String override) throws IOException {
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        // 2026-08-06: platform-aware via NativePlatform.
        NativePlatform platform = NativePlatform.forLibrary(LIB_NAME);
        if (platform == null) {
            return null;
        }
        Path cache = FabricLoader.getInstance().getGameDir()
                .resolve("caustica-fsr")
                .resolve("natives")
                .resolve(platform.resourceDirectory());
        Files.createDirectories(cache);
        Path target = cache.resolve(platform.libraryName());
        try (InputStream in = Fsr3Bridge.class.getResourceAsStream(platform.resourcePath())) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                boolean rewrite = !Files.isRegularFile(target) || Files.size(target) != bytes.length;
                if (!rewrite && Files.isRegularFile(target)) {
                    byte[] existing = Files.readAllBytes(target);
                    rewrite = existing.length != bytes.length
                            || !java.util.Arrays.equals(existing, bytes);
                }
                if (rewrite) {
                    Files.write(target, bytes);
                    if (!platform.isWindows()) target.toFile().setExecutable(true);
                    LOGGER.info("Extracted FSR3 bridge to {} ({} bytes)", target, bytes.length);
                }
                return target;
            }
        }
        if (Files.isRegularFile(target) && Files.size(target) > 50_000) {
            return target;
        }
        return null;
    }

    private static int invokeProbe(SymbolLookup lookup) {
        try {
            Fsr3Bridge b = new Fsr3Bridge(lookup);
            return (int) b.probe.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("ffx_fsr3 probe failed", t);
        }
    }

    private static MethodHandle req(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(() ->
                        new IllegalStateException("missing export " + name)),
                desc);
    }

    /** Packed FFX-FSR3 version (30400 = 3.4.0). */
    public int versionPacked() {
        try { return (int) probe.invokeExact(); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    public int queryUpscaleRatio(int qualityMode) {
        try { return (int) queryRatio.invokeExact(qualityMode); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    public int queryJitterPhaseCount(int renderW, int displayW) {
        try { return (int) queryPhaseCount.invokeExact(renderW, displayW); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    public void queryJitterOffset(int index, int phaseCount, float[] xy) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment x = a.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment y = a.allocate(ValueLayout.JAVA_FLOAT);
            int rc = (int) queryPhaseOffset.invokeExact(index, phaseCount, x, y);
            if (rc != 0) { xy[0] = 0; xy[1] = 0; return; }
            xy[0] = x.get(ValueLayout.JAVA_FLOAT, 0);
            xy[1] = y.get(ValueLayout.JAVA_FLOAT, 0);
        } catch (Throwable t) {
            xy[0] = 0;
            xy[1] = 0;
        }
    }

    public int create(long vkDevice, long vkPhysical, int flags,
                      int maxRenderW, int maxRenderH,
                      int upscaleW, int upscaleH,
                      MemorySegment outCtx) {
        try {
            return (int) create.invokeExact(vkDevice, vkPhysical, flags,
                    maxRenderW, maxRenderH, upscaleW, upscaleH, outCtx);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr3_create failed", t);
        }
    }

    public int destroy(MemorySegment ctx) {
        try { return (int) destroy.invokeExact(ctx); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }

    public int dispatch(MemorySegment ctx, long cmd,
                         long colorImg, long colorView,
                         long depthImg, long depthView,
                         long motionImg, long motionView,
                         long outputImg, long outputView,
                         long reactiveImg, long reactiveView,
                         long exposureImg, long exposureView,
                         int renderW, int renderH,
                         int upscaleW, int upscaleH,
                         float jx, float jy, float dtMs, float preExposure,
                         float camNear, float camFar, float camFov,
                         float vs2m, int reset, int allowTearing) {
        try {
            return (int) dispatch.invokeExact(ctx, cmd,
                    colorImg, colorView,
                    depthImg, depthView,
                    motionImg, motionView,
                    outputImg, outputView,
                    reactiveImg, reactiveView,
                    exposureImg, exposureView,
                    renderW, renderH,
                    upscaleW, upscaleH,
                    jx, jy, dtMs, preExposure,
                    camNear, camFar, camFov,
                    vs2m, reset, allowTearing);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr3_dispatch failed", t);
        }
    }

    public boolean isPresent() {
        return lookup != null;
    }

    public static OptionalInt tryLoadWithProbe() {
        Fsr3Bridge b = load();
        return b == null ? OptionalInt.empty() : OptionalInt.of(b.versionPacked());
    }
}
