# Official FidelityFX Denoiser (Shadow + Reflection) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate AMD FidelityFX Denoiser 1.2 (official Shadow + Reflection modules) into Caustica with path-tracer signal splits and failure-safe composite—never black-screen.

**Architecture:** Split `world.rgen` into `gShadowHit` / `gDiffuse` / `gReflection`; build SDK Vulkan denoiser library; Java FFM binds `ffxDenoiserContext*`; dispatch shadows then reflections; composite shader combines layers. Hand-written whole-radiance FFX is removed from the default path.

**Tech Stack:** FidelityFX SDK (in-tree `third_party/FidelityFX-SDK`), CMake + glslang, Java 21+ FFM, Vulkan RT (existing Caustica), Gradle Fabric Loom.

**Spec:** `docs/superpowers/specs/2026-07-14-ffx-official-denoiser-design.md`

## Global Constraints

- Linux + RADV first (RX 7600); Windows VK backend secondary.
- Official API only: `ffxDenoiserContextCreate/Destroy/DispatchShadows/DispatchReflections`.
- Depth is reversed-Z → `FFX_DENOISER_ENABLE_DEPTH_INVERTED`.
- Motion: `(prevNdc - curNdc) * 0.5 * size` (pixel offset current→previous); document scale in dispatch.
- Normative energy: `unshadowedDiffuse * denoisedShadow + reflection + emissive/sky`.
- Any native/dispatch failure → raw path (visible, noisy); never leave black `denoisedColor`.
- Keep `caustica$denoiseEnabled()` kill-switch `return false` until Task group P2 is visually validated, then remove.
- Do not feed whole-frame beauty into Reflection Denoiser.

## File map

| Path | Role |
| --- | --- |
| `native/ffx_denoiser/` | CMake + thin C ABI shim exporting flat functions for Java |
| `src/main/resources/caustica/natives/linux-x64/libffx_denoiser_*.so` | Bundled runtime (build artifact) |
| `src/main/java/.../ffx/denoiser/*` | FFM library + OfficialFfxDenoiseBackend |
| `shaders/world/world.rgen` | Write split buffers |
| `shaders/display/denoise_ffx/denoise_composite.comp` | Final combine |
| `shaders/display/denoise_ffx/depth_pyramid.comp` | Depth mips for reflections |
| `shaders/display/denoise_ffx/reflection_tiles.comp` | Tile list for reflections |
| `src/main/java/.../rt/RtComposite.java` | Alloc buffers, call backend, barriers |
| `src/main/java/.../denoise/DenoiseBackendSelector.java` | Route to official backend |
| `scripts/test_ffx_official_denoiser.py` | Source regressions |

---

### Task 1: Native CMake project for Denoiser (P0)

**Files:**
- Create: `native/ffx_denoiser/CMakeLists.txt`
- Create: `native/ffx_denoiser/ffx_denoiser_shim.cpp`
- Create: `native/ffx_denoiser/ffx_denoiser_shim.h`
- Create: `native/ffx_denoiser/README.md`
- Test: `scripts/test_ffx_official_denoiser.py` (path existence + shim export names)

**Interfaces:**
- Produces C ABI (extern "C"):
  - `int caustica_ffx_denoiser_probe(void);` → 0 if linked OK
  - Later tasks add create/destroy/dispatch with Vulkan handles as `uint64_t`

- [ ] **Step 1: Add source regression for native layout**

```python
# scripts/test_ffx_official_denoiser.py
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def test_native_ffx_denoiser_cmake_exists():
    assert (ROOT / "native/ffx_denoiser/CMakeLists.txt").exists()
    cmake = (ROOT / "native/ffx_denoiser/CMakeLists.txt").read_text()
    assert "FFX_DENOISER" in cmake
    assert "ffx_denoiser" in cmake
```

- [ ] **Step 2: Write CMakeLists that builds ffx_denoiser + vk backend from in-tree SDK**

Point `FFX_SDK` default to `${CMAKE_SOURCE_DIR}/../../third_party/FidelityFX-SDK` when unset. Enable `FFX_API_VK=ON`, `FFX_API_DX12=OFF`, `FFX_DENOISER=ON`. Link shim against `ffx_denoiser_*` and `ffx_backend_vk_*`.

