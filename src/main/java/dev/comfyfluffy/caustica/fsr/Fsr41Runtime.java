package dev.comfyfluffy.caustica.fsr;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.comfyfluffy.caustica.nativebridge.NativePlatform;
import java.util.OptionalInt;

/**
 * Resolves the FSR 4.1 / FFX 4.x modular shim
 * ({@code libffx_fsr41_caustica.so}). Idempotent; latches failure so
 * a missing library is not retried every frame.
 *
 * <p>Extraction logic mirrors {@code Fsr2ClassicUpscaler.resolveLibrary}:
 * prefer a system property override, then extract from the JAR's
 * {@code /caustica/natives/linux-x64/} resource path, then fall back
 * to a developer build-output path. Hash check guarantees a stale SO
 * with version-mismatched symbols never silently runs.
 */
public final class Fsr41Runtime {
    public static final Fsr41Runtime INSTANCE = new Fsr41Runtime();

    /** Canonical Linux-style library name; resolved via {@link NativePlatform#forLibrary}. */
    private static final String LIB_NAME = "libffx_fsr41_caustica.so";

    private Fsr41Library lib;
    private boolean attempted;
    private boolean failed;
    private int versionPacked = -1;

    private Fsr41Runtime() {
    }

    /**
     * Attempt to load the shim and call probe.
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
                        "FSR 4.1 native shim {} not found; FSR 4.1 path will stay disabled",
                        LIB_NAME);
                failed = true;
                return OptionalInt.empty();
            }
            lib = Fsr41Library.load(so);
            versionPacked = lib.probe();
            int major = versionPacked / 10000;
            int minor = (versionPacked / 100) % 100;
            int patch = versionPacked % 100;
            CausticaMod.LOGGER.info(
                    "FSR 4.1 shim loaded (FFX {}.{}.{}, packed={}) from {}",
                    major, minor, patch, versionPacked, so);
            return OptionalInt.of(versionPacked);
        } catch (Throwable t) {
            failed = true;
            lib = null;
            CausticaMod.LOGGER.warn("FSR 4.1 shim load failed; path disabled", t);
            return OptionalInt.empty();
        }
    }

    public synchronized boolean isAvailable() {
        return lib != null && !failed;
    }

    public synchronized Fsr41Library library() {
        return lib;
    }

    /** Packed version or -1 if not loaded. */
    public int versionPacked() {
        return versionPacked;
    }

    private static Path resolveLibraryPath() throws IOException {
        String override = System.getProperty("caustica.fsr41.path");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        // 2026-08-06: platform-aware via NativePlatform.
        NativePlatform platform = NativePlatform.forLibrary(LIB_NAME);
        if (platform == null) {
            return null;
        }
        // Extract from classpath jar to game dir natives cache (same idea as FSR2).
        Path cache = FabricLoader.getInstance().getGameDir()
                .resolve("caustica-fsr")
                .resolve("natives")
                .resolve(platform.resourceDirectory());
        Files.createDirectories(cache);
        Path target = cache.resolve(platform.libraryName());
        try (InputStream in = Fsr41Runtime.class.getResourceAsStream(platform.resourcePath())) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                // Always overwrite: a size-only check would mask a stale
                // .so that links against an older FFX ABI.
                boolean rewrite = !Files.isRegularFile(target) || Files.size(target) != bytes.length;
                if (!rewrite && Files.isRegularFile(target)) {
                    byte[] existing = Files.readAllBytes(target);
                    rewrite = existing.length != bytes.length
                            || !java.util.Arrays.equals(existing, bytes);
                }
                if (rewrite) {
                    Files.write(target, bytes);
                    if (!platform.isWindows()) target.toFile().setExecutable(true);
                    CausticaMod.LOGGER.info("Extracted FSR 4.1 shim to {} ({} bytes)", target, bytes.length);
                }
                return target;
            }
        }
        if (Files.isRegularFile(target) && Files.size(target) > 50_000) {
            return target;
        }
        Path dev = Path.of("src/main/resources/caustica/natives")
                .resolve(platform.resourceDirectory()).resolve(platform.libraryName());
        return Files.isRegularFile(dev) ? dev.toAbsolutePath() : null;
    }
}
