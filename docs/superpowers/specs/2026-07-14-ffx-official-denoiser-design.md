# Design: Official AMD FidelityFX Denoiser (Shadow + Reflection)

**Date:** 2026-07-14  
**Status:** Approved (user chose option B)  
**Scope:** Replace the hand-written whole-radiance “FFX” denoise path with a proper integration of **FidelityFX Denoiser 1.2** for **shadows** and **reflections**, including path-tracer signal splits and a failure-safe composite.

---

## 1. Problem statement

Caustica’s previous denoise path claimed “FFX” but was a **custom GLSL SVGF-style whole-radiance filter**. On AMD RADV (RX 7600) it produced:

- Pure black frames (filter wrote near-zero / poisoned buffers while Java still reported dispatch success)
- Multi-frame ghosting even when the camera was stationary
- No use of the official `FfxDenoiser*` API or SDK Vulkan backend

Meanwhile, **denoise OFF** shows correct path-traced output at SPP=1 (very noisy). Root cause is post-process integration and algorithm scope—not a dead ray tracer.

### What official FidelityFX Denoiser actually is

Per SDK docs (`third_party/FidelityFX-SDK/docs/techniques/denoiser.md` and `ffx_denoiser.h`):

| Module | Purpose | Not for |
| --- | --- | --- |
| **Shadow Denoiser** | Spatio-temporal denoise of a **shadow hit mask** (~1 jittered shadow ray/pixel) | Full-frame beauty |
| **Reflection Denoiser** | Spatio-temporal denoise of **reflection radiance** guided by roughness | Diffuse GI / whole-frame path tracing |

There is **no** official “path-traced beauty pass” denoiser equivalent to DLSS Ray Reconstruction in FidelityFX. This design integrates the two official modules correctly and composites with a split lighting model.

---

## 2. Goals and non-goals

### Goals

1. Build and ship **official** FidelityFX Denoiser (Vulkan backend) for Linux (and keep Windows build path viable).
2. Split path-tracer outputs so Shadow and Reflection denoisers receive **spec-compliant inputs**.
3. Composite: `final = diffuseLighting * shadowDenoised + reflectionDenoised + emissive/sky`.
4. **Never black-screen:** any missing native, create failure, or dispatch error falls back to raw (undenoised) split composite or raw legacy `output`.
5. Remove or hard-deprecate the hand-written whole-radiance `FfxDenoiseBackend` so it cannot be selected by default.
6. Keep current kill-switch behavior until P2+ is proven; then enable via config (`denoise.mode = ffx` / `AUTO` on AMD).

### Non-goals (this project)

- Using Reflection Denoiser on whole-frame radiance as a hack.
- Matching DLSS-RR quality on full-frame 1 spp.
- Denoising diffuse indirect light with FFX Denoiser (not its job).
- Frame generation or FSR upscaler changes (orthogonal).

### Residual noise expectation

After this work, **hard shadows and glossy reflections** should stabilize. **Diffuse snow / GI** may remain noisy at SPP=1; address later with higher SPP or a separate diffuse filter (e.g. NRD or a minimal spatial pass)—explicitly out of official FFX Denoiser scope.

---

## 3. Target architecture

```
                    ┌──────────────────────────────────────┐
                    │           world.rgen (split)           │
                    │  G-buffer: depth, normal, rough, MV    │
                    │  gShadowHit      (NEE visibility)      │
                    │  gReflection     (specular path only)  │
                    │  gDiffuse        (diffuse NEE+GI+emit) │
                    └───────────┬──────────────┬─────────────┘
                                │              │
              ┌─────────────────┘              └─────────────────┐
              ▼                                                  ▼
   ┌─────────────────────┐                          ┌────────────────────────┐
   │ Ffx Shadow Denoiser │                          │ Ffx Reflection Denoiser│
   │ prepare + tile +    │                          │ depth hierarchy (SPD)  │
   │ filter ×3           │                          │ tile list              │
   │ → shadowMaskClean   │                          │ reproject/prefilter/   │
   │                     │                          │ resolve temporal       │
   └──────────┬──────────┘                          │ → reflectionClean      │
              │                                     └───────────┬────────────┘
              └────────────────────┬────────────────────────────┘
                                   ▼
                        ┌────────────────────┐
                        │  denoise_composite │
                        │  final = diffuse   │
                        │    * shadowClean   │
                        │    + reflection    │
                        │    + sky/emissive  │
                        └─────────┬──────────┘
                                  ▼
                     exposure → display → present
                     (existing pipeline)
```

