package dev.comfyfluffy.caustica;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	}
}
