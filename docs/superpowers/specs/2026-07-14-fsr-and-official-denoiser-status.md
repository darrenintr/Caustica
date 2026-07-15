# FSR + Official FidelityFX Denoiser — Status & Plan

**Date:** 2026-07-14  
**Goal:** Real AMD temporal upscaling (playable motion) + official Denoiser (Shadow + Reflection).

## Reality check (Linux + Vulkan)

| Component | Public AMD status | Caustica today |
| --- | --- | --- |
| **FSR 3 / 4 modular loader** (`ffxCreateContext`) | Documented for DX12 kits; **Linux Vulkan upscaler provider not shipped as open source** | Bundled `libamd_fidelityfx_upscaler.so` is a **15KB stub** returning `NO_PROVIDER` |
| **FSR 3 host in FidelityFX-SDK 1.x tree** | Readme: **FSR3 Vulkan “in development”** — only DX12 backend | Cannot link real FSR3-VK from this tree |
| **FSR 2.2** | Full **Vulkan** GLSL + host API (`ffxFsr2Context*`) | Best real path for Linux VK upscale |
| **Denoiser 1.2** | Full **Vulkan** GLSL + host API | SPIR-V ports partial; **FULL host link** still needed |

## Bugs fixed / fixing

1. **Loader not found** — `FsrRuntime` extraction missed classloader roots → empty `caustica-fsr/`.  
   Fix: multi-source extract (class resource, TCCL, Fabric mod roots) + dev path fallbacks + logging.
2. **Overlay lies** — `geometry: fsr-3` is **config**, `upscaler: off/skip` is **resolved**.  
   TODO: overlay shows `resolvedMode`.
3. **Stub upscaler** — even with loader present, modular create context fails.  
   Path: classic **FSR2** shim as real provider (`native/ffx_fsr2`), selected when modular fails.

## Target architecture

```
Path trace (SPP-1)
    → beauty TAA (playable base)
    → Official FFX Denoiser (shadow + reflection masks / specular)  [native FULL]
    → FSR2 (or modular FSR when real .so exists) temporal upscale   [native FULL]
    → display / exposure
```

Energy-correct composite remains:
`beauty + unshadowed*(S_clean−S_raw) + (R_clean−R_raw)` then FSR2.

## Build steps (operators)

```bash
# 1) Official Denoiser + FSR2 Vulkan natives
export FFX_SDK="$PWD/third_party/FidelityFX-SDK"
bash scripts/build_ffx_vk_linux.sh

# 2) Copy into mod resources
cp build/ffx_vk/libffx_*_caustica.so src/main/resources/caustica/natives/linux-x64/

# 3) Package
JAVA_HOME=/usr/lib/jvm/zulu-25 bash gradlew jar
```

## Progress (2026-07-14 evening)

### Done
- Fixed modular loader **extraction** (classloader + Fabric roots + game-dir pre-seed)
- Built **classic FSR2.2 Vulkan** from GPUOpen `FidelityFX-FSR2`:
  - glslc single-permutation SPVs (HDR + reverse-Z + render-res MV)
  - `libffx_fsr2_caustica.so` (~340–700KB) with full `ffxFsr2*` + `caustica_ffx_fsr2_*` ABI
- Java: `Fsr2ClassicLibrary` + `Fsr2ClassicUpscaler`
- Selector: modular FSR fail → **classic FSR2**; AUTO also tries FSR2
- Overlay shows resolved upscaler + `cfg=`

### Still open
- Official Denoiser **FULL** `dispatchShadows` / `dispatchReflections` (still probe/SPIR-V path)
- FSR2: validate in-game create/dispatch; tune MV scale / jitter / near-far
- Vendor FSR2 build tree into `third_party/` for reproducible CI

## Success criteria

- Log: `Classic FSR2 native loaded`  
- Overlay: `upscaler: fsr-3/ran` (classic reports as fsr-3 mode key) — **not** `off/skip`  
- Denoise: official context create/dispatch returns FFX_OK; fail-open never black  
- Motion: clean enough to play; static snow near noise-free  

## Non-goals (this milestone)

- FSR 4.1 INT8 (needs closed AMD model binaries)  
- Frame Generation on Linux VK from stub FG .so  
- Replacing DLSS-RR on NVIDIA (still preferred when available)
