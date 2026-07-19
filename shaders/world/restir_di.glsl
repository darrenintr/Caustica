// ReSTIR Direct Illumination for block lights.
// Production algorithm is inlined in world.rgen (needs WorldPush `pc` + rndf).
// This file is intentionally a stub so old #includes do not reintroduce the
// broken uniform-sample / same-frame spatial path.

#ifndef RESTIR_DI_GLSL
#define RESTIR_DI_GLSL

// See world.rgen section:
//   "ReSTIR Direct Illumination for block lights (indoor-stable biased RIS)"
//
// Features there:
//   - distance-biased candidate generation
//   - temporal reuse with dynamic-light M clamp
//   - spatial reuse from previous-frame reservoirs only
//   - biased RIS estimator + firefly clamp
//   - half-float intensity + R5G5B5 color + dynamic flag packing

#endif // RESTIR_DI_GLSL
