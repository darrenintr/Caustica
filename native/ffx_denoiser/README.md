# Official FidelityFX Denoiser (Caustica native)

Integrates **AMD FidelityFX Denoiser 1.2** (Shadow + Reflection) for Caustica.

## Relationship to existing natives

| Library | Role |
| --- | --- |
| `libamd_fidelityfx_loader.so` | FSR 3 modular ABI (upscaler/FG); **not** the classic Denoiser API |
| `libffx_denoiser_caustica.so` (this project) | Thin C ABI over `ffxDenoiserContext*` |

There is also a proven Linux path that compiles the 8 official denoiser GLSL passes:

```bash
# From repo root (downloads FSR3 v3.0.4 sources if needed):
bash third_party/FidelityFX-SDK/build_linux_x64.sh
```

That script produces `libamd_fidelityfx_loader.so` with embedded denoiser shader blobs (see script header). Full create/dispatch still needs the C++ component linked via this shim with `CAUSTICA_FFX_DENOISER_LINK_SDK=ON`.

## Probe-only build (headers only, always works)

```bash
cmake -S native/ffx_denoiser -B build/ffx_denoiser -DCMAKE_BUILD_TYPE=Release
cmake --build build/ffx_denoiser -j
# → build/ffx_denoiser/libffx_denoiser_caustica.so
# Exports: caustica_ffx_denoiser_probe, create (stub), destroy
```

Copy into the mod (optional until Task 2 Java loader):

```bash
cp -f build/ffx_denoiser/libffx_denoiser_caustica.so \
  src/main/resources/caustica/natives/linux-x64/
```

## Full SDK link (Task 4+)

1. Build FidelityFX `ffx_denoiser` + `ffx_backend_vk` static libs (AMD CMake + shader blobs).
2. Reconfigure:

```bash
cmake -S native/ffx_denoiser -B build/ffx_denoiser \
  -DCAUSTICA_FFX_DENOISER_LINK_SDK=ON \
  -DCAUSTICA_FFX_LIB_DIR=/path/to/ffx_sdk/libs
cmake --build build/ffx_denoiser -j
```

## Toolchain

- CMake 3.17+
- g++ 12+ / clang
- For full shader compile: `glslc` (vulkan-tools)