---

## 4. Path-tracer signal split (`world.rgen`)

### 4.1 New / redefined storage images (render resolution)

| Resource | Format | Contents |
| --- | --- | --- |
| `gShadowHit` | `r8_unorm` or `r32_uint` (if packing in rgen) | Per-pixel NEE sun visibility in {0,1} (or pre-packed 8×4 bitmask if we skip host prepare) |
| `gReflection` | `rgba16f` | Radiance from **specular/reflection path only** |
| `gDiffuse` | `rgba16f` | Diffuse direct (unshadowed or with raw shadow for debug), indirect diffuse, emissive, non-reflection sky contribution as appropriate |
| Existing guides | unchanged roles | `gDepth` (reversed-Z), `gNormal` (xyz + roughness in w), `gMotion`, `gAlbedo`, `gSpecAlbedo`, `gSpecMotion` |

Legacy single `output` buffer:

- During migration: still written as undenoised sum for debug views and fallback.
- After cutover: optional; composite writes the display path’s HDR buffer.

### 4.2 Path rules (normative)

1. **Primary hit** → write G-buffer (depth, normal, roughness, albedo, MV, etc.).
2. **NEE visibility** (`visibility()` / shadow ray) → write `gShadowHit` only; do **not** bake final denoised shadow into diffuse before the Shadow Denoiser runs.
3. **Specular / reflection** (when roughness ≤ configurable threshold and specular albedo significant) → trace reflection ray(s); accumulate into `gReflection` only.
4. **Diffuse NEE + path bounces** → accumulate into `gDiffuse`. Direct NEE may store *unshadowed* irradiance so composite can apply `shadowMaskClean`; or store fully lit diffuse with raw shadow and replace with denoised shadow via ratio—**prefer unshadowed diffuse × denoised shadow** for Shadow Denoiser correctness.
5. **Sky on primary miss** → either `gDiffuse` or a separate sky path that composite adds without shadowing.

Exact energy bookkeeping must preserve: undenoised sum ≈ diffuse×rawShadow + reflection + emissive/sky (within firefly clamps).

### 4.3 Descriptor / binding changes

- Extend `RtPipeline` storage image bindings for the three new images (or repurpose slots carefully).
- Update `RtComposite.ensureWorld` / `bindGuideImages` allocation and barriers after RT.

---

## 5. Official Denoiser integration

### 5.1 Native build

- **Source of truth:** `third_party/FidelityFX-SDK`
  - Component: `sdk/src/components/denoiser`
  - Vulkan backend: `sdk/src/backends/vk` with `FFX_DENOISER` defined
  - Shaders: `sdk/src/backends/vk/shaders/denoiser/*.glsl`
- **Build:** CMake target producing a Linux shared library consumed by the mod, e.g.  
  `libffx_denoiser_x64.so` (name TBD in plan), plus any required backend symbols.
- **Gradle:** task analogous to `bundleFsrNatives` that copies the built `.so` into  
  `src/main/resources/caustica/natives/linux-x64/` when `FFX_SDK` (or in-tree path) is available; skip with warn if not built (runtime Noop).
- **Windows:** same sources with DX12 or VK backend as a later milestone if not in P0.

### 5.2 Host API (official)

From `ffx_denoiser.h`:

- `ffxDenoiserContextCreate` / `ffxDenoiserContextDestroy`
- `ffxDenoiserContextDispatchShadows`
- `ffxDenoiserContextDispatchReflections`

Context description flags:

- `FFX_DENOISER_SHADOWS` and/or `FFX_DENOISER_REFLECTIONS`
- `FFX_DENOISER_ENABLE_DEPTH_INVERTED` when using reversed-Z (Caustica default)

**Important:** This is the classic **FfxInterface / component** API, **not** the FSR 2.x `ffxCreateContext` upscaler loader API used by `FsrLibrary`. A separate native bridge is required (see 5.3).

### 5.3 Java / FFM layer

New package: `dev.comfyfluffy.caustica.ffx.denoiser`

