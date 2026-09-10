package dev.comfyfluffy.caustica.nativebridge;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cross-platform native library resolution helper.
 *
 * <p>Extracted from {@code NrdRuntime.NativePlatform} (was a private record) after
 * the Windows-only FSR2/FFX denoiser loaders were diagnosed as looking up
 * {@code /caustica/natives/linux-x64/libffx_fsr2_caustica.so} on a Windows host
 * because each loader had independently hardcoded the path / extension. NRD's
 * loader was platform-aware from the start; FSR2 / FSR3 / FSR4.1 / FFX denoiser
 * were not.
 *
 * <p>Callers pass the canonical Linux-style library name (the same one the
 * {@code /caustica/natives/linux-x64/} JAR resource uses); on Windows the
 * {@code lib} prefix is stripped and the {@code .so} extension is replaced with
 * {@code .dll} to derive the matching Windows filename. Linux ARM / macOS /
 * 32-bit x86 all return {@code null} -- call sites should treat that as
 * "library unavailable" and fall back.
 */
public final class NativePlatform {
    private static final Pattern LIB_PREFIX = Pattern.compile("^lib");
    private static final Pattern SO_EXT = Pattern.compile("\\.so$");

    private final String resourceDirectory;
    private final String libraryName;
    private final boolean windows;

    private NativePlatform(String resourceDirectory, String libraryName, boolean windows) {
        this.resourceDirectory = resourceDirectory;
        this.libraryName = libraryName;
        this.windows = windows;
    }

    public String resourceDirectory() { return resourceDirectory; }
    public String libraryName() { return libraryName; }
    public boolean isWindows() { return windows; }

    /** JAR resource path, e.g. {@code /caustica/natives/windows-x64/ffx_fsr2_caustica.dll}. */
    public String resourcePath() {
        return "/caustica/natives/" + resourceDirectory + "/" + libraryName;
    }

    /**
     * Resolve the platform-specific {@link NativePlatform} for the given canonical
     * Linux-style library filename. Returns {@code null} if the current OS / arch
     * has no shipped native.
     */
    public static NativePlatform forLibrary(String linuxLibName) {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64"))) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // libfoo_bar.so -> foo_bar.dll
            String stripped = LIB_PREFIX.matcher(linuxLibName).replaceFirst("");
            stripped = SO_EXT.matcher(stripped).replaceFirst(".dll");
            return new NativePlatform("windows-x64", stripped, true);
        }
        if (os.contains("linux")) {
            return new NativePlatform("linux-x64", linuxLibName, false);
        }
        return null;
    }
}