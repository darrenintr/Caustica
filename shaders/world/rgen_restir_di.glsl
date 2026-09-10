#ifndef RGEN_RESTIR_DI_GLSL
#define RGEN_RESTIR_DI_GLSL

// ============================================================================
// rgen_restir_di.glsl — ReSTIR Direct Illumination for block lights.
//
// Depends on rgen_common (PI / INV_PI / SBT constants) and rgen_nee (visibility).
//
// Provides:
//   - LIGHTFIELD_SKY_SCALE / LIGHTFIELD_BLOCK_SCALE / LIGHTFIELD_RT_RESIDUAL / LIGHTFIELD_BLOCK_WARM
//   - Reservoir (DI) struct + pack / unpack / empty / update / combine
//   - Vanilla light-field SSBO sampling: lightFieldCellByte / sampleLightField01 / lightFieldAmbient
//   - Block-light accessors: getLightPosition / getLightIntensity / getLightColor / isLightDynamic
//   - evalBlockLightUnshadowed / targetFunction / lightProposalWeight / sampleBiasedLight
//   - generateCandidates / temporalReuse / spatialReuse / evalBlockLightReSTIR
//   - secondaryBlockLightNee (single sample at first-bounce indirect surfaces)
//
// Distance-biased candidate generation, temporal reuse with dynamic-light M clamp, spatial reuse
// from previous-frame reservoirs only, biased RIS estimator + firefly clamp.
// ============================================================================

// ---- ReSTIR DI constants ----
const float RESTIR_MAX_RADIUS = 28.0;       // blocks — beyond this, block light is negligible
const float RESTIR_MAX_RADIUS_SQ = RESTIR_MAX_RADIUS * RESTIR_MAX_RADIUS;
const float RESTIR_ATTEN_K = 0.035;         // broad indoor falloff
const float RESTIR_INTENSITY_SCALE = 2.4;   // torch/glowstone punch for SPP-1 playability
const float RESTIR_FIREFLY_CLAMP = 8.0;     // max unshadowed contribution luminance proxy
const float RESTIR_DYNAMIC_M_SCALE = 0.25;  // dynamic lights keep much less temporal history
const uint  RESTIR_MAX_AGE = 32u;
const int   RESTIR_SPATIAL_SAMPLES = 4;     // random neighbor samples (avoids same-frame race)

// ---- Vanilla light-field GI base constants ----
// Tuned for SPP=1 outdoor grass stability: keep under RT direct so we don't double-count sun NEE /
// ReSTIR, but high enough to kill grain. LIGHTFIELD_RT_RESIDUAL is read by tracePath (world.rgen).
const float LIGHTFIELD_SKY_SCALE = 0.22;     // multiplies max(pc.lightRadiance) * dayFactor
const float LIGHTFIELD_BLOCK_SCALE = 0.55;   // warm torch ambient (rolled back from 0.85)
const float LIGHTFIELD_RT_RESIDUAL = 0.45;   // keep this fraction of noisy path-traced diffuse GI
const float LIGHTFIELD_BLOCK_WARM = 0.35;    // torch color warmth mix into block ambient

// ---- DI Reservoir (16 bytes/pixel: rgba32ui) ----
//   .x lightIndex
//   .y floatBitsToUint(wSum)
//   .z packHalf2x16(M, age)
//   .w unused
//
// Quarter-res tiled storage (2026-09, RestirEnhanced.QUARTER_RES_RESERVOIR):
// the images are allocated at half width/height, so every access maps
// full-res pix -> tilePix = pix >> 1. All four pixels of a 2x2 tile share
// one reservoir — the temporal merge reads the same prev tile, and the
// spatial merge below already samples a 1..4px ring around the tile.
//
// The push flag pc.quarterResReservoir (WorldPush @652..656, see world.rgen)
// selects the mapping at runtime: 1 = tilePix, 0 = legacy per-pixel pix.
struct Reservoir {
    uint lightIndex;
    float wSum;
    float M;
    uint age;
};