| Class | Role |
| --- | --- |
| `FfxDenoiserLibrary` | Load `.so`, bind create/destroy/dispatchShadows/dispatchReflections |
| `FfxDenoiserVkBackend` | Fill `FfxInterface` callbacks for Vulkan (device, command list, resources)—pattern from SDK `ffx_vk.cpp` samples / hybrid reflections |
| `FfxShadowDenoisePass` | Own shadow context resources, history, prepare packing if needed |
| `FfxReflectionDenoisePass` | Own reflection ping-pong radiance/variance, tile list, indirect args |
| `OfficialFfxDenoiseBackend` | Implements `CausticaDenoiseBackend`: ensureSized, dispatch (shadow → reflection → composite), resetHistory, destroy |
| `DenoiseCompositePass` | Compute shader: combine diffuse × shadow + reflection |

`DenoiseBackendSelector`:

- `FFX` / `AUTO` (when AMD or when official lib present) → `OfficialFfxDenoiseBackend` if library loads and context creates
- Else → `NoopDenoiseBackend` + log once
- Remove default selection of hand-written `FfxDenoiseBackend`

### 5.4 Shadow Denoiser inputs/outputs

**Dispatch** (`FfxDenoiserShadowsDispatchDescription`):

- `hitMaskResults` — packed 8×4 tiles (use SDK prepare pass from per-pixel `gShadowHit` if rgen does not pack)
- `depth`, `velocity` (motion), `normal`
- `shadowMaskOutput` — full-screen denoised shadow
- Matrices: projection inverse, reprojection, view-projection inverse; camera eye; `motionVectorScale`; depth similarity sigma
- Normals unpack mul/add matching Caustica’s normal encoding

**Motion vector convention:** world.rgen stores `(prevNdc - curNdc) * 0.5 * size` (current → previous, pixel units). Document and set `motionVectorScale` so SDK sees the space it expects (verify against hybrid sample / SDK comments; add a regression test that encodes the chosen sign).

### 5.5 Reflection Denoiser inputs/outputs

**Dispatch** (`FfxDenoiserReflectionsDispatchDescription`):

- `depthHierarchy` — full mip chain (generate via SPD or dedicated downsample pass each frame)
- `motionVectors`, `normal`
- `radianceA/B`, `varianceA/B` — ping-pong (host-owned or SDK-owned per backend rules)
- `extractedRoughness` — from `gNormal.w` or dedicated R8/R16 image
- `denoiserTileList`, `indirectArgumentsBuffer`
- `output` — denoised reflection
- `invProjection`, `invView`, `prevViewProjection`, roughness threshold, temporal stability, `reset` on hard cuts

Tile classification: initially a Caustica compute pass that fills tiles where roughness ≤ threshold and normal is valid; optionally later adopt FFX Classifier if needed.

### 5.6 Composite

```
final.rgb = gDiffuse.rgb * shadowMaskClean
          + reflectionClean.rgb
          + (sky/emissive terms if not already in gDiffuse)
```

- Firefly clamp remains consistent with `world.rgen` / display.
- Barriers: RT write → denoise reads; denoise writes → composite; composite → exposure.

---

## 6. Supporting passes (thin, Caustica-owned)

| Pass | Responsibility |
| --- | --- |
| Depth pyramid | Full mip depth for Reflection Denoiser |
| Shadow pack / prepare | Per-pixel hit → SDK bitmask if not packed in rgen |
| Reflection tile list | 8×8 tiles needing reflection denoise + indirect dispatch args |
| Denoise composite | Final HDR combine |
| History reset | On teleport / dimension / resource reload / denoise mode change—call SDK reset flags and clear history resources |

Hard cuts already partially handled in `RtComposite.invalidateHistory()`; extend to official contexts.

---

## 7. Failure handling (mandatory)

1. **Missing native library** → log warn; `OfficialFfxDenoiseBackend` never selected; display raw RT (or raw split composite without denoise).
2. **`ffxDenoiserContextCreate` fails** → destroy partial state; Noop path.
3. **Any dispatch returns non-`FFX_OK`** → that frame use raw shadow × diffuse + raw reflection; do not leave uninitialized `denoisedColor`.
4. **Before any filter write**, optional seed of output buffer with raw composite (defense in depth)—already learned from black-screen incidents.
5. **Hot-reload jar** shutdown crash (`NgxRuntime` ZipException): separate small fix—lazy/safe class load on shutdown so denoise work is not blocked; track as P0 hygiene.

