# Tile-Jitter Guide — NRD Pre-warp (v0.6.8+)

## Why

`world.rgen` applies a per-tile sub-pixel jitter to the primary ray for software SER
substitution on hardware without `VK_NV_/VK_EXT_ray_tracing_invocation_reorder`. The
pattern is 8x8 tiles with a ±~0.055 px (1/64 step) quantized offset per tile, hashed
by `tilePix ⊕ frameIndex` so adjacent tiles land in different sub-pixel positions
every frame.

`pc.jitter` (the frame-global Halton-style jitter) is what TAAU / NRD see. Without
intervention, NRD's internal reproject does:

```
prevUV = currentUV + mvec - (jitter - jitterPrev)
```

But the current frame was actually rendered with `jitter + tileJitter`, so the
correct reproject is:

```
prevUV = currentUV + mvec - (jitter + tileJitter - jitterPrev - tileJitterPrev)
       = currentUV + mvec - (jitter - jitterPrev) - (tileJitter - tileJitterPrev)
```

Without the `tileJitter` terms, NRD samples the previous frame at the wrong sub-pixel
position by the per-tile component — visible as subtle ghosting under camera motion.

## The fix (option b — pre-warp the NRD current input)

Rather than poking NRD's internal `permanentPool` (which the API does not expose for
direct write) or baking the offset into the motion vector (which loses pure
world-space motion semantics needed for FSR3 / DLSS-RR later), we re-sample the
current frame's NRD input by `-tileJitter/size` before NRD sees it.

After the pre-warp, the radiance in `nrdDiffWarped` / `nrdSpecWarped` represents
the world point at the unjittered pixel position, so NRD's reproject math lines up
exactly without any changes to the SDK call or the motion vector.

## Data flow

```
world.rgen  ── writes tileJitter ──► gJitterGuide (R8G8_UNORM, render res)
world.rgen  ── radiance ──► (existing) gDiffuse / gReflection
prepare_nrd_inputs.comp ──► nrdDiff / nrdSpec (RGBA16F + hitDist.a)
nrd_prewarp.comp  ── re-samples at -tileJitter/size ──► nrdDiffWarped / nrdSpecWarped
nrd_reblur  ── reads nrdDiffWarped / nrdSpecWarped ──► nrdOutDiff / nrdOutSpec
```

## Storage format

`gJitterGuide`: R8G8_UNORM, render resolution.

| Encoded value | Decoded (render pixels) |
|---------------|------------------------|
| 0.0           | -0.5                   |
| 0.5           |  0.0                   |
| 1.0           | +0.5                   |

Encoding: `encoded = tileJitter + 0.5` (clamped to [0, 1]).
Decoding:  `tileJitter = encoded - 0.5`.

Precision: 1/256 px, well under the 1/64 px quantisation step of the current
scheme (headroom for finer schemes later — e.g. 1/128 if we ever bump the scheme).

## Why option (b) and not (a)

| Option | Mechanism | Pros | Cons |
|--------|-----------|------|------|
| (a) Bake into MV | Add `(tileJitterPrev − tileJitterCurr) / size` to the motion vector before NRD sees it | Minimal shader change, one pass | Motion vector loses pure-world-space semantic; future FSR3 / DLSS-RR replacements would have to undo this, and the current path's debug visualisations of "raw MV" would mislead |
| (b) Pre-warp NRD input *(this)* | Re-sample `nrdDiff` / `nrdSpec` by `-tileJitter/size` | Motion vector stays pure world-space; NRD sees an "as if unjittered" current frame and does the right thing | One new compute pass per frame, ~50 MB of bandwidth at 4K render res (<0.1 ms) |

(b) was chosen for semantic cleanliness and to keep the door open for FSR3 / DLSS-RR.

## Cost

- 1 R8G8 sampler read + 2 RGBA16F bilinear samples + 2 RGBA16F writes per output pixel
- At 1920x1080 render res: ~16 MB read + ~32 MB write = ~48 MB per frame
- At 4K render res: ~64 MB read + ~128 MB write = ~192 MB per frame
- On a modern GPU: bandwidth-bound, <0.1 ms

## Files touched

- `shaders/world/world.rgen` — apply `tileJitter` to `effectiveJitter`, write to `gJitterGuide` (binding 26, R8G8)
- `shaders/display/denoise_ffx/nrd_prewarp.comp` — new compute pass
- `src/main/java/.../rt/RtComposite.java` — `GUIDE_COUNT` 23 → 24, add `gJitterGuide`, create + bind slot 23
- `src/main/java/.../denoise/HybridFfxNrdBackend.java` — `setJitterGuide(...)`, `nrdDiffuseWarped` / `nrdSpecularWarped`, prewarp pipeline, dispatch wired between `prepare_nrd_inputs` and NRD

## Verification

- `gradle compileShaders` (Build Successful, both new + modified shaders)
- `spirv-val --target-env vulkan1.4` on `nrd_prewarp.comp.spv` and `world.rgen.spv` (OK)
- Visual: spin a loaded chunk under RT lighting; should see the 1/8 px reprojection
  ghosting at edges disappear compared to v0.6.7 (no pre-warp, tileJitter overridden
  to `pc.jitter`).

## What's still on the to-do

`taau.comp` and `ffx_reproject.comp` were also patched in v0.6.8 (see
commits / file list above). They now read `gJitterGuide` at the reprojection
target and add the per-tile offset to `prevUV` — the same pattern as the
NRD pre-warp. `Upscaler.setJitterGuide()` is a default no-op on the
interface so non-TAAU upscalers (DLSS-RR, FSR, XeSS) don't need to care.

The OfficialFfxDenoiseBackend's own `shadow_reproject.comp` and
`reflection_reproject.comp` reproject paths were not patched — they're
not currently in the active dispatch chain (v0.6 FFX disabled, NRD-only
path). When FFX is re-enabled, apply the same pattern there.
