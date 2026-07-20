package dev.comfyfluffy.caustica.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import dev.comfyfluffy.caustica.CausticaMod;

/**
 * Java side of the libcaustica_native JNI bridge.
 *
 * <p>Phase 1 (2026-07-20): a single {@code ping()} round-trip to prove the cmake → ninja →
 * jar → extract → System.load(absolute) → JNI call chain works end-to-end without
 * touching any AMD FFX / NRD / Vulkan SDK. The native lib only writes a marker to
 * stderr on {@code JNI_OnLoad} and returns a fixed UTF string; everything else stays
 * in the GLSL pipeline so we can isolate "is the toolchain right?" from "does FFX on
 * AMD actually work?".
 *
 * <h2>Loading model</h2>
 *
 * {@link #tryLoad(Logger)} extracts the bundled {@code libcaustica_native.so} from the
 * JAR to {@code <gameDir>/caustica-native/natives/<platform>/}, then loads it by
 * absolute path. This follows the pattern Caustica's {@code NgxRuntime} already uses
 * for its NGX/DLSS shim — Fabric does not put JAR-bundled natives on
 * {@code java.library.path} automatically, so {@code System.loadLibrary(name)} would
 * silently fail on a typical player install.
 *
 * <h2>Failure containment</h2>
 *
 * The user constraint was "no failure, only success" — i.e. MC must keep booting
 * with or without the native half. Every fallible step is wrapped in
 * {@code catch (Throwable t)}: a missing lib, mismatched symbols, file-permission
 * flakiness, even an in-flight security manager all degrade to
 * {@link #isLoaded()} == {@code false} plus an entry in {@link #getLoadError()}. Nothing
 * thrown by this class can bubble out of mod init.
 */
public final class NativeBridge {

    private static final String LIB_NAME = "caustica_native";
    private static final String RESOURCE_PATH = "/caustica/natives/" + platformDir() + "/lib" + LIB_NAME + ".so";
    private static final String GAME_DIR_RELATIVE =
            "caustica-native/natives/" + platformDir() + "/lib" + LIB_NAME + ".so";

    private static volatile boolean LOADED = false;
    private static volatile String LOAD_ERROR = null;
    private static volatile Path EXTRACTED_LIB = null;

    private NativeBridge() {
    }

    /**
     * Idempotent. Extracts the bundled lib (if needed) and {@code System.load}s it.
     * Logs a single warning if anything goes wrong but never propagates a throwable.
     */
    public static void tryLoad(Logger logger) {
        if (LOADED) {
            return;
        }
        try {
            Path lib = locateOrExtract();
            if (lib == null) {
                LOAD_ERROR = "libcaustica_native.so not present in JAR resources";
                if (logger != null) {
                    logger.warn("[caustica_native] not bundled in JAR; continuing with GLSL fallback");
                }
                return;
            }
            System.load(lib.toAbsolutePath().toString());
            LOADED = true;
            EXTRACTED_LIB = lib;
            if (logger != null) {
                logger.info("[caustica_native] loaded from {}", lib);
            }
        } catch (UnsatisfiedLinkError e) {
            // Symbol mismatch (e.g. wrong -fvisibility, stale .so) or a corrupt extract.
            LOAD_ERROR = e.getMessage() != null ? e.getMessage() : e.toString();
            if (logger != null) {
                logger.warn("[caustica_native] System.load failed; continuing with GLSL fallback: {}",
                        LOAD_ERROR);
            }
        } catch (Throwable t) {
            // Defensive: SecurityException, IOException, anything.
            LOAD_ERROR = t.toString();
            if (logger != null) {
                logger.warn("[caustica_native] load failed; continuing with GLSL fallback", t);
            }
        }
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static String getLoadError() {
        return LOAD_ERROR;
    }

    public static Path getExtractedLib() {
        return EXTRACTED_LIB;
    }

    /**
     * Round-trip through JNI. Returns the static "pong-v1" string the C++ side emits
     * (the "v1" suffix lets a caller assert that this is the build it expects).
     *
     * <p>Throws {@link UnsatisfiedLinkError} if {@link #tryLoad} has not run or
     * failed. Callers should gate with {@link #isLoaded()} first.
     */
    public static native String ping();

    /**
     * Returns the AMD FidelityFX Denoiser SDK version this build was compiled against,
     * as a human-readable string (e.g. {@code "1.2.0 (10200)"}). The C++ side reads
     * the {@code FFX_DENOISER_VERSION} macro from {@code ffx_denoiser.h}, which encodes
     * major*10000 + minor*100 + patch.
     *
     * <p>Phase 2 (2026-07-20) readback: the SDK runtime is NOT linked yet, so this
     * method only proves the header is reachable + its compile-time version constant is
     * visible to the .so. If the SDK headers were missing at build time (the
     * FFX_SDK_PRESENT macro in CMakeLists.txt was 0), the C++ side returns a stub
     * string explaining why.
     *
     * <p>Throws {@link UnsatisfiedLinkError} if {@link #tryLoad} has not run or
     * failed. Callers should gate with {@link #isLoaded()} first.
     */
    public static native String ffxDenoiserVersion();

    /**
     * Convenience: {@link #tryLoad(Logger)} then {@link #ping()}. Returns
     * {@code null} on any failure. Used from {@code CausticaMod.onInitialize()}.
     */
    public static String tryLoadAndPing(Logger logger) {
        tryLoad(logger);
        if (!LOADED) {
            return null;
        }
        try {
            return ping();
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("[caustica_native] ping() threw after a clean load — unexpected", t);
            }
            return null;
        }
    }

