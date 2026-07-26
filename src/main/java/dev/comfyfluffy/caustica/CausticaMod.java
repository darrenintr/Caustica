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

		// Phase 2 SDK header integration readback (2026-07-20). Calls into the C++ side
		// which #include <ffx_denoiser.h> and returns the FFX_DENOISER_VERSION macro
		// (e.g. "1.2.0 (10200)"). Proves the AMD FFX SDK headers are reachable from the
		// build. The SDK runtime is NOT linked yet — that needs prebuilt static libs
		// (libffx_denoiser_x64.a, libffx_backend_vk_x64.a) and SPIR-V shader blobs, both
		// of which are out of scope for this turn. If the SDK headers were missing at
		// build time the C++ returns a "unavailable" stub.
		String ffxVer = NativeBridge.tryLoadAndFfxVersion(LOGGER);
		if (ffxVer != null) {
			LOGGER.info("[caustica_native] ffxDenoiserVersion={}", ffxVer);
		}

		// Phase 3 verify (2026-07-20). Extracts libamd_fidelityfx_loader.so from the
		// caustica-fsr/natives/<platform>/ resource tree and asks the C++ side to
		// dlopen + dlsym the six AMD FFX 2.x modular API entry points. This is a
		// minimum-viable readback — we do not yet call any of those symbols; the
		// goal is to confirm the .so is reachable and the function pointers are
		// present on this machine before we attempt to use them. If anything is
		// missing, the GLSL denoise path stays the default.
		String amdFfxCheck = NativeBridge.tryCheckAmdFfxLoader(LOGGER);
		LOGGER.info("[caustica_native] {}", amdFfxCheck);

		// Phase 3 follow-up: minimum real call into the AMD FFX 2.x modular API.
		// dlopen the loader, ffxQuery for the denoiser effect (FFX_API_EFFECT_ID_DENOISER).
		// Tells us whether the loader actually exposes a usable denoiser effect, not
		// just six symbols. Still no Vulkan handles, no context, no dispatch.
		String amdFfxDenoiser = NativeBridge.tryCheckAmdFfxDenoiser(LOGGER);
		LOGGER.info("[caustica_native] {}", amdFfxDenoiser);
	}
}
