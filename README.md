# Caustica

Caustica is an experimental client-side ray-traced renderer for Minecraft 26.2
on the Vulkan graphics backend. It replaces the vanilla world view with a
hardware ray-tracing pipeline while preserving Minecraft's UI and gameplay.

Caustica is early software. Expect missing visual cases, compatibility issues,
and frequent renderer changes.

![Caustica ray-traced Minecraft scene](docs/gallery/2026-07-09_21.25.14.jpg)

## Links

- [Discord](https://discord.gg/SeWCjyKu2)
- [Modrinth](https://modrinth.com/mod/caustica)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/caustica/preview)
- [Gallery](docs/gallery.md)

## Highlights

- Vulkan hardware ray-traced world rendering
- Cross-vendor Vulkan device bring-up based on capabilities rather than GPU names
- Modular denoise, upscale, frame-generation, material, and presentation stages
- LabPBR-style block and entity material support
- Dynamic entities, block entities, particles, and world overlays in the RT scene
- HDR10/PQ display output with SDR-to-PQ composition for Minecraft UI content
- Opacity micromaps where supported
- Shader invocation reordering through `VK_EXT_ray_tracing_invocation_reorder`,
  with a portable no-SER shader fallback

## Denoising

The denoiser is selected independently from the upscaler:

- `auto` capability-probes the bundled NRD path and falls back to the pure-SPIR-V
  bilateral denoiser when the native backend is unavailable.
- `nrd`, `hybrid`, and `relax` explicitly select NRD-based paths.
- `ffx` selects the FidelityFX shadow/reflection path.
- `bilateral` is the portable spatial-only fallback.
- `off` presents the raw RT result.

Optional native backends fail open; an unavailable library does not make the
renderer depend on a particular GPU vendor.

## Upscaling and frame generation

- `auto` uses Caustica's portable compute TAAU implementation.
- `fsr2` (legacy aliases such as `fsr-3` are accepted) probes the classic FSR2
  Vulkan provider and falls back to TAAU when it is unavailable.
- Unsupported legacy selector values are accepted for config compatibility and
  resolve to a supported portable fallback.
- Built-in Vulkan motion/depth frame generation is experimental, disabled by
  default, and can generate up to three intermediate frames per rendered frame.

Caustica does not include an NGX/DLSS runtime.

## Requirements

- Minecraft `26.2`
- Fabric Loader `0.19.3` or newer
- Fabric API
- Java `25`
- The Minecraft Vulkan graphics backend
- A GPU and driver exposing the Vulkan ray-tracing features required by Caustica
- An HDR-capable display, OS HDR mode, and an HDR-capable Vulkan surface for HDR output
- On Linux, a native Wayland session is normally required for HDR10/PQ surfaces

A LabPBR resource pack such as [SPBR](https://modrinth.com/resourcepack/spbr)
is recommended for richer material data, but is not required to start the renderer.

## Installation

1. Install Fabric Loader for Minecraft `26.2` and Fabric API.
2. Enable the Vulkan graphics backend.
3. Put the Caustica JAR in the Minecraft `mods` directory.
4. Launch the game and adjust Caustica in Video Settings or `config/caustica.toml`.

If Minecraft falls back to OpenGL after a crash, re-enable the Vulkan backend
before starting Caustica again.

## Building

Building requires Java 25 plus `glslangValidator` and `spirv-val`. CMake and
Ninja are recommended so Gradle can also build the optional portable JNI bridge.

```bash
export JAVA_HOME=/path/to/jdk-25
bash ./gradlew build
```

The regular mod JAR is written under `build/libs/`. No DLSS, NGX, or XeSS SDK is
required. See [the developer guide](docs/developer_guide.md) and the
[CachyOS/Arch guide](scripts/cachyos/README.md) for platform-specific setup.

## Usage notes

- Caustica is client-side only.
- HDR automatically falls back to SDR when the Vulkan surface cannot expose a
  compatible HDR10/PQ presentation format.
- Frame generation is experimental and disabled by default.
- On Linux, `-Xss2M` may help if a heavily modded instance reports a startup
  stack overflow.
- Other mods that replace world rendering, post-processing, or Vulkan backend
  internals may conflict. UI-only mods are more likely to work.

## Status

Current work focuses on visual correctness, world coverage, stability, portable
Vulkan behavior, and clean contracts between the renderer's pipeline modules.
No specific performance uplift is guaranteed across hardware or driver versions.

## License

Caustica's project-owned source code and documentation are licensed under the
GNU Lesser General Public License v3.0 or later. See [LICENSE.md](LICENSE.md),
[COPYING](COPYING), and [COPYING.LESSER](COPYING.LESSER).

Optional third-party native components retain their own license terms. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
