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

/** Loads the platform NRD shim and owns the REBLUR context. */
public final class NrdRuntime {
    public static final NrdRuntime INSTANCE = new NrdRuntime();

    private static final int EXPECTED_ABI = 2;
    private static final int EXPECTED_NRD_MAJOR = 4;
    private static final int EXPECTED_NORMAL_ENCODING = 4;
    private static final int EXPECTED_ROUGHNESS_ENCODING = 1;

    private NrdLibrary lib;
    private MemorySegment ctx = MemorySegment.NULL;
    // RELAX runs on a separate context — NRD's Instance can only host REBLUR or RELAX
    // at a time, so we own two contexts side-by-side when both are in use.
    private MemorySegment ctxRelax = MemorySegment.NULL;
    private int relaxWidth, relaxHeight;
    private long relaxDevice, relaxPhysical;
    private int relaxGraphicsFamily = -1, relaxComputeFamily = -1;
    private boolean relaxReady;
    private boolean failed;
    private int version = -1;
    private int width, height;
    private long device, physical;
    private int graphicsQueueFamily = -1, computeQueueFamily = -1;

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
            NativePlatform platform = NativePlatform.current();
            if (platform == null) {
                CausticaMod.LOGGER.info("NRD native unavailable on {} / {}", System.getProperty("os.name"),
                        System.getProperty("os.arch"));
                failed = true;
                return OptionalInt.empty();
            }
            Path nativePath = resolve(platform);
            if (nativePath == null || !Files.isRegularFile(nativePath)) {
                CausticaMod.LOGGER.info("NRD native {} not found; NRD path disabled", platform.libraryName());
                failed = true;
                return OptionalInt.empty();
            }
            lib = NrdLibrary.load(nativePath); // Resolves caustica_nrd_probe and every ABI v2 symbol.
            version = lib.probe();
            int major = version / 10_000;
            int abi = lib.abiVersion();
            int normal = lib.normalEncoding();
            int roughness = lib.roughnessEncoding();
            if (major != EXPECTED_NRD_MAJOR || abi != EXPECTED_ABI
                    || normal != EXPECTED_NORMAL_ENCODING || roughness != EXPECTED_ROUGHNESS_ENCODING) {
                throw new IllegalStateException("incompatible NRD native: version=" + version
                        + ", abi=" + abi + ", normal=" + normal + ", roughness=" + roughness
                        + " (expected major=" + EXPECTED_NRD_MAJOR + ", abi=" + EXPECTED_ABI
                        + ", normal=" + EXPECTED_NORMAL_ENCODING + ", roughness="
                        + EXPECTED_ROUGHNESS_ENCODING + ")");
            }
            CausticaMod.LOGGER.info("NRD native loaded (version={}, ABI={}, normal={}, roughness={}) from {}",
                    version, abi, normal, roughness, nativePath);
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

