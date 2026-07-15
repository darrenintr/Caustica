package dev.comfyfluffy.caustica.nrd;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.APIUtil;
import org.lwjgl.vulkan.VK;

import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

/** Loads {@code libnrd_caustica.so} and owns the REBLUR context. */
public final class NrdRuntime {
    public static final NrdRuntime INSTANCE = new NrdRuntime();

    private static final String RESOURCE = "/caustica/natives/linux-x64/libnrd_caustica.so";
    private static final String LIB_NAME = "libnrd_caustica.so";

    private NrdLibrary lib;
    private MemorySegment ctx = MemorySegment.NULL;
    private boolean failed;
    private int version = -1;
    private int width, height;

    private NrdRuntime() {
    }

    public synchronized OptionalInt tryLoad() {
        if (failed) {
            return OptionalInt.empty();
        }
        if (lib != null) {
            return OptionalInt.of(version);
        }
        try {
            Path so = resolve();
            if (so == null) {
                CausticaMod.LOGGER.info("NRD native {} not found; hybrid stays FFX-only", LIB_NAME);
                failed = true;
                return OptionalInt.empty();
            }
            lib = NrdLibrary.load(so);
            version = lib.probe();
            CausticaMod.LOGGER.info("NRD native loaded (probe={}) from {}", version, so);
            return OptionalInt.of(version);
        } catch (Throwable t) {
            failed = true;
            lib = null;
            CausticaMod.LOGGER.warn("NRD native load failed", t);
            return OptionalInt.empty();
        }
    }

    public synchronized boolean isAvailable() {
        return lib != null && !failed;
    }

    public synchronized boolean ensureContext(long vkDevice, long vkPhysical, int w, int h) {
        if (!isAvailable() || w <= 0 || h <= 0) {
            return false;
        }
        try {
            if (!ctx.equals(MemorySegment.NULL) && width == w && height == h) {
                return true;
            }
            if (!ctx.equals(MemorySegment.NULL)) {
                lib.destroy(ctx);
                ctx = MemorySegment.NULL;
            }
            long gdpa = APIUtil.apiGetFunctionAddress(VK.getFunctionProvider(), "vkGetDeviceProcAddr");
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
                int rc = lib.create(vkDevice, vkPhysical, gdpa, w, h, out);
                if (rc != 0) {
                    CausticaMod.LOGGER.warn("caustica_nrd_create failed rc={}", rc);
                    return false;
                }
                ctx = out.get(ValueLayout.ADDRESS, 0);
                if (ctx.equals(MemorySegment.NULL)) {
                    return false;
                }
            }
            width = w;
            height = h;
            CausticaMod.LOGGER.info("NRD REBLUR context {}x{}", w, h);
            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NRD ensureContext failed", t);
            return false;
        }
    }

    public synchronized int dispatch(long cmd,
                                     long inDiffImg, long inDiffView,
                                     long inSpecImg, long inSpecView,
                                     long inMvImg, long inMvView,
                                     long inNormImg, long inNormView,
                                     long inVzImg, long inVzView,
                                     long outDiffImg, long outDiffView,
                                     long outSpecImg, long outSpecView,
                                     float[] viewToClip, float[] viewToClipPrev,
                                     float[] worldToView, float[] worldToViewPrev,
                                     float jx, float jy, float jxPrev, float jyPrev,
                                     int frameIndex, boolean reset) {
        if (ctx.equals(MemorySegment.NULL) || lib == null) {
            return -1;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vtc = copyFloats(arena, viewToClip);
            MemorySegment vtcp = copyFloats(arena, viewToClipPrev);
            MemorySegment wtv = copyFloats(arena, worldToView);
            MemorySegment wtvp = copyFloats(arena, worldToViewPrev);
            return lib.dispatch(ctx, cmd,
                    inDiffImg, inDiffView, inSpecImg, inSpecView,
                    inMvImg, inMvView, inNormImg, inNormView, inVzImg, inVzView,
                    outDiffImg, outDiffView, outSpecImg, outSpecView,
                    vtc, vtcp, wtv, wtvp,
                    jx, jy, jxPrev, jyPrev, frameIndex, reset ? 1 : 0);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NRD dispatch failed", t);
            return -2;
        }
    }

    public synchronized void destroy() {
        if (lib != null && !ctx.equals(MemorySegment.NULL)) {
            try {
                lib.destroy(ctx);
            } catch (Throwable ignored) {
            }
            ctx = MemorySegment.NULL;
        }
        width = height = 0;
    }

    private static MemorySegment copyFloats(Arena arena, float[] src) {
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, src.length);
        for (int i = 0; i < src.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, src[i]);
        }
        return seg;
    }

    private static Path resolve() throws Exception {
        String override = System.getProperty("caustica.nrd.path");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        Path cache = FabricLoader.getInstance().getGameDir()
                .resolve(".caustica").resolve("natives").resolve("linux-x64");
        Files.createDirectories(cache);
        Path target = cache.resolve(LIB_NAME);
        try (InputStream in = NrdRuntime.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                    Files.write(target, bytes);
                    target.toFile().setExecutable(true);
                }
                return target;
            }
        }
        Path dev = Path.of("src/main/resources/caustica/natives/linux-x64").resolve(LIB_NAME);
        return Files.isRegularFile(dev) ? dev.toAbsolutePath() : null;
    }
}
