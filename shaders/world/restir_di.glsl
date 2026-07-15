// ReSTIR Direct Illumination: sample block lights with spatiotemporal reuse.
// Provides evalBlockLightContribution() for world.rgen to call at primary hits.

#ifndef RESTIR_DI_GLSL
#define RESTIR_DI_GLSL

#include "restir_reservoir.glsl"

// Block light buffer: array of vec4(posXYZ, packedIntensityColor)
// Binding = firstExtraBinding(3) + slot(9) = 12
layout(binding = 12, set = 0) readonly buffer BlockLights {
    vec4 lights[]; // lights[i] = vec4(pos, packed)
} blockLights;

layout(binding = 13, set = 0, rgba32ui) uniform uimage2D gReservoirCurr; // firstExtraBinding + 10
layout(binding = 14, set = 0, rgba32ui) uniform uimage2D gReservoirPrev; // firstExtraBinding + 11

// ReSTIR config (set by push constant or UBO)
uniform int u_blockLightCount = 0;
uniform int u_candidateCount = 8;      // N candidates per pixel
uniform float u_maxM_temporal = 20.0;  // clamp M after temporal reuse
uniform float u_maxM_spatial = 100.0;  // clamp M after spatial reuse

// Unpack light data
vec3 getLightPosition(uint idx) {
    return blockLights.lights[idx].xyz;
}

float getLightIntensity(uint idx) {
    uint packed = floatBitsToUint(blockLights.lights[idx].w);
    return unpackHalf2x16(packed).x;
}

vec3 getLightColor(uint idx) {
    uint packed = floatBitsToUint(blockLights.lights[idx].w);
    uint colorBits = packed & 0xFFFFu;
    float r = float((colorBits >> 10) & 0x1Fu) / 31.0;
    float g = float((colorBits >> 5) & 0x1Fu) / 31.0;
    float b = float(colorBits & 0x1Fu) / 31.0;
    return vec3(r, g, b);
}

// Evaluate single light contribution (unshadowed)
vec3 evalBlockLightUnshadowed(uint lightIdx, vec3 hitPos, vec3 normal, vec3 albedo) {
    if (lightIdx >= uint(u_blockLightCount)) return vec3(0.0);

    vec3 lightPos = getLightPosition(lightIdx);
    vec3 toLight = lightPos - hitPos;
    float distSq = dot(toLight, toLight);
    float dist = sqrt(distSq);
    vec3 L = toLight / dist;

    float ndl = max(0.0, dot(normal, L));
    if (ndl < 0.001) return vec3(0.0);

    // Minecraft-style attenuation: intensity / (1 + dist²)
    float intensity = getLightIntensity(lightIdx);
    vec3 color = getLightColor(lightIdx);
    float attenuation = intensity / (1.0 + distSq * 0.1);

    return albedo * color * attenuation * ndl * INV_PI;
}

// Target function for ReSTIR: unshadowed contribution (used for importance weighting)
float targetFunction(uint lightIdx, vec3 hitPos, vec3 normal, vec3 albedo) {
    vec3 contrib = evalBlockLightUnshadowed(lightIdx, hitPos, normal, albedo);
    return max(contrib.r, max(contrib.g, contrib.b)); // luminance proxy
}

// Sample N candidate lights and build reservoir (initial candidates)
Reservoir generateCandidates(vec3 hitPos, vec3 normal, vec3 albedo, inout uint seed) {
    Reservoir r = emptyReservoir();
    if (u_blockLightCount == 0) return r;

    // Uniform random sampling from all lights (could use spatial hash for large counts)
    for (int i = 0; i < u_candidateCount; i++) {
        uint lightIdx = uint(rndf(seed) * float(u_blockLightCount));
        lightIdx = min(lightIdx, uint(u_blockLightCount - 1));

        float target = targetFunction(lightIdx, hitPos, normal, albedo);
        float pdf = 1.0 / float(u_blockLightCount); // uniform proposal
        float weight = target / max(pdf, 1e-6);

        updateReservoir(r, lightIdx, weight, seed);
    }

    return r;
}