- [ ] **Step 3: Minimal shim**

```cpp
// ffx_denoiser_shim.cpp
#include "ffx_denoiser_shim.h"
#include <FidelityFX/host/ffx_denoiser.h>
extern "C" int caustica_ffx_denoiser_probe(void) {
    return FFX_DENOISER_VERSION_MAJOR * 10000
         + FFX_DENOISER_VERSION_MINOR * 100
         + FFX_DENOISER_VERSION_PATCH;
}
```

- [ ] **Step 4: Document build**

`README.md`: install Vulkan SDK / glslang; example:

```bash
cmake -S native/ffx_denoiser -B build/ffx_denoiser -DFFX_API_VK=ON -DFFX_DENOISER=ON
cmake --build build/ffx_denoiser -j
```

- [ ] **Step 5: Attempt local build; record failures in README if toolchain missing**

Run cmake build. If shaders need FFX SC tool, document binary path. Goal of Task 1 is structure that *can* build; full green build may need Task 1b fixes.

- [ ] **Step 6: Commit**

```bash
git add native/ffx_denoiser scripts/test_ffx_official_denoiser.py
git commit -m "build: scaffold official FidelityFX Denoiser native project"
```

---

### Task 2: Java probe of native library (P0)

**Files:**
- Create: `src/main/java/dev/comfyfluffy/caustica/ffx/denoiser/FfxDenoiserLibrary.java`
- Create: `src/main/java/dev/comfyfluffy/caustica/ffx/denoiser/FfxDenoiserRuntime.java`
- Modify: `build.gradle` (optional copy task for `libffx_denoiser*.so`)
- Test: extend `scripts/test_ffx_official_denoiser.py`

**Interfaces:**
- Produces: `FfxDenoiserRuntime.tryLoad() → OptionalInt versionPacked` (empty if missing .so)
- Consumes: resource path `/caustica/natives/linux-x64/` or `-Dcaustica.ffx.denoiser.path`

- [ ] **Step 1: Regression — package and class names exist after implementation**

```python
def test_java_ffx_denoiser_runtime_class():
    p = ROOT / "src/main/java/dev/comfyfluffy/caustica/ffx/denoiser/FfxDenoiserRuntime.java"
    assert p.exists()
    t = p.read_text()
    assert "tryLoad" in t
    assert "caustica_ffx_denoiser_probe" in t or "probe" in t
```

- [ ] **Step 2: Implement extract-from-jar + System.load + FFM probe** (mirror `FsrRuntime` extract pattern)

- [ ] **Step 3: Log once on success/failure from a safe call site** (e.g. first `DenoiseBackendSelector.resolve` or client init)—do not crash if missing

- [ ] **Step 4: Run `python3 scripts/test_ffx_official_denoiser.py` and `bash gradlew compileJava`**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ffx): load official Denoiser native probe from Java"
```

---

### Task 3: Path-tracer buffer split (P1)

**Files:**
- Modify: `shaders/world/world.rgen`
- Modify: `src/main/java/.../rt/pipeline/RtPipeline.java` (bindings)
- Modify: `src/main/java/.../rt/RtComposite.java` (allocate `gShadowHit`, `gDiffuse`, `gReflection`)
- Modify: debug overlay / `debugView` enums if needed
- Test: `scripts/test_ffx_official_denoiser.py` asserts rgen stores to new names

**Interfaces:**
- Produces images same res as `output`:
  - `gShadowHit` R8_UNORM (0 or 1 visibility)
  - `gDiffuse` RGBA16F
  - `gReflection` RGBA16F
- Energy: undenoised `output ≈ gDiffuse * gShadowHit + gReflection` (+ sky/emissive bookkeeping)

- [ ] **Step 1: Add regression for image names in rgen**

```python
def test_rgen_writes_split_lighting_buffers():
    src = (ROOT / "shaders/world/world.rgen").read_text()
    assert "gShadowHit" in src or "g_shadow" in src
    assert "gDiffuse" in src or "g_diffuse" in src
    assert "gReflection" in src or "g_reflection" in src
