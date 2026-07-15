package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared AMD FidelityFX loader lifecycle. Loads {@code amd_fidelityfx_loader.dll} /
 * {@code libamd_fidelityfx_loader.so} from the bundled natives or the override path
 * ({@code -Dcaustica.fsr.path}), queries the provider version (FSR 3 vs FSR 4), and creates a per-device
 * context. Multiple features (upscaler, frame generation) share the same loader — the AMD loader
 * transparently loads the per-feature DLLs (e.g. {@code amd_fidelityfx_upscaler.dll} for FSR 3/4,
 * {@code amd_fidelityfx_framegeneration.dll} for FG).
 *
 * <p>Idempotent — one FSR loader per device; latches failure so a missing/broken SDK isn't retried every
 * frame.
 */
public final class FsrRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    public static final FsrRuntime INSTANCE = new FsrRuntime();

    private static final PlatformNatives PLATFORM_NATIVES = PlatformNatives.current();

    /** FFX_API_RETURN_OK == 0. Anything else is a failure. */
    private static final int FFX_OK = 0;

    private FsrLibrary lib;
    private boolean initialized;
    private boolean failed;
    /** Patch number of the loaded upscaler (e.g. 1 for FSR 3.1, 4 for FSR 4.x). -1 unknown. */
    private int loadedUpscalerPatch = -1;
    /** Major version of the loaded SDK (e.g. 2 for FFX SDK 2.x). -1 unknown. */
    private int loadedSdkMajor = -1;
    /** Whether the loaded upscaler is FSR 4 (vs FSR 3). Determined by patch >= 4 OR SDK major >= 2 with
     * the Redstone model (FSR 4) in the bundle — for the 2.1 SDK, FSR 4 DLL content ships separately. */
    private boolean isFsr4;

    private FsrRuntime() {
    }

    public synchronized FsrLibrary acquire(VulkanDevice device) {
        if (initialized) {
            return lib;
        }
        if (failed) {
            return null;
        }
        try {
            init(device);
            initialized = true;
            return lib;
        } catch (Throwable t) {
            failed = true;
            lib = null;
            CausticaMod.LOGGER.error("AMD FidelityFX init failed; FSR features disabled", t);
            return null;
        }
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** Patch number of the loaded upscaler (1 = FSR 3.1.x, 4 = FSR 4.x); -1 if unknown. */
    public int loadedUpscalerPatch() {
        return loadedUpscalerPatch;
    }

    public int loadedSdkMajor() {
        return loadedSdkMajor;
    }

    /** Whether the loaded upscaler is FSR 4.x. */
    public boolean isFsr4Loaded() {
        return isFsr4;
    }

    public synchronized void shutdown() {
        if (lib != null && initialized) {
            try {
                // Per-feature contexts are owned by their own wrappers; we only release the loader here.
            } catch (Throwable t) {
                LOGGER.warn("FSR shutdown failed", t);
            }
        }
        initialized = false;
        failed = false;
        lib = null;
    }

    private void init(VulkanDevice device) {
        if (!PLATFORM_NATIVES.supported()) {
            throw new IllegalStateException("FSR natives are not bundled for " + PLATFORM_NATIVES.platformDir());
        }
        Path shim = locateLoader();
        if (shim == null) {
            throw new IllegalStateException(PLATFORM_NATIVES.loaderName() + " not found (bundled natives or -Dcaustica.fsr.path)");
        }
        Path nativesDir = shim.getParent();
        if (nativesDir != null) {
            List<String> missing = missingFeatureLibraries(nativesDir);
            if (!missing.isEmpty()) {
                LOGGER.warn("FSR feature libraries {} not found next to {}; those features will be unavailable",
                        missing, PLATFORM_NATIVES.loaderName());
            }
        }

        lib = FsrLibrary.load(shim);
        if (lib.hasProviderVersion()) {
            int[] version = lib.readProviderVersion();
            loadedSdkMajor = version[0];
            loadedUpscalerPatch = version[2];
            // Heuristic: SDK 2.1+ ships the FSR 4 model by default (per AMD's modular-loader release notes);
            // an FSR 3-only bundle marks itself with major=1 or the upscaler DLL content is the FSR 3
            // model. The detailed version mapping is kept conservative here — the user can override
            // caustica.fsr.forceMode = "fsr-3" / "fsr-4" to disambiguate when the bundle mixes both.
            isFsr4 = version[0] >= 2 && CausticaConfig.Rt.Fsr.FORCE_FSR_3.value() != CausticaConfig.FsrForceMode.FORCE_FSR_3;
        } else {
            LOGGER.warn("amd_fidelityfx_loader has no ffxQueryGetProviderVersion; cannot determine FSR 3 vs FSR 4 — defaulting to FSR 3");
            isFsr4 = false;
        }
        LOGGER.info("AMD FidelityFX loaded: SDK major={} upscaler patch={} (FSR {})",
                loadedSdkMajor, loadedUpscalerPatch, isFsr4 ? "4" : "3");
    }

    private static Path locateLoader() {
        // 1) Explicit override (-Dcaustica.fsr.path or config)
        String override = CausticaConfig.Rt.Fsr.PATH.get();
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isDirectory(p)) {
                p = p.resolve(PLATFORM_NATIVES.loaderName());
            }
            if (Files.isRegularFile(p)) {
                LOGGER.info("FSR loader from override path: {}", p.toAbsolutePath());
                return p;
            }
            LOGGER.warn("FSR override path set but file missing: {}", p);
        }
        // 2) Extract/copy bundled natives into the game dir (always preferred for dlopen)
        Path extracted = extractBundledLoader();
        if (extracted != null) {
            return extracted;
        }
        // 3) Dev-tree fallbacks (running from repo without jar packaging)
        for (String rel : List.of(
                "src/main/resources/caustica/natives/" + PLATFORM_NATIVES.platformDir(),
                "build/resources/main/caustica/natives/" + PLATFORM_NATIVES.platformDir())) {
            Path p = Path.of(rel).resolve(PLATFORM_NATIVES.loaderName()).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                LOGGER.info("FSR loader from dev path: {}", p);
                return p;
            }
        }
        return null;
    }

    private static Path extractBundledLoader() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-fsr")
                .resolve("natives").resolve(PLATFORM_NATIVES.platformDir());
        try {
            Files.createDirectories(dir);
            boolean ok = extractBundledNative(PLATFORM_NATIVES.loaderName(), dir.resolve(PLATFORM_NATIVES.loaderName()));
            extractBundledFeatureLibraries(dir);
            Path loader = dir.resolve(PLATFORM_NATIVES.loaderName());
            if (!ok || !Files.isRegularFile(loader)) {
                LOGGER.error(
                        "Failed to extract {} into {} (classloader resource missing?). "
                                + "Set -Dcaustica.fsr.path=/path/to/dir-with-libamd_fidelityfx_loader.so",
                        PLATFORM_NATIVES.loaderName(), dir.toAbsolutePath());
                return null;
            }
            long size = Files.size(loader);
            // Stub upscaler is ~15KB; a real loader is hundreds of KB. Log so we can diagnose stubs.
            LOGGER.info("FSR natives ready in {} (loader {} bytes)", dir.toAbsolutePath(), size);
            Path up = dir.resolve("libamd_fidelityfx_upscaler.so");
            if (Files.isRegularFile(up) && Files.size(up) < 50_000L) {
                LOGGER.warn(
                        "libamd_fidelityfx_upscaler.so is only {} bytes — this is the NO_PROVIDER stub. "
                                + "Modular FSR 3/4 upscale will fail until a real Vulkan FSR provider is built "
                                + "(see native/ffx_fsr2 and scripts/build_ffx_vk_linux.sh). "
                                + "Caustica will fall back to TAA / classic FSR2 when available.",
                        Files.size(up));
            }
            return loader;
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Could not extract bundled FSR natives to {}", dir, e);
            return null;
        }
    }

    /**
     * Copy a bundled native into {@code dst}. Tries several classloaders + Fabric mod roots so
     * Prism/Fabric packaging cannot silently miss the resource.
     */
    private static boolean extractBundledNative(String name, Path dst) throws IOException {
        String resource = "/caustica/natives/" + PLATFORM_NATIVES.platformDir() + "/" + name;
        byte[] bytes = readBundledBytes(name, resource);
        if (bytes == null) {
            LOGGER.warn("Bundled FSR native not found on classpath: {}", resource);
            return false;
        }
        if (!sameBytes(dst, bytes)) {
            Files.write(dst, bytes);
            dst.toFile().setExecutable(true);
            LOGGER.info("Extracted FSR native {} ({} bytes) -> {}", name, bytes.length, dst.toAbsolutePath());
        }
        return true;
    }

    private static byte[] readBundledBytes(String name, String resource) throws IOException {
        // 1) Class resource (works for most Fabric jars)
        try (InputStream in = FsrRuntime.class.getResourceAsStream(resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        // 2) Context classloader
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            try (InputStream in = cl.getResourceAsStream(resource.startsWith("/") ? resource.substring(1) : resource)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            }
        }
        // 3) Fabric mod container root paths (handles nested jar FS)
        var opt = FabricLoader.getInstance().getModContainer("caustica");
        if (opt.isPresent()) {
            String relative = "caustica/natives/" + PLATFORM_NATIVES.platformDir() + "/" + name;
            for (Path root : opt.get().getRootPaths()) {
                Path p = root.resolve(relative);
                if (Files.isRegularFile(p)) {
                    return Files.readAllBytes(p);
                }
            }
        }
        return null;
    }

    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            extractBundledNative(name, dir.resolve(name));
        }
        for (String name : bundledFeatureLibraryNames()) {
            extractBundledNative(name, dir.resolve(name));
        }
    }

    private static List<String> bundledFeatureLibraryNames() {
        List<String> names = new ArrayList<>();
        FabricLoader.getInstance().getModContainer("caustica").ifPresent(container -> {
            String nativeDir = "caustica/natives/" + PLATFORM_NATIVES.platformDir();
            for (Path root : container.getRootPaths()) {
                Path dir = root.resolve(nativeDir);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    files.map(path -> path.getFileName().toString())
                            .filter(PLATFORM_NATIVES::isFeatureLibrary)
                            .forEach(names::add);
                } catch (IOException e) {
                    LOGGER.warn("Could not list bundled FSR natives in {}", dir, e);
                }
            }
        });
        return names;
    }

    private static List<String> missingFeatureLibraries(Path dir) {
        List<String> missing = new ArrayList<>();
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                missing.add(name);
            }
        }
        List<String> names;
        try (Stream<Path> files = Files.list(dir)) {
            names = files.map(path -> path.getFileName().toString()).toList();
        } catch (IOException e) {
            return PLATFORM_NATIVES.featureDescriptions();
        }
        for (String prefix : PLATFORM_NATIVES.featureNamePrefixes()) {
            if (names.stream().noneMatch(name -> name.startsWith(prefix))) {
                missing.add(prefix + "*");
            }
        }
        return missing;
    }

    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
        try {
            return Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes);
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private record PlatformNatives(String platformDir, String loaderName, List<String> exactFeatureNames,
                                   List<String> featureNamePrefixes, boolean supported) {
        private static PlatformNatives current() {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
            if (os.contains("win") && x64) {
                return new PlatformNatives("windows-x64", "amd_fidelityfx_loader.dll",
                        // The 2.1 SDK ships one upscaler DLL that hosts either the FSR 3 or the FSR 4 model
                        // (per the modular-loader design). The FG DLL is separate.
                        List.of("amd_fidelityfx_upscaler.dll", "amd_fidelityfx_framegeneration.dll"),
                        List.of(), true);
            }
            if (os.contains("linux") && x64) {
                return new PlatformNatives("linux-x64", "libamd_fidelityfx_loader.so",
                        List.of("libamd_fidelityfx_upscaler.so", "libamd_fidelityfx_framegeneration.so"),
                        List.of(), true);
            }
            return new PlatformNatives(os + "/" + arch, System.mapLibraryName("amd_fidelityfx_loader"),
                    List.of(), List.of(), false);
        }

        private boolean isFeatureLibrary(String name) {
            return exactFeatureNames.contains(name)
                    || featureNamePrefixes.stream().anyMatch(name::startsWith);
        }

        private List<String> featureDescriptions() {
            List<String> descriptions = new ArrayList<>(exactFeatureNames);
            featureNamePrefixes.stream()
                    .map(prefix -> prefix + "*")
                    .forEach(descriptions::add);
            return descriptions;
        }
    }
}
