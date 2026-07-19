# RT / RDNA 3 architecture audit

## Decision

Do not rewrite the renderer wholesale. The core is already based on cross-vendor Vulkan KHR ray tracing,
has a no-SER shader variant, separates denoise/upscale backends, and has useful guide/split-lighting data.
The highest-risk failures come from implicit resource and descriptor contracts, not from an NVIDIA-only
ray-tracing algorithm. Replace those contracts incrementally, then profile the resulting RDNA 3 path.

## Black-screen findings fixed in this change

1. The new `B10G11R11` beauty chain uses `r11f_g11f_b10f` storage images, but device creation did not
   enable `shaderStorageImageExtendedFormats`. Format capability is not enough; the feature must be enabled.
2. `GUIDE_COUNT=23` occupies set 0 bindings 3 through 25, while the material sampler was also declared at
   binding 25. Java creates material/sky bindings 26, 27, and 28. The shader ABI is now aligned to those values.
3. FFX spatial ping-pong, its depth pyramid, and the bilateral residual repeatedly updated one descriptor set
   after recording earlier dispatches. Descriptor contents are execution-time state, not snapshots. Distinct
   binding tuples now use distinct sets and stable bindings are not rewritten every frame.
4. Reverse-infinite FSR2 was dispatched with `cameraFar=0`. The bundled SDK still uses min/max of near/far
   to derive its reverse-Z scale, so zero collapses that scale. The caller now supplies a positive far sentinel.
5. The FSR blackout guard performed the same 4x4 global probe in every output invocation. It now performs a
   two-load per-pixel fail-open, avoiding tens of millions of redundant image reads at 1080p.

## What should be refactored

### P0: generate the shader/host ABI

`RtComposite` owns 23 positional guide slots while `RtPipeline` separately derives following sampler bindings
and GLSL repeats raw integers. The binding-25 collision demonstrates that comments/tests are not a sufficient
ABI. Define one binding schema and generate both Java constants and a GLSL include. Add a validation test that
checks descriptor type, stage mask, format, and binding for every resource.

### P1: split the render graph out of `RtComposite`

`RtComposite.java` is about 2,800 lines and records trace, denoise, upscale, exposure, display mapping, copies,
HDR, frame generation, and overlays in one method/lifetime owner. Introduce explicit passes with declared
inputs, outputs, queue, and access state. This makes barriers derivable and lets failures fall back at a pass
boundary instead of corrupting the final plate.

Suggested boundaries:

`Trace -> Split lighting/guides -> Denoise -> Upscale -> Exposure -> Tone map -> Present`

### P1: make descriptors frame-safe by construction

The immediate FFX paths are fixed, but other backends still deserve an audit for descriptor updates after bind
and updates while previous frames are in flight. Prefer immutable per-size descriptor sets or a small
frames-in-flight ring. Do not depend on a driver copying descriptors during command recording.

### P2: real async compute only after graph extraction

The current compositor explicitly keeps denoise on the graphics queue; the `dispatchAsync` surface does not
split submissions. Once pass dependencies are explicit, overlap denoise/upscale compute with independent
graphics/streaming work using timeline semaphores. Adding a second queue before that would increase ownership
and synchronization risk without proving overlap.

### P2: profile-guided RDNA 3 specialization

Keep the portable no-SER path as the baseline. Profile wave32/wave64 occupancy, register pressure in the
2,600-line raygen shader, divergence at material/bounce branches, and bandwidth from reservoir/guide images.
Only then add RDNA-specific shader variants (for example subgroup-size control or smaller reservoir formats).
The packed HDR plate is a reasonable bandwidth optimization once its required feature is enabled; do not pack
signed motion, normals, hit distance, or multi-field histories merely to reduce bytes.

## Rewrite threshold

A full rewrite becomes justified only if the pass graph cannot be extracted without changing observable
rendering, or profiling shows the monolithic raygen design itself dominates RDNA 3 occupancy after descriptor,
synchronization, and bandwidth fixes. Current evidence does not meet that threshold. The recommended course is
an ABI/render-graph refactor that preserves the existing KHR tracer and replaces one pass boundary at a time.
