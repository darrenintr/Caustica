# Upscaler and Frame-Generation Architecture

This document replaces the old vendor-SDK roadmap. It describes the current
provider contracts and the rules for adding another implementation.

## Current providers

### Upscaling

- **TAAU**: portable compute implementation and the default for `auto`.
- **Classic FSR2 Vulkan**: optional native provider selected by `fsr2`; failure
  to load or dispatch falls back to TAAU.
- **No-op**: explicit `off` or the final fail-open path.

Legacy config names remain parser aliases only. They must not cause a removed SDK
to be fetched or loaded.

### Frame generation

- **Vulkan motion/depth frame generation**: built in, experimental, disabled by
  default, and limited to three generated frames per rendered frame.
- **No-op**: universal fallback.

Frame generation consumes the resolved upscaler/display contract rather than a
GPU-vendor enum.

## Provider contract

New upscalers implement `Upscaler` and declare capabilities such as:

- input, output, and HDR display color formats
- reactive-mask and blackout-guard requirements
- jitter convention
- integrated sharpening
- fail-open state

New frame generators implement `FrameGen` and are selected through
`FrameGenSelector`. Presentation code must interact only with these interfaces.

The shared `RtPlateBridge` owns format conversion between RT composition,
denoising, upscaling, and presentation. A provider must not create a competing
bridge or introduce a concrete-type check into `RtComposite`/`RtPlateProfile`.

## Compatibility rules

1. Select providers by capabilities and successful runtime probes, not PCI vendor.
2. Preserve a pure Vulkan/SPIR-V fallback.
3. Treat optional native failure as recoverable.
4. Advertise image formats explicitly; do not infer them from provider class names.
5. Keep SDR and HDR paths valid. HDR presentation uses floating-point display
   intermediates and the final SDR-to-PQ composition path.
6. Do not add NGX/DLSS, Reflex, CUDA, `VK_NV*`, or another vendor-only bring-up
   requirement to the bottom-level renderer.
7. Add regression checks for every cross-module seam and run the full validation
   sequence from `docs/developer_guide.md`.