```

- [ ] **Step 2: Allocate + bind new storage images in RtComposite / RtPipeline**

- [ ] **Step 3: Change rgen path accumulation** to write the three buffers; keep legacy `output` as undenoised sum for fallback

- [ ] **Step 4: Add debug views** (e.g. 8=shadow, 9=diffuse, 10=reflection) for visual QA

- [ ] **Step 5: Build shaders + run client smoke** (denoise still kill-switched): world visible; debug layers sane

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(rt): split path tracer into shadow, diffuse, reflection buffers"
```

---

### Task 4: Shadow Denoiser dispatch (P2)

**Files:**
- Extend: `native/ffx_denoiser/ffx_denoiser_shim.cpp` (create/dispatchShadows/destroy with Vk handles)
- Create: `OfficialFfxDenoiseBackend.java` (shadow half first)
- Create: `shaders/display/denoise_ffx/denoise_composite.comp` (diffuse * shadow + raw reflection)
- Modify: `DenoiseBackendSelector`, remove kill-switch **only after** visual OK
- Test: regressions for composite formula and selector

**Interfaces:**
- Shim: `caustica_ffx_denoiser_create(device, physDev, flags, w, h, outCtx)`
- Shim: `caustica_ffx_denoiser_dispatch_shadows(ctx, cmd, hit, depth, mv, normal, outMask, ...matrices)`
- Backend `dispatch`: if official unavailable → copy raw composite to outColor

- [ ] **Step 1: Implement shadow-only context with `FFX_DENOISER_SHADOWS | DEPTH_INVERTED`**

- [ ] **Step 2: Pack hit mask** (prepare pass or rgen packing)

- [ ] **Step 3: Composite compute** using denoised shadow; seed outColor with raw before dispatch

- [ ] **Step 4: In-game validation** — sun soft edges; toggle off → noisier shadows; no black frame

- [ ] **Step 5: Remove kill-switch only if Step 4 passes**

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(ffx): integrate official Shadow Denoiser"
```

---

### Task 5: Reflection Denoiser (P3)

**Files:**
- Create: `shaders/display/denoise_ffx/depth_pyramid.comp`
- Create: `shaders/display/denoise_ffx/reflection_tiles.comp`
- Extend shim: `dispatch_reflections`
- Extend `OfficialFfxDenoiseBackend` for dual flags or second context
- Modify composite to add `reflectionClean`

**Interfaces:**
- Context flags: `FFX_DENOISER_SHADOWS | FFX_DENOISER_REFLECTIONS` (or two contexts if SDK requires)
- Resources: radiance/variance ping-pong, roughness, tile list, indirect args, depth hierarchy

- [ ] **Step 1: Depth pyramid each frame after RT**

- [ ] **Step 2: Tile list for reflective pixels**

- [ ] **Step 3: Dispatch reflections; composite adds reflection**

- [ ] **Step 4: resetHistory on teleport/dimension/reload**

- [ ] **Step 5: In-game validation** — metals/water; no black; hard cut OK

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(ffx): integrate official Reflection Denoiser"
```

---

### Task 6: Cleanup and docs (P4)

**Files:**
- Deprecate/delete hand-written whole-radiance denoise shaders path from selector
- Update `docs/developer_guide.md` with build + config
- Expand `scripts/test_ffx_official_denoiser.py` + denoise regressions
- Overlay shows `ffx-official` vs `noop`

- [ ] **Step 1: Selector only official or Noop**

- [ ] **Step 2: Developer guide section**

- [ ] **Step 3: Full regression suite green**

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(ffx): retire hand-written whole-radiance denoise; document official path"
```

---

## Spec coverage checklist

| Spec section | Task |
| --- | --- |
| Official Shadow + Reflection | 4, 5 |
| rgen split | 3 |
| Native build | 1 |
| Java FFM | 2, 4, 5 |
| Composite energy | 4, 5 |
| Failure → raw | 4, 5 |
| Deprecate fake FFX | 6 |
| Phased P0–P4 | Tasks 1–6 |
| Kill-switch until proven | 4 Step 5 |

## Placeholder scan

No TBD steps; residual diffuse noise deferred explicitly in Global Constraints / spec non-goals.
