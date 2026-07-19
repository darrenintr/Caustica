// ReSTIR reservoir packing helpers.
// Production path: inlined in world.rgen (same layout).

#ifndef RESTIR_RESERVOIR_GLSL
#define RESTIR_RESERVOIR_GLSL

// ============================================================================
// Direct-illumination reservoir (light-index based). 16 bytes/pixel: rgba32ui.
// ============================================================================
// Reservoir state (packed into uvec4 = 128-bit per pixel)
//   .x lightIndex
//   .y floatBitsToUint(wSum)
//   .z packHalf2x16(M, age)
//   .w unused

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

// ============================================================================
// Global-illumination reservoir (direction-based). 32 bytes/pixel: 2x rgba32f.
// ============================================================================
// Stores a sampled reflection direction (NOT a light index) plus RIS weights.
// Splits across two images so the direction stays full-precision:
//   A: dir.xyz (vec3) + wSum (float)  -> 16 bytes
//   B: M (float) + age (float) + targetPdf (float) + cachedVisibility (float) -> 16 bytes
// age is stored as float rather than half because ReSTIR GI needs >1024-frame
// reuse to converge in deep indoor scenes; half saturates at 2048 anyway but
// rounding eats frames. Full float adds 2 bytes for a much friendlier ceiling.
// cachedVisibility (v0.6): stores the previous frame's visibility result [0,1]
// to avoid retracing every frame. -1.0 = invalid/needs refresh.

struct ReservoirGI {
    vec3  dir;
    float wSum;
    float M;
    float age;
    float targetPdf;
    float cachedVisibility;
};

vec4 packReservoirGiA(ReservoirGI r) {
    return vec4(r.dir, r.wSum);
}

vec4 packReservoirGiB(ReservoirGI r) {
    return vec4(r.M, r.age, r.targetPdf, r.cachedVisibility);
}

ReservoirGI unpackReservoirGI(vec4 a, vec4 b) {
    ReservoirGI r;
    r.dir       = a.xyz;
    r.wSum      = a.w;
    r.M         = b.x;
    r.age       = b.y;
    r.targetPdf = b.z;
    r.cachedVisibility = b.w;
    return r;
}

ReservoirGI emptyReservoirGI() {
    ReservoirGI r;
    r.dir       = vec3(0.0, 1.0, 0.0);
    r.wSum      = 0.0;
    r.M         = 0.0;
    r.age       = 0.0;
    r.targetPdf = 1.0;
    r.cachedVisibility = -1.0; // invalid, needs trace
    return r;
}

bool isReservoirGIEmpty(ReservoirGI r) {
    // M tracks effective candidate count. Below 0.5 means no valid sample was
    // ever accepted -> treat as empty regardless of wSum.
    return r.M < 0.5 || r.wSum <= 0.0;
}

void updateReservoirGI(inout ReservoirGI r, vec3 dir, float weight, float pdf, inout uint seed) {
    if (!(weight > 0.0) || isnan(weight) || isinf(weight)) {
        return;
    }
    r.wSum += weight;
    r.M += 1.0;
    if (rndf(seed) * r.wSum < weight) {
        r.dir = dir;
        r.targetPdf = pdf;
    }
}

// Symmetric RIS merge for GI reservoirs. The Jacobian term handles the
// visibility/normal mismatch between the donor pixel and the receiver -
// typically a saturated dot(N, N_neighbor) check. This is the simplified
// variant from the ReSTIR GI paper (Eq. 6) that omits the full target/pdf
// ratio because cosine-weighted sampling makes both sides proportional.
// v0.6: propagates cachedVisibility from the chosen reservoir.
ReservoirGI combineReservoirsGI(ReservoirGI ra, ReservoirGI rb, float jacobian, float maxM, inout uint seed) {
    if (isReservoirGIEmpty(rb) || jacobian <= 0.0) return ra;
    if (isReservoirGIEmpty(ra)) {
        ReservoirGI r = rb;
        r.wSum = rb.wSum * jacobian;
        r.M    = min(rb.M * jacobian, maxM);
        return r;
    }

    ReservoirGI r;
    float wb = rb.wSum * jacobian;
    r.wSum   = ra.wSum + wb;
    r.M      = min(ra.M + rb.M * jacobian, maxM);
    r.age    = min(ra.age, rb.age);

    float totalW = max(r.wSum, 1e-6);
    if (rndf(seed) * totalW < ra.wSum) {
        r.dir       = ra.dir;
        r.targetPdf = ra.targetPdf;
        r.cachedVisibility = ra.cachedVisibility;
    } else {
        r.dir       = rb.dir;
        r.targetPdf = rb.targetPdf;
        r.cachedVisibility = rb.cachedVisibility;
    }
    return r;
}

#endif // RESTIR_RESERVOIR_GLSL