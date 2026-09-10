#ifndef RGEN_COMMON_GLSL
#define RGEN_COMMON_GLSL

// ============================================================================
// rgen_common.glsl — core utilities shared by every rgen_*.glsl include.
//
// Depends on (defined in world.rgen BEFORE this include is textually pasted):
//   - WorldPush struct + `pc` global
//   - `payload` (rayPayloadEXT Payload) and `shadowVis` globals
//   - gv_* globals (guide-attribute globals)
//
// Provides:
//   - material-flag constants + payload accessors
//   - PI / INV_PI / EMISSIVE_STRENGTH
//   - PCG hash RNG (pcg / rndf / hash1)
//   - TLAS cull-mask + SBT-record constants used by every tracer
// ============================================================================

// ---- Material flag constants ----
// 0 = opaque, 1 = water, 2 = particle, 3 = glass. Set by world.rchit.
const uint MATERIAL_OPAQUE = 0u;
const uint MATERIAL_WATER = 1u;
const uint MATERIAL_PARTICLE = 2u;
const uint MATERIAL_GLASS = 3u;
const uint PAYLOAD_MATERIAL_MASK = 3u;
const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Set by world.rchit only on a MATERIAL_WATER hit — whether the ray was travelling INTO the water
// volume (vs. out of it) at this hit's face. Distinct from PAYLOAD_SHOW_CELESTIAL (miss only).
const uint PAYLOAD_WATER_ENTERING = 8u;

// ---- Payload accessors (small, inlined; pulling them out keeps world.rgen readable) ----
uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
bool payloadWaterEntering() { return (payload.flags & PAYLOAD_WATER_ENTERING) != 0u; }
float payloadRoughness() { return unpackHalf2x16(payload.roughMetal).x; }
float payloadMetalness() { return unpackHalf2x16(payload.roughMetal).y; }
float payloadEmission() { return unpackHalf2x16(payload.emissionSss).x; }
float payloadSss() { return unpackHalf2x16(payload.emissionSss).y; }
void payloadSetTraceState(bool showCelestial, float rayConeWidth, float rayConeSpread) {
    payload.flags = showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u;
    payload.rayCone = packHalf2x16(vec2(rayConeWidth, rayConeSpread));
}

// ---- Basic constants ----
const float PI = 3.14159265359;
const float INV_PI = 0.31830988618;
// HDR radiance of a full (level-15) emitter, modulated by albedo.
const float EMISSIVE_STRENGTH = 5.0;

// Max-channel selector used to scalarise an RGB shadow transmittance into one [0,1] factor. NOT
// luminance — luminance's weighted average reads the shadow as a "darkening" instead of "is any
// channel blocked", which is wrong for the conservative shadow mask we apply later. Lives here
// because every tracePath material branch + giTraceAndShade + evalBlockLightReSTIR need it and
// it's a one-line inline so no codegen cost to share.
float maxChannel(vec3 v) {
    return max(v.r, max(v.g, v.b));
}

// ---- PCG hash RNG ----
// State-mutating PCG returns uniform in [0, 2^32).
uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
// Float wrapper: returns [0, 1).
float rndf(inout uint s) { return float(pcg(s)) * (1.0 / 4294967296.0); }
// Non-mutating hash for ReSTIR (returns [0, 1]).
float hash1(uint x) {
    x = (x ^ 61u) ^ (x >> 16u);
    x *= 9u;
    x = x ^ (x >> 4u);
    x *= 0x27d4eb2du;
    x = x ^ (x >> 15u);
    return float(x) * (1.0 / 4294967296.0);
}

// ---- Ray-cone texture LOD constants ----
// Raygen tracks a one-pixel footprint along the path, and closest-hit converts it to texture mip
// levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so indirect
// texture fetches don't keep sampling mip 0 through broad lobes. These are used by tracePath
// (in world.rgen), visibility (in rgen_nee), mirrorLobeRadiance / refractedGuideHit
// (in rgen_water), so they live in the common header to avoid a circular include order.
const float RAY_CONE_MIN_SPREAD = 1.0e-5;
const float RAY_CONE_MIN_WIDTH = 1.0e-5;
const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

// ---- TLAS cull-mask + SBT record offsets ----
// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits) and are seen by everything. Particles
// use 0x02: primary-visible receiver billboards only. They can be lit by rays they spawn from the
// camera hit, but secondary rays never hit particles, so they do not cast shadows, show in
// reflections, or contribute GI. The first-person camera owner uses 0x01 (secondary only), so the
// player appears in reflections / shadows but never blocks the camera ray.
const uint CULL_SECONDARY = 0x01u;
const uint CULL_PRIMARY = 0x02u;
const uint TERRAIN_BUCKETS = 4u;
const uint SBT_RADIANCE = 0u;
const uint SBT_SHADOW = TERRAIN_BUCKETS;
const uint SBT_STRIDE_BUCKET = 1u;

#endif // RGEN_COMMON_GLSL