uvec4 packReservoir(Reservoir r) {
    return uvec4(
        r.lightIndex,
        floatBitsToUint(r.wSum),
        packHalf2x16(vec2(r.M, float(r.age))),
        0u
    );
}

Reservoir unpackReservoir(uvec4 packed) {
    Reservoir r;
    r.lightIndex = packed.x;
    r.wSum = uintBitsToFloat(packed.y);
    vec2 mAge = unpackHalf2x16(packed.z);
    r.M = mAge.x;
    r.age = uint(mAge.y + 0.5);
    return r;
}

Reservoir emptyReservoir() {
    Reservoir r;
    r.lightIndex = 0xFFFFFFFFu;
    r.wSum = 0.0;
    r.M = 0.0;
    r.age = 0u;
    return r;
}

void updateReservoir(inout Reservoir r, uint lightIdx, float weight, inout uint seed) {
    if (!(weight > 0.0) || isnan(weight) || isinf(weight)) {
        return;
    }
    r.wSum += weight;
    r.M += 1.0;
    if (rndf(seed) * max(r.wSum, 1e-6) < weight) {
        r.lightIndex = lightIdx;
    }
}

// Combine reservoirs with a simple stream-RIS merge. misWeight scales the donor's wSum/M.
Reservoir combineReservoirs(Reservoir ra, Reservoir rb, float misWeight, float maxM, inout uint seed) {
    if (rb.lightIndex == 0xFFFFFFFFu || misWeight <= 0.0) return ra;
    if (ra.lightIndex == 0xFFFFFFFFu) {
        Reservoir r = rb;
        r.wSum = rb.wSum * misWeight;
        r.M = min(rb.M * misWeight, maxM);
        return r;
    }

    Reservoir r;
    float wb = rb.wSum * misWeight;
    r.wSum = ra.wSum + wb;
    r.M = min(ra.M + rb.M * misWeight, maxM);
    r.age = min(ra.age, rb.age);

    float totalWeight = max(r.wSum, 1e-6);
    if (rndf(seed) < ra.wSum / totalWeight) {
        r.lightIndex = ra.lightIndex;
    } else {
        r.lightIndex = rb.lightIndex;
    }
    return r;
}

// ---- Vanilla light-field GI (packed sky/block levels) ----
// cells[] is uint-packed little-endian bytes: cell i lives in cells[i>>2] byte (i&3).
uint lightFieldCellByte(int linearIndex) {
    uint word = lightField.cells[linearIndex >> 2];
    uint shift = uint(linearIndex & 3) * 8u;
    return (word >> shift) & 0xFFu;
}

// Sample sky/block light levels at a rebased world position. Returns vec2(sky01, block01).
vec2 sampleLightField01(vec3 rebasedPos) {
    int size = pc.lightFieldOriginSize.w;
    if (size <= 0) {
        return vec2(1.0, 0.0);
    }
    vec3 p = rebasedPos + vec3(0.0, 0.05, 0.0);
    ivec3 o = pc.lightFieldOriginSize.xyz;
    vec3 local = p - vec3(o);
    vec3 base = floor(local);
    vec3 f = fract(local);
    vec2 acc = vec2(0.0);
    float wSum = 0.0;
    for (int dz = 0; dz <= 1; dz++) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                ivec3 c = ivec3(base) + ivec3(dx, dy, dz);
                if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, ivec3(size)))) {
                    continue;
                }
                float wx = (dx == 0) ? (1.0 - f.x) : f.x;
                float wy = (dy == 0) ? (1.0 - f.y) : f.y;
                float wz = (dz == 0) ? (1.0 - f.z) : f.z;
                float w = wx * wy * wz;
                int idx = c.x + size * (c.z + size * c.y); // X-major, then Z, then Y
                uint packed = lightFieldCellByte(idx);
                float sky = float((packed >> 4) & 0xFu) / 15.0;
                float blk = float(packed & 0xFu) / 15.0;
                sky = sky * sky * (3.0 - 2.0 * sky);
                blk = blk * blk;
                acc += vec2(sky, blk) * w;
                wSum += w;
            }
        }
    }
    if (wSum < 1e-4) {
        return vec2(1.0, 0.0);
    }
    return acc / wSum;
}