    /**
     * Convenience: {@link #tryLoad(Logger)} then {@link #ffxDenoiserVersion()}.
     * Returns the SDK version string from the C++ readback, or {@code null} if the
     * native half is unavailable.
     */
    public static String tryLoadAndFfxVersion(Logger logger) {
        tryLoad(logger);
        if (!LOADED) {
            return null;
        }
        try {
            return ffxDenoiserVersion();
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("[caustica_native] ffxDenoiserVersion() threw after a clean load", t);
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // extraction
    // ---------------------------------------------------------------------

    private static Path locateOrExtract() throws IOException {
        // 1) Resolve where the lib should live on disk (same path NgxRuntime uses).
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path target = gameDir.resolve(GAME_DIR_RELATIVE);

        // 2) Extract the bundled lib from the JAR. We don't short-circuit on an
        //    already-existing file: the .so shipped inside the JAR can change between
        //    builds (Phase 1 had a 15648-byte ping-only stub, Phase 2 added the
        //    ffxDenoiserVersion symbol at 15856 bytes). If we returned early on file-
        //    existence we'd silently load the stale copy from a previous run. The
        //    cost of always re-writing is one small (~15 KB) write per boot.
        try (InputStream in = NativeBridge.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return null;
            }
            Files.createDirectories(target.getParent());
            byte[] bytes;
            try (InputStream copy = in) {
                bytes = copy.readAllBytes();
            }
            Files.write(target, bytes);
            return target;
        }
    }

    private static String platformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        boolean aarch64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("linux") && x64) {
            return "linux-x64";
        }
        if (os.contains("linux") && aarch64) {
            return "linux-arm64";
        }
        if (os.contains("windows") && x64) {
            return "windows-x64";
        }
        if (os.contains("windows") && aarch64) {
            return "windows-arm64";
        }
        if (os.contains("mac") && aarch64) {
            return "macos-arm64";
        }
        if (os.contains("mac") && x64) {
            return "macos-x64";
        }
        // Last-resort: let the resource look-up fail with a clear "not bundled" log.
        return "unknown";
    }

    // ---- CausticaMod boot entry point ----
    //
    // Wrapped so the mod's main class doesn't need to know about NativeBridge directly.

    public static String tryLoadAndPingViaProjectLogger() {
        return tryLoadAndPing(CausticaMod.LOGGER);
    }
}
