# Caustica FSR2 (classic Vulkan) native

Public AMD **FSR3 modular** loaders do **not** ship a Linux Vulkan upscaler provider.
This directory builds the **classic FSR2.2 host API** (`ffxFsr2ContextCreate` /
`ffxFsr2ContextDispatch`) against in-tree `third_party/FidelityFX-SDK` for Vulkan.

## Requirements

- FFX_SDK with FSR2 component + VK backend sources
- Generated shader permutation headers (via AMD `ffx_sc` **or** the simplified
  glslc+blob pipeline used by `third_party/FidelityFX-SDK/build_linux_x64.sh` for
  the denoiser — still WIP for FSR2)
- glslc, cmake, C++17

## Build

```bash
export FFX_SDK=/path/to/FidelityFX-SDK
bash scripts/build_ffx_vk_linux.sh
```

## Status

Scaffold only until permutation blobs are generated. Probe symbol
`caustica_ffx_fsr2_probe` always builds; full create/dispatch needs `CAUSTICA_FFX_FSR2_FULL=1`.