// Low-variance ambient irradiance from the light field (not albedo-modulated yet).
vec3 lightFieldAmbient(vec3 rebasedPos) {
    vec2 sb = sampleLightField01(rebasedPos);
    float day = clamp(pc.sunDir.w, 0.0, 1.0);
    float skyLum = max(pc.lightRadiance.x, max(pc.lightRadiance.y, pc.lightRadiance.z));
    vec3 skyAmb = vec3(0.55, 0.65, 0.85) * (skyLum * LIGHTFIELD_SKY_SCALE) * sb.x * mix(0.15, 1.0, day);
    skyAmb += vec3(0.02, 0.03, 0.05) * sb.x;
    vec3 blockTint = mix(vec3(1.0), vec3(1.0, 0.72, 0.40), LIGHTFIELD_BLOCK_WARM);
    vec3 blockAmb = blockTint * (sb.y * LIGHTFIELD_BLOCK_SCALE);
    return skyAmb + blockAmb;
}

// ---- Block-light accessors ----
// w pack: float16 intensity | dynamic flag (bit15) | R5G5B5
vec3 getLightPosition(uint idx) {
    return blockLights.lights[idx].xyz;
}

float getLightIntensity(uint idx) {
    uint packed = floatBitsToUint(blockLights.lights[idx].w);
    return unpackHalf2x16(packed).x;
}

bool isLightDynamic(uint idx) {
    uint packed = floatBitsToUint(blockLights.lights[idx].w);
    return (packed & 0x8000u) != 0u;
}

vec3 getLightColor(uint idx) {
    uint packed = floatBitsToUint(blockLights.lights[idx].w);
    uint colorBits = packed & 0x7FFFu; // strip dynamic flag
    float r = float((colorBits >> 10) & 0x1Fu) / 31.0;
    float g = float((colorBits >> 5) & 0x1Fu) / 31.0;
    float b = float(colorBits & 0x1Fu) / 31.0;
    // Avoid pure-black packed colors collapsing light to zero.
    vec3 c = max(vec3(r, g, b), vec3(0.04));
    return c / max(max(c.r, c.g), max(c.b, 1e-3)); // keep peak channel ~1
}

// Unshadowed Lambertian response of one block light at hitPos.
vec3 evalBlockLightUnshadowed(uint lightIdx, vec3 hitPos, vec3 normal, vec3 albedo) {
    if (lightIdx >= pc.blockLightCount) return vec3(0.0);

    vec3 lightPos = getLightPosition(lightIdx);
    vec3 toLight = lightPos - hitPos;
    float distSq = dot(toLight, toLight);
    if (distSq > RESTIR_MAX_RADIUS_SQ || distSq < 1e-6) return vec3(0.0);

    float dist = sqrt(distSq);
    vec3 L = toLight / dist;
    float ndl = max(0.0, dot(normal, L));
    if (ndl < 0.001) return vec3(0.0);

    float intensity = getLightIntensity(lightIdx);
    if (intensity <= 1e-4) return vec3(0.0);
    vec3 color = getLightColor(lightIdx);
    float attenuation = (intensity * RESTIR_INTENSITY_SCALE) / (1.0 + distSq * RESTIR_ATTEN_K);

    vec3 contrib = albedo * color * attenuation * ndl * INV_PI;
    // Soft firefly clamp (preserves hue).
    float peak = maxChannel(contrib);
    if (peak > RESTIR_FIREFLY_CLAMP) {
        contrib *= RESTIR_FIREFLY_CLAMP / peak;
    }
    return contrib;
}

float targetFunction(uint lightIdx, vec3 hitPos, vec3 normal, vec3 albedo) {
    vec3 contrib = evalBlockLightUnshadowed(lightIdx, hitPos, normal, albedo);
    return maxChannel(contrib);
}