// Temporal reuse: combine current with reprojected previous frame
Reservoir temporalReuse(Reservoir rCurr, ivec2 pix, vec2 motion, ivec2 size,
                        vec3 hitPos, vec3 normal, vec3 albedo) {
    // Reproject to previous frame
    vec2 prevUv = (vec2(pix) + motion) / vec2(size);
    ivec2 prevPix = ivec2(prevUv * vec2(size));

    // Bounds check
    if (any(lessThan(prevPix, ivec2(0))) || any(greaterThanEqual(prevPix, size))) {
        return rCurr;
    }

    // Load previous reservoir
    uvec4 prevPacked = imageLoad(gReservoirPrev, prevPix);
    Reservoir rPrev = unpackReservoir(prevPacked);

    // Temporal validation: reject if geometry changed significantly
    // (depth/normal check done in world.rgen based on motion vector validity)
    if (rPrev.lightIndex == 0xFFFFFFFFu || rPrev.age > 60u) {
        return rCurr;
    }

    // MIS weight: re-evaluate previous sample at current shading point
    float targetPrev = targetFunction(rPrev.lightIndex, hitPos, normal, albedo);
    float misWeight = targetPrev / max(rPrev.wSum / max(rPrev.M, 1.0), 1e-6);

    Reservoir r = combineReservoirs(rCurr, rPrev, misWeight, u_maxM_temporal);
    r.age = rPrev.age + 1u;
    return r;
}

// Spatial reuse: combine with neighbors (3×3 or 5×5)
Reservoir spatialReuse(Reservoir rCenter, ivec2 pix, ivec2 size,
                       vec3 hitPos, vec3 normal, vec3 albedo) {
    Reservoir r = rCenter;

    // 3×3 kernel (8 neighbors)
    const ivec2 offsets[8] = ivec2[](
        ivec2(-1, -1), ivec2(0, -1), ivec2(1, -1),
        ivec2(-1,  0),               ivec2(1,  0),
        ivec2(-1,  1), ivec2(0,  1), ivec2(1,  1)
    );

    for (int i = 0; i < 8; i++) {
        ivec2 nPix = pix + offsets[i];
        if (any(lessThan(nPix, ivec2(0))) || any(greaterThanEqual(nPix, size))) continue;

        uvec4 nPacked = imageLoad(gReservoirCurr, nPix);
        Reservoir rNeighbor = unpackReservoir(nPacked);

        if (rNeighbor.lightIndex == 0xFFFFFFFFu) continue;

        // Spatial validation: re-evaluate neighbor's sample at center
        float targetNeighbor = targetFunction(rNeighbor.lightIndex, hitPos, normal, albedo);
        float misWeight = targetNeighbor / max(rNeighbor.wSum / max(rNeighbor.M, 1.0), 1e-6);

        // Geometry similarity check (plane-based): reject if normal/depth differ too much
        // (simplified: assume valid if target > 0)
        if (targetNeighbor < 1e-6) continue;

        r = combineReservoirs(r, rNeighbor, misWeight, u_maxM_spatial);
    }

    return r;
}

// Main ReSTIR DI evaluation: call from world.rgen at bounce-0 block material hits
// Returns: block light contribution (still needs visibility test)
vec3 evalBlockLightReSTIR(ivec2 pix, ivec2 size, vec3 hitPos, vec3 normal, vec3 albedo,
                          vec2 motion, inout uint seed, out uint selectedLightIdx) {
    selectedLightIdx = 0xFFFFFFFFu;

    if (u_blockLightCount == 0) return vec3(0.0);

    // 1. Generate initial candidates
    Reservoir r = generateCandidates(hitPos, normal, albedo, seed);

    // 2. Temporal reuse
    r = temporalReuse(r, pix, motion, size, hitPos, normal, albedo);

    // 3. Spatial reuse
    r = spatialReuse(r, pix, size, hitPos, normal, albedo);

    // 4. Store reservoir for next frame
    imageStore(gReservoirCurr, pix, packReservoir(r));

    // 5. Evaluate selected light (unshadowed; caller adds visibility)
    if (r.lightIndex == 0xFFFFFFFFu || r.M < 0.5) return vec3(0.0);

    selectedLightIdx = r.lightIndex;
    vec3 unshadowedContrib = evalBlockLightUnshadowed(r.lightIndex, hitPos, normal, albedo);

    // Unbiased weight: contribution / p_hat
    float pHat = r.wSum / max(r.M, 1.0);
    if (pHat < 1e-6) return vec3(0.0);

    return unshadowedContrib * getReservoirContribution(r) / pHat;
}

#endif // RESTIR_DI_GLSL
