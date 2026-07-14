package dev.comfyfluffy.caustica.ffx.denoiser;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.OptionalInt;

/**
 * Loads the official FidelityFX Denoiser shim ({@code libffx_denoiser_caustica.so}).
 * Idempotent; latches failure so a missing library is not retried every frame.
 *
 * <p>P0: only {@link #probeVersion()} is required. Full context create lands with Shadow dispatch.
 */
public final class FfxDenoiserRuntime {
    public static final FfxDenoiserRuntime INSTANCE = new FfxDenoiserRuntime();

    private static final String RESOURCE = "/caustica/natives/linux-x64/libffx_denoiser_caustica.so";
    private static final String LIB_NAME = "libffx_denoiser_caustica.so";

    private FfxDenoiserLibrary lib;
    private boolean attempted;
    private boolean failed;
    private int versionPacked = -1;

    private FfxDenoiserRuntime() {
    }

    /**
     * Attempt to load the native library and call probe.
     * @return packed version if loaded, empty if unavailable
     */
    public synchronized OptionalInt tryLoad() {
        if (failed) {
            return OptionalInt.empty();
        }
        if (lib != null) {
            return OptionalInt.of(versionPacked);
        }
        attempted = true;
        try {
            Path so = resolveLibraryPath();
            if (so == null || !Files.isRegularFile(so)) {
                CausticaMod.LOGGER.info(
                        "Official FFX Denoiser native not found (build native/ffx_denoiser and bundle {}); denoise stays Noop",
                        LIB_NAME);
                failed = true;
                return OptionalInt.empty();
            }
            lib = FfxDenoiserLibrary.load(so);
            versionPacked = lib.probe();
            int major = versionPacked / 10000;
            int minor = (versionPacked / 100) % 100;
            int patch = versionPacked % 100;
            CausticaMod.LOGGER.info(
                    "Official FidelityFX Denoiser native loaded (API {}.{}.{}, packed={}) from {}",
                    major, minor, patch, versionPacked, so);
            return OptionalInt.of(versionPacked);
        } catch (Throwable t) {
            failed = true;
            lib = null;
            CausticaMod.LOGGER.warn("Official FFX Denoiser native load failed; denoise stays Noop", t);
            return OptionalInt.empty();
        }
    }

    public synchronized boolean isAvailable() {
        return lib != null && !failed;
    }

    public synchronized FfxDenoiserLibrary library() {
        return lib;
    }

    /** Packed version or -1 if not loaded. */
    public int versionPacked() {
        return versionPacked;
    }

    private static Path resolveLibraryPath() throws IOException {
        String override = System.getProperty("caustica.ffx.denoiser.path");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        // Extract from classpath jar to game dir natives cache (same idea as FSR).
        Path cache = FabricLoader.getInstance().getGameDir()
                .resolve(".caustica")
                .resolve("natives")
                .resolve("linux-x64");
        Files.createDirectories(cache);
        Path target = cache.resolve(LIB_NAME);
        try (InputStream in = FfxDenoiserRuntime.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                // Dev: also try repo build output / resources tree
                Path dev = Path.of("src/main/resources/caustica/natives/linux-x64").resolve(LIB_NAME);
                if (Files.isRegularFile(dev)) {
                    return dev.toAbsolutePath();
                }
                Path buildOut = Path.of("build/ffx_denoiser").resolve(LIB_NAME);
                if (Files.isRegularFile(buildOut)) {
                    return buildOut.toAbsolutePath();
                }
                return null;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true);
            return target;
        }
    }
}