// Distance-biased proposal: prefer nearer lights without building a spatial hash on GPU.
float lightProposalWeight(uint lightIdx, vec3 hitPos) {
    vec3 toLight = getLightPosition(lightIdx) - hitPos;
    float distSq = max(dot(toLight, toLight), 0.25);
    if (distSq > RESTIR_MAX_RADIUS_SQ) return 0.0;
    float intensity = max(getLightIntensity(lightIdx), 0.05);
    // 2026-07-20: uniform floor 0.005 biases the proposal toward actual intensity / inverse-square.
    return intensity / distSq + 0.005;
}

uint sampleBiasedLight(vec3 hitPos, inout uint seed, out float proposalPdf) {
    uint count = pc.blockLightCount;
    proposalPdf = 0.0;
    if (count == 0u) return 0xFFFFFFFFu;

    int trials = int(min(pc.restirCandidates, count));
    trials = max(trials, 1);

    uint best = 0xFFFFFFFFu;
    float wSum = 0.0;
    for (int i = 0; i < trials; i++) {
        uint idx = uint(rndf(seed) * float(count));
        idx = min(idx, count - 1u);
        float w = lightProposalWeight(idx, hitPos);
        if (w <= 0.0) continue;
        wSum += w;
        if (rndf(seed) * wSum < w) {
            best = idx;
        }
    }
    if (best == 0xFFFFFFFFu || wSum <= 0.0) {
        best = min(uint(rndf(seed) * float(count)), count - 1u);
        proposalPdf = 1.0 / float(count);
        return best;
    }
    float wBest = lightProposalWeight(best, hitPos);
    proposalPdf = max(wBest / max(wSum / float(trials), 1e-6) / float(count), 1e-6);
    return best;
}

// ---- DI pipeline ----
Reservoir generateCandidates(vec3 hitPos, vec3 normal, vec3 albedo, inout uint seed) {
    Reservoir r = emptyReservoir();
    if (pc.blockLightCount == 0u) return r;

    int n = int(pc.restirCandidates);
    n = max(n, 1);
    for (int i = 0; i < n; i++) {
        float pdf;
        uint lightIdx = sampleBiasedLight(hitPos, seed, pdf);
        if (lightIdx == 0xFFFFFFFFu) continue;
        float target = targetFunction(lightIdx, hitPos, normal, albedo);
        if (target <= 0.0) {
            // Still count the sample so M reflects effort.
            r.M += 1.0;
            continue;
        }
        float weight = target / max(pdf, 1e-6);
        updateReservoir(r, lightIdx, weight, seed);
    }
    return r;
}

