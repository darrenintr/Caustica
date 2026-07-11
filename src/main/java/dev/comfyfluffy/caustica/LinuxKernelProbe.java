package dev.comfyfluffy.caustica;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux-only kernel / system probe, run once at mod init.
 *
 * <p>The renderer itself does not (and should not) make direct syscalls to
 * exploit mainline-kernel features — that is the JVM, Mesa, and the
 * vendor driver driver's job. What this class does is read the system
 * state that <i>does</i> matter for a Vulkan RT renderer and surface it in
 * the log so that the user (and CI) can verify the box is actually
 * configured the way they think it is.
 *
 * <p>Specifically, on a CachyOS-tuned box we want to confirm:
 * <ul>
 *   <li>Kernel version + whether the running kernel is the CachyOS-tuned one.</li>
 *   <li>Scheduler: EEVDF (≥6.6) or BORE / BORE-EEVDF (CachyOS patch).</li>
 *   <li>THP mode: "madvise" or "always" is acceptable; "never" defeats the
 *       JVM's {@code -XX:+UseTransparentHugePages} request.</li>
 *   <li>Cgroup v2 unified hierarchy (CachyOS default; ananicy-cpp needs this).</li>
 * </ul>
 *
 * <p>All reads are best-effort: a failure to read any single file is logged
 * as a debug message and the probe moves on. On non-Linux platforms the
 * entire class no-ops.
 */
public final class LinuxKernelProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    private LinuxKernelProbe() {
    }

    /**
     * Runs the probe. Cheap; intended to be called once at {@link CausticaMod#onInitialize()}.
     */
    public static void probe() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux")) {
            return;
        }
        LOGGER.info("Linux kernel probe starting");
        readKernelVersion();
        readScheduler();
        readTransparentHugepages();
        readCgroupVersion();
        readAnanicyHint();
        LOGGER.info("Linux kernel probe done");
    }

    private static void readKernelVersion() {
        String version = slurp("/proc/version");
        if (version == null) {
            return;
        }
        // /proc/version looks like: "Linux version 6.16.0-1-cachyos (builder@cachyos) (...)"
        // We just want to flag the cachyos / bore / eevdf markers so the log is greppable.
        boolean isCachyos = version.contains("cachyos");
        boolean isEEVDF   = version.contains("eevdf") || version.contains("bore");
        LOGGER.info("kernel: {} (cachyos={}, eevdf-or-bore={})",
                firstWord(version), isCachyos, isEEVDF);
    }

    private static void readScheduler() {
        // /sys/kernel/debug/sched_features is debugfs-only; if debugfs is not
        // mounted (common on hardened / locked-down boxes) the file won't
        // exist. We don't fail — the kernel version probe already says whether
        // EEVDF/BORE is the scheduler.
        Path features = Path.of("/sys/kernel/debug/sched_features");
        if (!Files.exists(features)) {
            LOGGER.debug("scheduler features not readable (debugfs not mounted); skipping");
            return;
        }
        String content = slurp(features.toString());
        if (content == null) {
            return;
        }
        // EEVDF's debugfs feature flag is "EEVDF_ENTITY" (not always present
        // post-6.12 because it's the default). We just report what's there.
        LOGGER.info("sched features: {}", content.trim().replace('\n', ' '));
    }

    private static void readTransparentHugepages() {
        // /sys/kernel/mm/transparent_hugepage/enabled reports the *current*
        // mode. "always" / "[madvise]" / "[never]" — the active mode is
        // bracketed.
        Path enabled = Path.of("/sys/kernel/mm/transparent_hugepage/enabled");
        String value = slurp(enabled.toString());
        if (value == null) {
            return;
        }
        // Extract the bracketed active mode: e.g. "always [madvise] never" -> "madvise"
        String active = "unknown";
        int open  = value.indexOf('[');
        int close = value.indexOf(']');
        if (open >= 0 && close > open) {
            active = value.substring(open + 1, close);
        }
        boolean thpGood = "always".equals(active) || "madvise".equals(active);
        LOGGER.info("THP: {} (jvm-hugepage-friendly={})", active, thpGood);
        if (!thpGood) {
            LOGGER.warn("THP mode is '{}' — JVM's -XX:+UseTransparentHugePages will be a no-op. " +
                    "Run 'echo madvise > /sys/kernel/mm/transparent_hugepage/enabled' (or use the " +
                    "sysctl shipped in scripts/cachyos/install.sh).", active);
        }
    }

    private static void readCgroupVersion() {
        // Cgroup v2 unified hierarchy is at /sys/fs/cgroup/cgroup.controllers
        // (v1 has a bunch of separate controllers). We use the existence of
        // cgroup.controllers as the v2 marker.
        Path v2 = Path.of("/sys/fs/cgroup/cgroup.controllers");
        if (Files.exists(v2)) {
            LOGGER.info("cgroup: v2 (unified)");
        } else {
            LOGGER.info("cgroup: v1 (legacy) — ananicy-cpp game cgroup rules may not apply");
        }
    }

    private static void readAnanicyHint() {
        // ananicy-cpp is typically running as a service. We just check whether
        // the daemon process is alive; we don't try to interpret its rules
        // from inside the JVM.
        boolean running = ProcessHandle.allProcesses()
                .anyMatch(p -> p.info().command().orElse("").endsWith("ananicy"));
        LOGGER.info("ananicy-cpp daemon: {}", running ? "running" : "not detected");
    }

    private static String slurp(String path) {
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (IOException | SecurityException | RuntimeException e) {
            LOGGER.debug("could not read {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String firstWord(String s) {
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }
}