    public synchronized boolean ensureContext(long vkDevice, long vkPhysical, int graphicsFamily,
                                              int computeFamily, int w, int h) {
        if (!isAvailable() || w <= 0 || h <= 0) {
            return false;
        }
        try {
            boolean sameDevice = device == vkDevice && physical == vkPhysical
                    && graphicsQueueFamily == graphicsFamily && computeQueueFamily == computeFamily;
            if (!ctx.equals(MemorySegment.NULL) && sameDevice) {
                if (width == w && height == h) return true;
                int rc = lib.resize(ctx, w, h);
                if (rc == 0) {
                    width = w;
                    height = h;
                    return true;
                }
                CausticaMod.LOGGER.warn("caustica_nrd_resize failed rc={}; recreating context", rc);
            }
            if (!ctx.equals(MemorySegment.NULL)) {
                lib.destroy(ctx);
                ctx = MemorySegment.NULL;
            }
            long gdpa = APIUtil.apiGetFunctionAddress(VK.getFunctionProvider(), "vkGetDeviceProcAddr");
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
                int rc = lib.create(vkDevice, vkPhysical, gdpa, w, h,
                        graphicsFamily, computeFamily, out);
                if (rc != 0) {
                    CausticaMod.LOGGER.warn("caustica_nrd_create failed rc={} ({})", rc, createError(rc));
                    return false;
                }
                ctx = out.get(ValueLayout.ADDRESS, 0);
                if (ctx.equals(MemorySegment.NULL)) {
                    return false;
                }
            }
            width = w;
            height = h;
            device = vkDevice;
            physical = vkPhysical;
            graphicsQueueFamily = graphicsFamily;
            computeQueueFamily = computeFamily;
            CausticaMod.LOGGER.info("NRD REBLUR context {}x{}", w, h);
            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NRD ensureContext failed", t);
            return false;
        }
    }

    /**
     * Update REBLUR's {@code maxAccumulatedFrameNum} at runtime. Returns true on success,
     * false if the underlying shim does not expose the setter symbol (older build).
     * Idempotent and safe to call before {@link #ensureContext} — it just no-ops.
     */
    public synchronized boolean setReblurMaxAccumulatedFrames(int frameNum) {
        if (lib == null || failed) return false;
        if (ctx.equals(MemorySegment.NULL)) return false;
        int rc = lib.setMaxAccumulatedFrameNum(ctx, frameNum);
        if (rc == 0) return true;
        if (rc == -1) {
            // Symbol missing — older build, no-op.
            return false;
        }
        CausticaMod.LOGGER.warn("caustica_nrd_set_max_accumulated_frame_num failed rc={}", rc);
        return false;
    }

    /** RELAX equivalent of {@link #setReblurMaxAccumulatedFrames}. */
    public synchronized boolean setRelaxMaxAccumulatedFrames(int frameNum) {
        if (lib == null || failed) return false;
        if (ctxRelax.equals(MemorySegment.NULL)) return false;
        int rc = lib.setRelaxMaxAccumulatedFrameNum(ctxRelax, frameNum);
        if (rc == 0) return true;
        if (rc == -1) return false;
        CausticaMod.LOGGER.warn("caustica_nrd_set_relax_max_accumulated_frame_num failed rc={}", rc);
        return false;
    }

    /** True iff the underlying shim exposes the REBLUR setter (newer build with v3 ABI hooks). */
    public synchronized boolean supportsReblurMaxAccumulatedFramesSetter() {
        return lib != null && !failed && lib.supportsMaxAccumulatedFrameNumSetter();
    }

    /** True iff the underlying shim exposes the RELAX setter. */
    public synchronized boolean supportsRelaxMaxAccumulatedFramesSetter() {
        return lib != null && !failed && lib.supportsRelaxMaxAccumulatedFrameNumSetter();
    }

    public synchronized boolean isRelaxAvailable() {
        return lib != null && !failed && lib.supportsRelax();
    }

    /**
     * Lazily create the RELAX context. Returns false if the underlying shim predates RELAX
     * support or RELAX creation fails (e.g. format unsupported on this device).
     */
    public synchronized boolean ensureContextRelax(long vkDevice, long vkPhysical,
                                                    int graphicsFamily, int computeFamily,
                                                    int w, int h) {
        if (!isRelaxAvailable() || w <= 0 || h <= 0) {
            return false;
        }
        try {
            boolean sameDevice = relaxDevice == vkDevice && relaxPhysical == vkPhysical
                    && relaxGraphicsFamily == graphicsFamily && relaxComputeFamily == computeFamily;
            if (!ctxRelax.equals(MemorySegment.NULL) && sameDevice) {
                if (relaxWidth == w && relaxHeight == h) return true;
                int rc = lib.resize(ctxRelax, w, h);
                if (rc == 0) {
                    relaxWidth = w;
                    relaxHeight = h;
                    return true;
                }
                CausticaMod.LOGGER.warn("caustica_nrd_resize (relax) failed rc={}; recreating", rc);
            }
            if (!ctxRelax.equals(MemorySegment.NULL)) {
                lib.destroy(ctxRelax);
                ctxRelax = MemorySegment.NULL;
            }
            long gdpa = APIUtil.apiGetFunctionAddress(VK.getFunctionProvider(), "vkGetDeviceProcAddr");
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
                int rc = lib.createRelax(vkDevice, vkPhysical, gdpa, w, h,
                        graphicsFamily, computeFamily, out);
                if (rc != 0) {
                    CausticaMod.LOGGER.warn("caustica_nrd_create_relax failed rc={} ({})", rc, createError(rc));
                    return false;
                }
                ctxRelax = out.get(ValueLayout.ADDRESS, 0);
                if (ctxRelax.equals(MemorySegment.NULL)) {
                    return false;
                }
            }
            relaxWidth = w;
            relaxHeight = h;
            relaxDevice = vkDevice;
            relaxPhysical = vkPhysical;
            relaxGraphicsFamily = graphicsFamily;
            relaxComputeFamily = computeFamily;
            relaxReady = true;
            CausticaMod.LOGGER.info("NRD RELAX context {}x{}", w, h);
            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NRD RELAX ensureContext failed", t);
            return false;
        }
    }

    public synchronized int dispatchRelax(long cmd,
                                           long inDiffImg, long inDiffView,
                                           long inSpecImg, long inSpecView,
                                           long inMvImg, long inMvView,
                                           long inNormImg, long inNormView,
                                           long inVzImg, long inVzView,
                                           long inShadowImg, long inShadowView,
                                           long inDiffConfImg, long inDiffConfView,
                                           long inSpecConfImg, long inSpecConfView,
                                           long inDisocclusionImg, long inDisocclusionView,
                                           long outDiffImg, long outDiffView,
                                           long outSpecImg, long outSpecView,
                                           long outShadowImg, long outShadowView,
                                           float[] viewToClip, float[] viewToClipPrev,
                                           float[] worldToView, float[] worldToViewPrev,
                                           float jx, float jy, float jxPrev, float jyPrev,
                                           float lightDirX, float lightDirY, float lightDirZ,
                                           int frameIndex, boolean reset) {
        if (ctxRelax.equals(MemorySegment.NULL) || lib == null) {
            return -1;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vtc = copyFloats(arena, viewToClip);
            MemorySegment vtcp = copyFloats(arena, viewToClipPrev);
            MemorySegment wtv = copyFloats(arena, worldToView);
            MemorySegment wtvp = copyFloats(arena, worldToViewPrev);
            return lib.dispatchRelax(ctxRelax, cmd,
                    inDiffImg, inDiffView, inSpecImg, inSpecView,
                    inMvImg, inMvView, inNormImg, inNormView, inVzImg, inVzView,
                    inShadowImg, inShadowView, inDiffConfImg, inDiffConfView,
                    inSpecConfImg, inSpecConfView, inDisocclusionImg, inDisocclusionView,
                    outDiffImg, outDiffView, outSpecImg, outSpecView, outShadowImg, outShadowView,
                    vtc, vtcp, wtv, wtvp,
                    jx, jy, jxPrev, jyPrev, lightDirX, lightDirY, lightDirZ,
                    frameIndex, reset ? 1 : 0);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("NRD RELAX dispatch failed", t);
            return -2;
        }
    }

    public synchronized int dispatch(long cmd,
                                     long inDiffImg, long inDiffView,
                                     long inSpecImg, long inSpecView,
                                     long inMvImg, long inMvView,
                                     long inNormImg, long inNormView,
                                     long inVzImg, long inVzView,
                                     long inShadowImg, long inShadowView,
                                     long inDiffConfImg, long inDiffConfView,
                                     long inSpecConfImg, long inSpecConfView,
                                     long inDisocclusionImg, long inDisocclusionView,
                                     long outDiffImg, long outDiffView,
                                     long outSpecImg, long outSpecView,
                                     long outShadowImg, long outShadowView,
                                     float[] viewToClip, float[] viewToClipPrev,
                                     float[] worldToView, float[] worldToViewPrev,
                                     float jx, float jy, float jxPrev, float jyPrev,
                                     float lightDirX, float lightDirY, float lightDirZ,
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
                    inShadowImg, inShadowView, inDiffConfImg, inDiffConfView,
                    inSpecConfImg, inSpecConfView, inDisocclusionImg, inDisocclusionView,
                    outDiffImg, outDiffView, outSpecImg, outSpecView, outShadowImg, outShadowView,
                    vtc, vtcp, wtv, wtvp,
                    jx, jy, jxPrev, jyPrev, lightDirX, lightDirY, lightDirZ,
                    frameIndex, reset ? 1 : 0);
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
        if (lib != null && !ctxRelax.equals(MemorySegment.NULL)) {
            try {
                lib.destroy(ctxRelax);
            } catch (Throwable ignored) {
            }
            ctxRelax = MemorySegment.NULL;
        }
        relaxReady = false;
        width = height = 0;
        device = physical = 0;
        graphicsQueueFamily = computeQueueFamily = -1;
        relaxWidth = relaxHeight = 0;
        relaxDevice = relaxPhysical = 0;
        relaxGraphicsFamily = relaxComputeFamily = -1;
    }

    private static MemorySegment copyFloats(Arena arena, float[] src) {
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, src.length);
        for (int i = 0; i < src.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, src[i]);
        }
        return seg;
    }

    private static String createError(int rc) {
        return switch (rc) {
            case -3 -> "selected graphics/compute queue family lacks compute support";
            case -7 -> "shaderStorageImageExtendedFormats is unavailable";
            case -8 -> "an NRD internal format lacks sampled/storage image support";
            default -> "native initialization error";
        };
    }

    private static Path resolve(NativePlatform platform) throws Exception {
        String override = System.getProperty("caustica.nrd.path");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        Path cache = FabricLoader.getInstance().getGameDir()
                .resolve(".caustica").resolve("natives").resolve(platform.resourceDirectory());
        Files.createDirectories(cache);
        Path target = cache.resolve(platform.libraryName());
        try (InputStream in = NrdRuntime.class.getResourceAsStream(platform.resourcePath())) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                    Files.write(target, bytes);
                    if (!platform.windows()) target.toFile().setExecutable(true);
                }
                return target;
            }
        }
        Path dev = Path.of("src/main/resources/caustica/natives")
                .resolve(platform.resourceDirectory()).resolve(platform.libraryName());
        return Files.isRegularFile(dev) ? dev.toAbsolutePath() : null;
    }

    private record NativePlatform(String resourceDirectory, String libraryName, boolean windows) {
        String resourcePath() {
            return "/caustica/natives/" + resourceDirectory + "/" + libraryName;
        }

        static NativePlatform current() {
            String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
            if (!(arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64"))) return null;
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) return new NativePlatform("windows-x64", "nrd_caustica.dll", true);
            if (os.contains("linux")) return new NativePlatform("linux-x64", "libnrd_caustica.so", false);
            return null;
        }
    }
}