Reservoir temporalReuse(Reservoir rCurr, ivec2 pix, vec2 motion, ivec2 size,
                        vec3 hitPos, vec3 normal, vec3 albedo, inout uint seed) {
    // Quarter-res mapping: the reservoir images are half-size, so all accesses
    // go through the 2x2 tile coordinate. Motion/size stay full-res (only the
    // reservoir fetch is tiled).
    vec2 prevUv = (vec2(pix) + 0.5 + motion) / vec2(size);
    ivec2 prevPix = ivec2(prevUv * vec2(size));
    ivec2 prevTile = prevPix >> (pc.quarterResReservoir != 0u ? 1 : 0);

    if (any(lessThan(prevPix, ivec2(0))) || any(greaterThanEqual(prevPix, size))) {
        return rCurr;
    }
    // Reject large motion (disocclusion / fast turn) — history is useless and causes streaks.
    float motionSq = dot(motion, motion);
    float motionCapPx = pc.footprintMotionCapPx;
    if (motionSq > motionCapPx * motionCapPx) {
        return rCurr;
    }

    // ReSTIR PT Enhanced §4 footprint-based reconnection (temporal side). See rgen_nee.glsl for the
    // general rationale; mirrored here so DI's temporal gate uses the same geometric criterion.
    // Quarter-res: hit-pos shares the reservoir tiling (prevTile).
    if (pc.footprintReconnectionActive == 1u) {
        vec4 prevHp = imageLoad(gHitPosPrev, prevTile);
        if (prevHp.w > 0.5) {
            vec3 x0 = prevHp.xyz;
            vec3 dx = hitPos - x0;
            float cosTheta = max(dot(normal, dx), 0.0);
            if (cosTheta > 1e-3) {
                float Rp = sqrt(dot(dx, dx) * cosTheta / (4.0 * 3.14159265));
                float dist = length(hitPos - pc.camOffset);
                float maxFootprint = dist * pc.pixelsToWorld;
                if (Rp > 0.02 * maxFootprint) {
                    return rCurr;
                }
            }
        }
    }

    Reservoir rPrev = unpackReservoir(imageLoad(gReservoirPrev, prevTile));
    if (rPrev.lightIndex == 0xFFFFFFFFu || rPrev.lightIndex >= pc.blockLightCount || rPrev.age > RESTIR_MAX_AGE) {
        return rCurr;
    }

    float targetPrev = targetFunction(rPrev.lightIndex, hitPos, normal, albedo);
    if (targetPrev <= 1e-5) {
        return rCurr;
    }

    float prevEstimate = max(rPrev.wSum / max(rPrev.M, 1.0), 1e-6);
    float confidence = clamp(targetPrev / prevEstimate, 0.0, 1.0);
    if (confidence < 0.15) {
        return rCurr;
    }

    float maxM = pc.restirMaxMTemporal;
    // ReSTIR PT Enhanced §5: D-aware M cap (gDupMapPrev at previous-frame pixel).
    float D = imageLoad(gDupMapPrev, prevPix).r;
    maxM = mix(4.0, maxM, pow(D, 0.1));
    if (isLightDynamic(rPrev.lightIndex)) {
        maxM *= RESTIR_DYNAMIC_M_SCALE;
    }

    Reservoir r = combineReservoirs(rCurr, rPrev, confidence, maxM, seed);
    r.age = min(rPrev.age + 1u, RESTIR_MAX_AGE);
    return r;
}

Reservoir spatialReuse(Reservoir rCenter, ivec2 pix, ivec2 size,
                       vec3 hitPos, vec3 normal, vec3 albedo, inout uint seed) {
    Reservoir r = rCenter;
    if (pc.blockLightCount == 0u) return r;

    for (int i = 0; i < RESTIR_SPATIAL_SAMPLES; i++) {
        float ang = rndf(seed) * 6.2831853;
        float rad = 1.0 + rndf(seed) * 3.0; // 1..4 px
        ivec2 nPix = pix + ivec2(round(vec2(cos(ang), sin(ang)) * rad));
        if (any(lessThan(nPix, ivec2(0))) || any(greaterThanEqual(nPix, size))) continue;

        // Quarter-res: neighbour fetch goes through the tile coordinate.
        ivec2 nTile = nPix >> (pc.quarterResReservoir != 0u ? 1 : 0);
        Reservoir rNeighbor = unpackReservoir(imageLoad(gReservoirPrev, nTile));
        if (rNeighbor.lightIndex == 0xFFFFFFFFu || rNeighbor.lightIndex >= pc.blockLightCount) continue;

        if (pc.footprintReconnectionActive == 1u) {
            vec4 nhp = imageLoad(gHitPosPrev, nTile);
            if (nhp.w > 0.5) {
                vec3 dxS = hitPos - nhp.xyz;
                float cosThetaS = max(dot(normal, dxS), 0.0);
                if (cosThetaS > 1e-3) {
                    float rpS = sqrt(dot(dxS, dxS) * cosThetaS / (4.0 * 3.14159265));
                    float distS = length(hitPos - pc.camOffset);
                    if (rpS > 0.02 * (distS * pc.pixelsToWorld)) {
                        continue;
                    }
                }
            }
        }

        float targetNeighbor = targetFunction(rNeighbor.lightIndex, hitPos, normal, albedo);
        if (targetNeighbor <= 1e-5) continue;

        float neighborEstimate = max(rNeighbor.wSum / max(rNeighbor.M, 1.0), 1e-6);
        float confidence = clamp(targetNeighbor / neighborEstimate, 0.0, 1.0);
        if (confidence < 0.08) continue;

        float maxM = pc.restirMaxMSpatial;
        if (isLightDynamic(rNeighbor.lightIndex)) {
            maxM *= RESTIR_DYNAMIC_M_SCALE;
        }
        r = combineReservoirs(r, rNeighbor, confidence * 0.85, maxM, seed);
    }
    return r;
}

