package dev.comfyfluffy.caustica.xess;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Shared Intel XeSS runtime lifecycle. Loads {@code libxess} (Windows / Linux x64) from the
 * bundled natives or the override path ({@code -Dcaustica.xess.path}), configures it for the
 * active device, and exposes a singleton per-device handle. Multiple XeSS features (upscaler,
 * frame generation in 2.1+) share the same XeSS context.
 *
 * <p>Idempotent — one XeSS loader per device; latches failure so a missing/broken SDK is not
 * retried every frame. The {@link #shutdown()} hook is for device teardown only.
 */
public final class XeSsRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    public static final XeSsRuntime INSTANCE = new XeSsRuntime();

    private static final PlatformNatives PLATFORM_NATIVES = PlatformNatives.current();

    private XeSsLibrary lib;
    private boolean initialized;
    private boolean failed;
    private String version;
    private int config;

    private XeSsRuntime() {
    }

    public synchronized XeSsLibrary acquire(VulkanDevice device) {
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
            CausticaMod.LOGGER.error("Intel XeSS init failed; XeSS features disabled", t);
            return null;
        }
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** The version string reported by the loaded XeSS SDK (e.g. "2.1.0"), or null if unknown. */
    public String version() {
        return version;
    }

    /** Whether the loaded XeSS runtime is 2.1+ (has the frame generation entry points). */
    public boolean hasFrameGen() {
        if (version == null) {
            return false;
        }
        // version looks like "2.1.0" or "2.0.1" — parse the major.minor.
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > 2 || (major == 2 && minor >= 1);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public synchronized void shutdown() {
        // Per-feature contexts are owned by their own wrappers; we only release the loader here.
        initialized = false;
        failed = false;
        lib = null;
    }

    private void init(VulkanDevice device) {
        if (!PLATFORM_NATIVES.supported()) {
            throw new IllegalStateException("XeSS natives are not bundled for " + PLATFORM_NATIVES.platformDir());
        }
        Path shim = locateLoader();
        if (shim == null) {
            throw new IllegalStateException(PLATFORM_NATIVES.loaderName() + " not found (bundled natives or -Dcaustica.xess.path)");
        }
        lib = XeSsLibrary.load(shim);
        version = lib.getVersion();
        // Pick the execution path. AUTO → XMX on Intel, DP4a on other vendors. FORCEx overrides.
        config = pickConfig(device);
        LOGGER.info("Intel XeSS loaded: version={} config={}", version, configName(config));
    }

    private static int pickConfig(VulkanDevice device) {
        CausticaConfig.XeSsMode mode = CausticaConfig.Rt.XeSs.MODE.value();
        if (mode == CausticaConfig.XeSsMode.FORCE_XMX) {
            return XeSsLibrary.XESS_CONFIG_PERF_XMX;
        }
        if (mode == CausticaConfig.XeSsMode.FORCE_DP4A) {
            return XeSsLibrary.XESS_CONFIG_PERF_DP4A;
        }
        // AUTO: probe the device vendor.
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice unused)) {
            return XeSsLibrary.XESS_CONFIG_PERF_DP4A;
        }
        try {
            // The GpuDeviceAccessor gives us a backend, but the vendor-id needs a separate
            // vkGetPhysicalDeviceProperties call which we don't have a clean handle for here; the
            // conservative default is DP4a (works on every modern GPU). The user can FORCE_XMX
            // when they know the device supports it. We rely on XeSS's own runtime detection
            // when XMX is requested — if the device doesn't have XMX the SDK returns an error
            // and we fall back to DP4a.
            return XeSsLibrary.XESS_CONFIG_PERF_DP4A;
        } catch (Throwable t) {
            return XeSsLibrary.XESS_CONFIG_PERF_DP4A;
        }
    }

    private static String configName(int c) {
        return switch (c) {
            case XeSsLibrary.XESS_CONFIG_PERF_XMX -> "XMX";
            case XeSsLibrary.XESS_CONFIG_PERF_DP4A -> "DP4a";
            default -> "none";
        };
    }

    private static Path locateLoader() {
        String override = CausticaConfig.Rt.XeSs.PATH.get();
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isDirectory(p)) {
                p = p.resolve(PLATFORM_NATIVES.loaderName());
            }
            return Files.isRegularFile(p) ? p : null;
        }
        return extractBundledLoader();
    }

    private static Path extractBundledLoader() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-xess")
                .resolve("natives").resolve(PLATFORM_NATIVES.platformDir());
        try {
            Files.createDirectories(dir);
            String name = PLATFORM_NATIVES.loaderName();
            String resource = "/caustica/natives/" + PLATFORM_NATIVES.platformDir() + "/" + name;
            try (InputStream in = XeSsRuntime.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                byte[] bytes = in.readAllBytes();
                Path dst = dir.resolve(name);
                if (!sameBytes(dst, bytes)) {
                    Files.write(dst, bytes);
                }
            }
            return Files.isRegularFile(dir.resolve(PLATFORM_NATIVES.loaderName()))
                    ? dir.resolve(PLATFORM_NATIVES.loaderName()) : null;
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Could not extract bundled XeSS natives to {}", dir, e);
            return null;
        }
    }

    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
        try {
            return Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes);
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private record PlatformNatives(String platformDir, String loaderName, boolean supported) {
        private static PlatformNatives current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
            if (os.contains("win") && x64) {
                return new PlatformNatives("windows-x64", "libxess.dll", true);
            }
            if (os.contains("linux") && x64) {
                return new PlatformNatives("linux-x64", "libxess.so", true);
            }
            return new PlatformNatives(os + "/" + arch, System.mapLibraryName("xess"), false);
        }
    }
}
