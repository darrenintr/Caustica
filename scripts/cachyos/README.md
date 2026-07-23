# Caustica on CachyOS / Arch

These helpers build and launch Caustica without selecting a GPU vendor or
forcing a vendor ICD.

## Files

- `build.sh` installs Java 25, Vulkan shader tools, CMake/Ninja/Clang, then runs
  the normal Gradle build. It does not clone DLSS, NGX, or XeSS SDKs.
- `install.sh` installs optional runtime helpers, the ananicy-cpp rule, and the
  `caustica-launch` wrapper. Kernel tuning can be skipped.
- `run-caustica.sh` applies JVM flags and optionally wraps the launcher with
  GameMode, ananicy-friendly priorities, and a manually selected NUMA node.
- `ananicy.d/50-caustica.rules` contains the process-priority rule.

## Build

```bash
./scripts/cachyos/build.sh
```

The regular mod JAR is written under `build/libs/`. The source tree already
contains the optional native resources intended for normal packaging; the build
helper does not fetch third-party SDKs or construct a vendor-specific fat JAR.

## Install and launch

```bash
# Runtime helpers and launcher wrapper. Add --skip-sysctl to avoid sysctl changes.
sudo ./scripts/cachyos/install.sh

# Copy the regular JAR into your instance.
cp build/libs/caustica-*.jar ~/.minecraft/mods/

# Launch through the optional wrapper.
caustica-launch prism-launcher
```

The wrapper accepts `--no-gamemode`, `--no-ananicy`, `-Xmx <size>`, and
`--numa <node|off>`. It deliberately leaves Vulkan ICD/device selection to the
Vulkan loader and the Minecraft backend.

## Drivers and HDR

Install the current Vulkan driver package appropriate for your GPU. Caustica
requires Vulkan ray tracing, but it does not require an NVIDIA driver or RTX
branding.

HDR normally requires a native Wayland session, compositor HDR support, an HDR
monitor, and a Vulkan surface that advertises the required HDR10/PQ format.
Caustica falls back to SDR when that presentation path is unavailable.

## Scheduling notes

CachyOS kernels, GameMode, and ananicy-cpp can reduce contention from background
processes, but they do not change renderer correctness and no performance uplift
is guaranteed. Start with neutral defaults, measure on the target machine, and
remove wrapper flags that make frame pacing worse.