vec3 evalBlockLightReSTIR(ivec2 pix, ivec2 size, vec3 hitPos, vec3 normal, vec3 albedo,
                          vec2 motion, inout uint seed, out uint selectedLightIdx) {
    selectedLightIdx = 0xFFFFFFFFu;
    // Quarter-res store coordinate (matches the fetch tiling above).
    ivec2 tilePix = pix >> (pc.quarterResReservoir != 0u ? 1 : 0);
    if (pc.blockLightCount == 0u) {
        imageStore(gReservoirCurr, tilePix, packReservoir(emptyReservoir()));
        return vec3(0.0);
    }

    Reservoir r = generateCandidates(hitPos, normal, albedo, seed);
    r = temporalReuse(r, pix, motion, size, hitPos, normal, albedo, seed);
    // ReSTIR PT Enhanced §3 paired spatial-reuse: skip inline 9-tap when compute pass already merged.
    if (pc.pairedReuseActive == 0u) {
        r = spatialReuse(r, pix, size, hitPos, normal, albedo, seed);
    }

    if (r.lightIndex == 0xFFFFFFFFu || r.lightIndex >= pc.blockLightCount || r.M < 0.5 || r.wSum <= 0.0) {
        imageStore(gReservoirCurr, tilePix, packReservoir(emptyReservoir()));
        imageStore(gHitPosCurr, tilePix, vec4(0.0, 0.0, 0.0, 0.0));
        return vec3(0.0);
    }

    r.age = min(r.age + 1u, RESTIR_MAX_AGE);
    imageStore(gReservoirCurr, tilePix, packReservoir(r));
    imageStore(gHitPosCurr, tilePix, vec4(hitPos, 1.0));

    selectedLightIdx = r.lightIndex;
    vec3 unshadowed = evalBlockLightUnshadowed(r.lightIndex, hitPos, normal, albedo);
    float target = maxChannel(unshadowed);
    if (target <= 1e-6) {
        selectedLightIdx = 0xFFFFFFFFu;
        return vec3(0.0);
    }

    // Biased RIS estimator: L ≈ (wSum / M) * (f / p_hat) with p_hat ≈ target → L ≈ unshadowed * (wSum/M) / target.
    float invM = 1.0 / max(r.M, 1.0);
    float W = r.wSum * invM;
    vec3 estimate = unshadowed * (W / target);

    float peak = maxChannel(estimate);
    if (peak > RESTIR_FIREFLY_CLAMP * 1.5) {
        estimate *= (RESTIR_FIREFLY_CLAMP * 1.5) / peak;
    }
    return estimate;
}

// One local-light sample on secondary diffuse hits. Primary surfaces use full ReSTIR DI; this
// bounded estimator carries torch energy through one additional surface so enclosed rooms receive
// warm bounce light without allocating per-bounce reservoirs.
vec3 secondaryBlockLightNee(vec3 hitPos, vec3 normal, vec3 albedo, inout uint seed) {
    if (pc.blockLightCount == 0u) return vec3(0.0);

    float proposalPdf;
    uint lightIdx = sampleBiasedLight(hitPos, seed, proposalPdf);
    if (lightIdx == 0xFFFFFFFFu || proposalPdf <= 0.0) return vec3(0.0);

    vec3 unshadowed = evalBlockLightUnshadowed(lightIdx, hitPos, normal, albedo);
    if (maxChannel(unshadowed) <= 0.0) return vec3(0.0);

    vec3 toLight = getLightPosition(lightIdx) - hitPos;
    float dist = length(toLight);
    if (dist <= 1e-4) return vec3(0.0);
    vec3 vis = visibility(hitPos + normal * SURF_BIAS, toLight / dist, max(dist - 0.15, 0.05));
    return min(unshadowed * vis / max(proposalPdf, 1e-4), vec3(MAX_BOUNCE_RADIANCE));
}

#endif // RGEN_RESTIR_DI_GLSL