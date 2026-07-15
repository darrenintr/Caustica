# Hybrid FFX → NRD Denoise Pipeline

## Goal

AMD-playable path-traced image quality by combining:

1. **Official FidelityFX Denoiser** — shadows + reflections (what we already host as SPIR-V)
2. **NRD REBLUR** (Radiance-style) — diffuse/specular radiance with hit distance
3. **Beauty TAA** (optional residual) or **FSR only as upscaler** after a clean plate

## Why not “FFX beauty then NRD beauty”

| Bad cascade | Why it fails |
|-------------|----------------|
| Whole beauty → FFX → NRD | Double temporal, over-blur, NRD variance/hitDist model wrong |
| FSR temporal on raw PT | Rectifies MC noise; swim + grain (seen) |
| NRD alone on unsplit beauty | NRD expects **diff + spec + hitDist + viewZ** |

## Correct hybrid (layered)

```
world.rgen
  beauty            = U * S_raw + G + R_raw
  U  unshadowed primary direct
  S  primary shadow hit [0,1]
  G  diffuseOther (GI / multi-bounce / sky-diff / emission)
  R  specular / reflection
  hitDistDiff / hitDistSpec
  deviceZ (HW reverse-Z) + linear viewZ

        │
        ▼
┌───────────────────────┐
│ FFX prepass (layers)  │  only S and R
│  S_raw → S_clean      │
│  R_raw → R_clean      │
└───────────┬───────────┘
            │
            ▼
┌───────────────────────┐
│ prepare_nrd_inputs    │
│  D = U * S_clean + G  │  ← shadow already cleaned
│  Sp = R_clean         │  ← reflection pre-cleaned
│  pack hitDist, viewZ  │
└───────────┬───────────┘
            │
            ▼
┌───────────────────────┐
│ NRD REBLUR            │  Diffuse+Specular (Radiance default)
│  IN_DIFF / IN_SPEC    │
│  IN_MV / NORMAL / VIEWZ
└───────────┬───────────┘
            │
            ▼
┌───────────────────────┐
│ modulate + composite  │  albedo * demoded lighting + clearcoat extras
└───────────┬───────────┘
            │
            ▼
  Beauty TAA (1:1)  OR  FSR3/2 upscale only (no second PT denoise)
```

## What each stage owns

| Stage | Owns | Must not own |
|-------|------|----------------|
| FFX | Binary/noisy **shadow**, **specular spikes** | Full GI beauty |
| NRD | Residual **diffuse GI + specular** MC noise | Device-depth-only guides |
| TAA | Tiny residual / firefly | Being the only denoise |
| FSR | Spatial scale after clean plate | Raw SPP-1 temporal |

## Energy

Keep the existing FFX energy identity:

```
beauty = U * S_raw + G + R_raw
after FFX prepass conceptually:
  D_nrd = U * S_clean + G
  Sp_nrd = R_clean
```

Never multiply `G` or sky by shadow.

## Radiance reference

- Preset `RT_NRD_FSR`: RayTrace → **NRD** → FSR3 → ToneMap  
- They **disable** FFX Denoiser (`FFX_DENOISER OFF`) and use NRD as the only denoise.
- Our hybrid is **stricter on shadows/spec** (FFX first) then NRD for the rest — valid super-set if layers stay separate.

## Implementation status (2026-07-14)

| Phase | Status |
|-------|--------|
| rgen hitDist in layer alpha | done |
| FFX prepass + clean layer getters | done |
| prepare_nrd_inputs.comp | done |
| **NRD REBLUR native** (`libnrd_caustica.so`) | **done** — `native/nrd/`, build via `scripts/build_nrd_linux.sh` |
| Hybrid stage 3 dispatch + compose | done |
| Beauty TAA after hybrid | done |

## Fallback

If NRD create/dispatch fails (e.g. missing `VK_KHR_push_descriptor`): FFX composite + beauty TAA.  
If FFX fails: raw beauty + TAA.  

## Build NRD native

```bash
# Needs: cmake, g++, Vulkan SDK, dxc (downloaded by NRD)
bash scripts/build_nrd_linux.sh
JAVA_HOME=/usr/lib/jvm/zulu-25 bash gradlew jar
```
