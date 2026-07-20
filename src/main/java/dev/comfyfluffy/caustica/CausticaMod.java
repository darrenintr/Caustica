package dev.comfyfluffy.caustica;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.comfyfluffy.caustica.nativebridge.NativeBridge;

public final class CausticaMod implements ModInitializer {
	public static final String MOD_ID = "caustica";
	public static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

	@Override
	public void onInitialize() {
		// Register every setting (applying TOML file values) and write a default config on first run.
		CausticaConfig.ensureRegistered();
		CausticaConfig.saveIfMissing();
		// Probe the host kernel / scheduler / cgroup state on Linux. Logs the running
		// kernel version, THP mode, and ananicy-cpp presence so the user can confirm
		// the box is configured the way scripts/cachyos/install.sh set it up. No-op on
		// other platforms.
		LinuxKernelProbe.probe();
		LOGGER.info("Caustica initialized (common); config: {}", CausticaConfig.configPath());

		// Phase 1 native bridge probe (2026-07-20). Loads libcaustica_native.so from the JAR's
		// resources and round-trips a single JNI ping(). The C++ side is currently a stub
		// (writes "[caustica_native] JNI_OnLoad fired" + returns "pong-v1"); it does NOT
		// touch any AMD/NRD/Vulkan SDK because those headers aren't on the build host. The
		// bridge is wired through here so we can prove the cmake → ninja → jar → extract →
		// System.load → JNI call pipeline end-to-end. If anything fails (missing lib,
		// symbol mismatch, security manager), NativeBridge swallows it; Caustica
		// continues with the GLSL denoise path as it always has.
		String pong = NativeBridge.tryLoadAndPingViaProjectLogger();
		if (pong != null) {
			LOGGER.info("[caustica_native] ping={}", pong);
		} else {
			LOGGER.info("[caustica_native] native half unavailable; using GLSL fallback");
		}
	}
}