---

## 8. Configuration and UX

| Key | Behavior |
| --- | --- |
| `denoise.mode = off` | No denoise; raw RT |
| `denoise.mode = ffx` | Official Shadow+Reflection when available; else Noop + warn |
| `denoise.mode = auto` | AMD (or lib present) → official; else Noop / future NRD |
| `denoise.mode = nrd` | Unchanged stub or future work |

Debug overlay:

- Show backend name: `ffx-official` / `noop` / etc.
- Optional debug views: raw shadow, denoised shadow, raw reflection, denoised reflection, diffuse only.

Keep kill-switch in `caustica$denoiseEnabled()` **until** P2 shadow path is validated in-game; then remove kill-switch and rely on Noop fallback.

---

## 9. Phased delivery

| Phase | Deliverable | Acceptance |
| --- | --- | --- |
| **P0** | Build Linux Denoiser lib; Java loads symbols; create+destroy context on device | Log: `FfxDenoiser context create OK` (or clean skip if not built) |
| **P1** | rgen + composite allocate and write `gShadowHit`, `gDiffuse`, `gReflection`; debug views | Three layers look plausible; undenoised sum matches old look |
| **P2** | Shadow Denoiser + composite uses denoised shadow | Soft shadows cleaner; **no black screen**; failure → raw shadow |
| **P3** | Depth pyramid + tile list + Reflection Denoiser + composite | Glossy/metal/water reflections less noisy; **no black screen** |
| **P4** | Delete/deprecate hand-written whole-radiance FFX; overlay; docs; regression tests | Default path is official or Noop only |

Each phase is mergeable and playable.

---

## 10. Testing strategy

1. **Unit / source regressions** (existing Python style): config keys, selector routes, MV sign contract, kill-switch removal gates.
2. **Native smoke:** create/destroy context without dispatch.
3. **In-game checklist (manual):**
   - Denoise off: noisy but visible (baseline).
   - Shadow only: sun soft edges stable under small camera motion.
   - Reflection only: metal/water less salt-and-pepper.
   - Hard cut (teleport): no multi-second ghost; reset works.
4. **Optional:** RenderDoc capture of one frame after P2/P3.
5. **Do not** claim success from “dispatch returned without Java exception” alone—visual + non-black mean/max luminance checks if readback is added later.

---

## 11. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Linux CMake/shader compile pain | Pin toolchain in `native/ffx_denoiser/README`; CI optional skip |
| FfxInterface binding complexity | Thin C++ shim that takes Vulkan handles + flat params; Java only calls few exports |
| MV / depth convention mismatch | Explicit tests + debug view; inverted depth flag |
| Diffuse still noisy after P3 | Document; SPP slider; future diffuse denoise |
| Regression black screen | Noop fallback; seed buffers; keep kill-switch until P2 proven |

---

## 12. Explicit decisions (locked)

1. **Scope B:** Shadow **and** Reflection official denoisers (not reflection-only).
2. **Not** whole-frame SVGF as “FFX”.
3. **Diffuse/GI** not fed to official Reflection Denoiser.
4. **Failure mode:** raw path, never black.
5. **API:** classic `FfxDenoiser*` + Vulkan backend, separate from FSR loader `.so` set.
6. **Phased P0–P4** implementation order: Shadow before Reflection (Shadow is fewer external resources).

---

## 13. References

- `third_party/FidelityFX-SDK/docs/techniques/denoiser.md`
- `third_party/FidelityFX-SDK/sdk/include/FidelityFX/host/ffx_denoiser.h`
- `third_party/FidelityFX-SDK/samples/hybridreflections/` (Classifier + Denoiser + apply)
- Existing Caustica FSR FFM pattern: `src/main/java/dev/comfyfluffy/caustica/fsr/`
- Current kill-switch: `RtComposite.caustica$denoiseEnabled()` (temporary)

---

## 14. Spec self-review notes

- No TBDs left for scope: residual diffuse noise is explicitly out of FFX Denoiser and deferred.
- Contradictions resolved: “FFX” config name maps to **official** backend, not hand-written filter.
- Single project scope: Shadow + Reflection + split + composite + native build; not NRD, not DLSS-RR changes.
- Ambiguity on diffuse×shadow energy: prefer **unshadowed diffuse × denoised shadow** as normative.
