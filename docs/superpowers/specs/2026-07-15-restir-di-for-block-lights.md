# ReSTIR DI for Block Lights - Mid-Range GPU Quality Breakthrough

**Date:** 2026-07-15  
**Goal:** SPP=1 playable quality on RX7600/RTX4060 with dynamic block lights (torches, lava, glowstone).

## Problem Statement

Current SPP=1 path tracer:
- ✅ Sun/moon NEE → clean outdoor direct light
- ❌ Block lights (torches, glowstone) → only gathered by random walk
- ❌ Dark indoor scenes → massive noise (firefly starfield)
- ❌ 1 diffuse bounce → misses most indirect lighting from block lights

**Example**: Standing in a torch-lit corridor:
- Current: 1 diffuse ray has ~5% chance to hit the torch → 95% black noise
- Need: direct sample the torch every frame → stable lighting

## Solution: ReSTIR DI (Direct Illumination)

ReSTIR = **Reservoir-based Spatio-Temporal Importance Resampling**  
Paper: [Bitterli et al. 2020, "Spatiotemporal reservoir resampling for real-time ray tracing with dynamic direct lighting"]

### Core Idea

1. **Candidate generation**: Test N candidate lights (e.g., 8 nearby torches)
2. **Weighted resampling**: Pick 1 light proportional to `(contribution / pdf)`
3. **Temporal reuse**: Blend with previous frame's reservoir → 20+ effective samples
4. **Spatial reuse**: Share neighbors' reservoirs → 100+ effective samples
5. **Result**: 1 shadow ray achieves quality of 100+ rays

### Why It's Perfect for Minecraft RT

- Block lights are **static** (torches don't move) → temporal reuse converges fast
- Block lights are **sparse** (8-32 in view) → candidate iteration is cheap
- Caustica already has **light BVH** (from terrain structure) → fast nearest-neighbor
- SPP=1 + ReSTIR >> SPP=8 naive → performance win

## Implementation Plan

### Phase 1: Reservoir Infrastructure (P0)

```glsl
// shaders/world/restir_reservoir.glsl
struct Reservoir {
    uint lightIndex;      // selected light ID
    float wSum;           // sum of weights seen
    float M;              // effective sample count (clamped to maxM)
    uint sampleCount;     // actual samples tested
};

layout(binding = 11, rgba32ui) uniform uimage2D gReservoir; // 4×32-bit packed
```

**Reservoir packing** (128-bit per pixel):
- `.x`: `lightIndex` (24-bit) + flags (8-bit)
- `.y`: `wSum` (float32)
- `.z`: `M` (float16) + `sampleCount` (uint16)
- `.w`: unused (future: multiple reservoirs)

### Phase 2: Temporal Resampling (P0)

```glsl
// world.rgen: after primary hit, before NEE
if (bounce == 0 && material == MATERIAL_BLOCK) {
    Reservoir r = loadReservoir(pix);
    
    // Temporal reuse: load previous frame's reservoir
    vec2 prevUv = (pix + motion) / size;
    Reservoir rPrev = loadReservoir(ivec2(prevUv * size));
    if (temporalValid(rPrev, hitPos, normal)) {
        r = combineReservoirs(r, rPrev, maxM = 20);
    }
    
    // Generate N candidates from nearby lights
    for (int i = 0; i < N_CANDIDATES; i++) {
        uint lightIdx = sampleNearbyLight(hitPos, seed);
        float weight = evalReservoirWeight(lightIdx, hitPos, normal);
        updateReservoir(r, lightIdx, weight);
    }
    
    // Selected light: trace 1 shadow ray
    if (r.lightIndex != INVALID) {
        vec3 lightPos = getBlockLightPos(r.lightIndex);
        vec3 lightDir = normalize(lightPos - hitPos);
        float vis = visibility(hitPos, lightDir, length(lightPos - hitPos));
        vec3 contrib = evalBlockLight(r.lightIndex, hitPos, normal) * vis;
        acc.diffuseOther += contrib / max(r.M, 1.0); // unbiased
    }
    
    storeReservoir(pix, r);
}
```

### Phase 3: Spatial Resampling (P1)

After temporal, share with 3×3 neighbors:

```glsl
// Run as separate pass (or interleaved checkerboard)
for (int dy = -1; dy <= 1; dy++) {
    for (int dx = -1; dx <= 1; dx++) {
        Reservoir rNeighbor = loadReservoir(pix + ivec2(dx, dy));
        if (spatialValid(rNeighbor, hitPos, normal)) {
            r = combineReservoirs(r, rNeighbor, maxM = 100);
        }
    }
}
```

**Validation**: reject neighbor if:
- Depth diff > 10% of viewZ
- Normal dot < 0.9
- MIS weight < 0.1 (light outside receiver's hemisphere)

### Phase 4: Block Light BVH (P1)

Currently Caustica has terrain chunk structure. Leverage that:

```java
// RtBlockLights.java
class BlockLightBvh {
    // Per-chunk: list of (blockPos, lightLevel) in chunk bounds
    // GPU buffer: flat array of vec4(xyz, intensity)
    // Query: BVH traversal or grid hash (16×16×16 cells)
}
```

**Fallback**: if no BVH, sample uniformly from all lights in 32-block radius.

## Performance Budget (RX7600 @ 1080p)

| Operation | Cost | Budget |
|-----------|------|--------|
| Candidate iteration (8 lights) | ~0.1ms | ✅ |
| Temporal resampling (1 tap) | ~0.05ms | ✅ |
| Spatial resampling (9 taps) | ~0.3ms | ✅ |
| 1 shadow ray | ~0.2ms | ✅ |
| **Total ReSTIR overhead** | **~0.65ms** | **✅ <1ms** |

Compared to brute-force:
- SPP=8 naive: +6ms (8× trace cost)
- ReSTIR: +0.65ms (12× faster)

## Quality Comparison

| Scene | SPP=1 (current) | SPP=1 + ReSTIR | SPP=4 naive |
|-------|-----------------|----------------|-------------|
| Torch corridor | Starfield noise | Clean | Clean but 4× slower |
| Glowstone cave | 90% black pixels | Stable lighting | Stable but 4× slower |
| Night exterior (moon only) | No change (sun NEE) | No change | No change |

## Risks & Mitigations

1. **Bias from clamping M**: ReSTIR is biased when M is clamped (we use maxM=20/100).  
   → Acceptable: NRD already assumes some bias; visual quality >> theoretical correctness.

2. **Light update lag**: Moving lights (held torch) have 1-frame lag in reservoir.  
   → Minecraft block lights are 99% static; held torches can use realtime candidate gen.

3. **Memory**: 128-bit/pixel reservoir = 8MB @ 1080p.  
   → Acceptable for 8GB VRAM cards (RX7600/RTX4060).

## Success Criteria

- [ ] Dark indoor (4 torches): noise reduced by 80%+
- [ ] RX7600 @ 1080p: <1ms ReSTIR overhead
- [ ] Temporal stability: no flicker on camera rotation

## References

- Bitterli et al., "Spatiotemporal reservoir resampling for real-time ray tracing with dynamic direct lighting" (2020)
- Lin et al., "Generalized Resampled Importance Sampling" (GRIS, 2022)
- NVIDIA RTX DI SDK (reference impl, not used here due to vendor lock-in)
