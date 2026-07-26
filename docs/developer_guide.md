# Developer Guide

## Toolchain

The normal build requires:

- JDK 25
- `glslangValidator`
- `spirv-val`
- Git

CMake, Ninja, and a C/C++ compiler are recommended. Gradle uses them to build
Caustica's optional portable JNI bridge, but failure to build that bridge is a
non-fatal fallback.

No NGX, DLSS, or XeSS SDK is required for a normal build.

## Windows

1. Install a JDK 25 distribution and set `JAVA_HOME`.
2. Install the LunarG Vulkan SDK. Its installer normally exposes
   `glslangValidator` and `spirv-val` through `VULKAN_SDK`/`PATH`.
3. Optionally install CMake and Ninja.
4. Build or run the development client:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
```

To rebuild the pinned NRD native on Windows, use
`scripts/build_nrd_windows.ps1` and follow the version/license requirements in
that script. The regular Java/shader build does not rebuild NRD automatically.

## Linux

Install Java 25, glslang, SPIR-V Tools, Vulkan headers/tools, CMake, and Ninja,
then run:

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
bash ./gradlew build
```

On CachyOS/Arch, `scripts/cachyos/build.sh` installs the build packages and runs
the same command. On NixOS:

```bash
nix develop
bash ./gradlew build
```

Run a development client with:

```bash
JAVA_TOOL_OPTIONS='-Xmx8G -Xss2M -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC' \
  bash ./gradlew runClient --args='--renderDebugLabels --graphicsBackend VULKAN'
```

## Shader build

`compileShaders` compiles `shaders/**` to generated SPIR-V and validates every
module for Vulkan 1.4:

```bash
bash ./gradlew compileShaders
```

Generated files are placed under `build/generated/shaders/caustica/rt/` and are
added to the main resources automatically. Do not hand-edit generated `.spv`
files.

## Optional native providers

Optional providers own their loading and capability probes. Missing or
incompatible native libraries must fail open to a portable implementation.
Current rebuild helpers include:

```bash
bash scripts/build_fsr2_classic_linux.sh
bash scripts/build_nrd_linux.sh
```

Review the scripts and the relevant third-party licenses before redistributing
their outputs. Do not add GPU-vendor routing to renderer call sites; expose a
provider-neutral capability on the denoise, upscaler, frame-generation, plate,
or presentation interface instead.

## Validation

Before submitting renderer changes, run:

```bash
bash ./gradlew compileJava compileShaders
python3 scripts/test_denoise_regressions.py
git diff --check
bash ./gradlew build
```
