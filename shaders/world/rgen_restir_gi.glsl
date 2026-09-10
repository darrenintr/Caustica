#ifndef RGEN_RESTIR_GI_GLSL
#define RGEN_RESTIR_GI_GLSL

// ============================================================================
// rgen_restir_gi.glsl — ReSTIR Global Illumination + hemisphere ambient fallback +
// hybrid-rendering fast-path heuristic.
//
// Depends on rgen_common (PI / SBT constants), rgen_brdf (cosineDir),
// rgen_nee (visibility), rgen_restir_di (lightFieldAmbient / sampleLightField01).
//
// Provides:
//   - GI_SPATIAL_SAMPLES / GI_* / GI_TEMPORAL_SOFT_MOTION_PX constants
//   - ReservoirGI struct + pack / unpack / empty / updateReservoirGI / combineReservoirsGI
//   - giTarget / giGenerateCandidates / giTemporalReuse / giSpatialReuse / giTraceAndShade
//   - evalGIReSTIR (tile-based 4x4 shared GI; leader traces + follower reuses direction)
//   - giHemisphereAmbient (sky / ground hemisphere fill when lightfield is unavailable)
//   - isLightingComplex + HYBRID_LIGHTFIELD_THRESHOLD (the hybrid fast-path gate)
//
// Concept: at a primary hit, "spend" the budget on choosing a reflection direction instead of a
// light index. Combine neighbor + history reservoirs to get an effective sample count of
// M_eff >> actual rays we trace, then trace ONE ray along the chosen direction and evaluate
// radiance at the hit. This is the simplified ReSTIR GI from the NVIDIA 2021 paper, with the
// target/pdf ratio replaced by a symmetric Jacobian (cosine-weighted sampling keeps target
// proportional to BRDF*cos, so the per-direction pdf cancels).
// ============================================================================

// ---- GI constants ----
const int   GI_SPATIAL_SAMPLES = 8;         // increased from 4 for better variance reduction
const float GI_SPATIAL_RADIUS_MIN = 4.0;    // min spatial search radius (px)
const float GI_SPATIAL_RADIUS_MAX = 16.0;   // max spatial search radius (px)
const float GI_MAX_RADIUS = 24.0;           // cap GI bounce distance (blocks)
const float GI_FIREFLY_CLAMP = 4.0;         // per-direction radiance ceiling
const float GI_CONFIDENCE_FLOOR = 0.08;     // below this, reject donor
const float GI_DEPTH_THRESHOLD = 0.15;      // relative depth difference threshold for spatial rejection
// Soft motion penalty (v0.5): exponential falloff in motion magnitude. At 80 px (typical walk
// speed at 60 fps) confidence drops to ~37%; at 200 px (teleport / sprint) it's effectively zero.
const float GI_TEMPORAL_SOFT_MOTION_PX = 80.0;

// ---- GI Reservoir (32 bytes/pixel: 2x rgba32f) ----
// Stored across two images so the direction stays full-precision:
//   A: dir.xyz (vec3) + wSum (float)  -> 16 bytes
//   B: M (float) + age (float) + targetPdf (float) + cachedVisibility (float) -> 16 bytes
// cachedVisibility (v0.6) stores previous frame's visibility result [0,1] to avoid retracing.
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
    // M tracks effective candidate count. Below 0.5 means no valid sample was ever accepted.
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

// Symmetric RIS merge for GI reservoirs. The Jacobian term handles the visibility/normal mismatch
// between the donor pixel and the receiver - typically a saturated dot(N, N_neighbor) check. This
// is the simplified variant from the ReSTIR GI paper (Eq. 6) that omits the full target/pdf ratio
// because cosine-weighted sampling makes both sides proportional.
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

// Target function for GI: BRDF*cos, reduced to ndl for a Lambertian receiver.
// Returns 0 when the direction is below the surface (ndl <= 0).
float giTarget(vec3 n, vec3 dir) {
    return max(0.0, dot(n, dir));
}

// Generate candidate GI reservoirs for the current pixel.
ReservoirGI giGenerateCandidates(vec3 hitPos, vec3 normal, inout uint seed) {
    ReservoirGI r = emptyReservoirGI();
    int n = max(int(pc.giCandidates), 1);
    for (int i = 0; i < n; i++) {
        vec3 dir = cosineDir(normal, seed);
        float w = giTarget(normal, dir);
        // Cosine-sampling pdf = cos/PI; weight = target/pdf = (cos/PI) / (cos/PI) = 1.
        float pdf = max(dot(normal, dir), 1e-6) * INV_PI;
        updateReservoirGI(r, dir, max(w / pdf, 0.0), pdf, seed);
    }
    return r;
}

// Temporal reuse: same pixel in the previous frame, reprojected via MV.
ReservoirGI giTemporalReuse(ReservoirGI rCurr, ivec2 pix, vec2 motion, ivec2 size,
                             vec3 hitPos, vec3 normal, inout uint seed) {
    vec2 prevUv = (vec2(pix) + 0.5 + motion) / vec2(size);
    ivec2 prevPix = ivec2(prevUv * vec2(size));
    if (any(lessThan(prevPix, ivec2(0))) || any(greaterThanEqual(prevPix, size))) {
        return rCurr;
    }

    ReservoirGI rPrev = unpackReservoirGI(
        imageLoad(gGiReservoirPrevA, prevPix),
        imageLoad(gGiReservoirPrevB, prevPix));
    if (isReservoirGIEmpty(rPrev)) return rCurr;

    float targetPrev = giTarget(normal, rPrev.dir);
    if (targetPrev <= 1e-5) return rCurr;

    float prevEstimate = max(rPrev.wSum / max(rPrev.M, 1.0), 1e-6);
    float confidence = clamp(targetPrev / prevEstimate, 0.0, 1.0);
    // Soft motion penalty: scales confidence by exp(-motion^2 / soft^2).
    float motionMag = length(motion);
    float motionFalloff = exp(-(motionMag * motionMag) / (GI_TEMPORAL_SOFT_MOTION_PX * GI_TEMPORAL_SOFT_MOTION_PX));
    confidence *= motionFalloff;
    if (confidence < GI_CONFIDENCE_FLOOR) return rCurr;

    ReservoirGI r = combineReservoirsGI(rCurr, rPrev, confidence, pc.giMaxMTemporal, seed);
    r.age = min(rPrev.age + 1.0, 4096.0);
    return r;
}

// Spatial reuse: from previous-frame reservoirs at jittered offsets.
ReservoirGI giSpatialReuse(ReservoirGI rCenter, ivec2 pix, ivec2 size,
                            vec3 hitPos, vec3 normal, inout uint seed) {
    ReservoirGI r = rCenter;

    float centerDepth = imageLoad(gDepth, pix).r;
    if (centerDepth <= 0.0) return r;

    const vec2 poissonDisk[8] = vec2[](
        vec2(-0.94201624, -0.39906216),
        vec2(0.94558609, -0.76890725),
        vec2(-0.094184101, -0.92938870),
        vec2(0.34495938, 0.29387760),
        vec2(-0.91588581, 0.45771432),
        vec2(-0.81544232, -0.87912464),
        vec2(-0.38277543, 0.27676845),
        vec2(0.97484398, 0.75648379)
    );

    for (int i = 0; i < GI_SPATIAL_SAMPLES; i++) {
        float adaptiveScale = mix(1.0, 1.5, clamp(1.0 - rCenter.M / 10.0, 0.0, 1.0));
        float radius = mix(GI_SPATIAL_RADIUS_MIN, GI_SPATIAL_RADIUS_MAX, rndf(seed));
        radius *= adaptiveScale;

        float rotation = rndf(seed) * 6.2831853;
        vec2 rotated = vec2(
            poissonDisk[i].x * cos(rotation) - poissonDisk[i].y * sin(rotation),
            poissonDisk[i].x * sin(rotation) + poissonDisk[i].y * cos(rotation)
        );
        ivec2 nPix = pix + ivec2(round(rotated * radius));
        if (any(lessThan(nPix, ivec2(0))) || any(greaterThanEqual(nPix, size))) continue;

        float neighborDepth = imageLoad(gDepth, nPix).r;
        if (neighborDepth <= 0.0) continue;
        float depthDiff = abs(centerDepth - neighborDepth);
        if (depthDiff > GI_DEPTH_THRESHOLD * centerDepth) continue;

        ReservoirGI p = unpackReservoirGI(
            imageLoad(gGiReservoirPrevA, nPix),
            imageLoad(gGiReservoirPrevB, nPix));
        if (isReservoirGIEmpty(p)) continue;

        float targetN = giTarget(normal, p.dir);
        if (targetN <= 1e-5) continue;

        // Symmetric Jacobian: cosine of angle between donor normal and receiver normal.
        vec3 nNeighbor = imageLoad(gNormal, nPix).xyz;
        if (length(nNeighbor) < 0.5) continue;
        float normalDot = dot(normalize(nNeighbor), normal);
        if (normalDot < 0.9) continue;
        float jacobian = clamp(normalDot, 0.9, 1.0);

        float neighborEst = max(p.wSum / max(p.M, 1.0), 1e-6);
        float confidence = clamp(targetN / neighborEst, 0.0, 1.0);
        if (confidence < GI_CONFIDENCE_FLOOR * 0.7) continue;

        r = combineReservoirsGI(r, p, confidence * jacobian, pc.giMaxMSpatial, seed);
    }
    return r;
}

// Hemisphere ambient fallback (raster-style fill for areas RT can't cover).
// Used by both giTraceAndShade (one-bounce indirect GI receiver) and the primary surface when
// both ReSTIR DI and the lightfield SSBO are unavailable. Defined here, before giTraceAndShade,
// because the latter calls it on the opaque branch when pc.lightFieldOriginSize.w == 0.
vec3 giHemisphereAmbient(vec3 n, vec3 worldPos) {
    vec3 up = pc.sunDir.w > 0.05 ? normalize(pc.sunDir.xyz)
                                 : normalize(pc.moonDir.xyz);
    float skyTerm = 0.5 + 0.5 * dot(n, up);             // [0,1]
    float dayFactor = clamp(pc.sunDir.w, 0.0, 1.0);

    vec3 dayTint   = vec3(0.55, 0.65, 0.85);            // cool sky
    vec3 nightTint = vec3(0.18, 0.22, 0.35);            // moonlit
    vec3 skyCol = mix(nightTint, dayTint, dayFactor);

    float skyLum = max(pc.lightRadiance.x, max(pc.lightRadiance.y, pc.lightRadiance.z));
    vec3 skyAmb  = skyCol * skyLum * pc.giAmbientParams.x * skyTerm;
    vec3 groundAmb = skyCol * 0.25 * pc.giAmbientParams.y * (1.0 - skyTerm);

    if (pc.lightFieldOriginSize.w > 0) {
        vec3 lf = lightFieldAmbient(worldPos);
        return mix(skyAmb + groundAmb, lf, pc.giAmbientParams.z);
    }
    return skyAmb + groundAmb;
}

// One-bounce GI evaluation: trace along the reservoir's chosen direction and return the radiance at
// the hit (sky radiance on miss, lightfield + NEE on hit). v0.6: accepts cachedVisibility hint.
vec3 giTraceAndShade(vec3 origin, vec3 dir, float maxDist, inout uint seed, inout float outVisibility) {
    payload.hitT = -1.0;
    payloadSetTraceState(true, RAY_CONE_MIN_WIDTH, RAY_CONE_DIFFUSE_SPREAD);
    hitObjectEXT hObj;
    hitObjectTraceReorderExecuteEXT(hObj, topLevelAS, gl_RayFlagsNoneEXT, CULL_SECONDARY,
                SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u, origin, RAY_TMIN, dir, maxDist, 0);

    outVisibility = (payload.hitT >= 0.0) ? 1.0 : 0.0;

    if (payload.hitT < 0.0) {
        // Sky hit -- payload.albedo carries the sky radiance from world.rmiss.
        return max(payload.albedo, vec3(0.0));
    }
    vec3 hitP = origin + dir * payload.hitT;
    vec3 n = payload.normal;
    vec3 alb = max(payload.albedo, vec3(0.0));
    uint mat = payloadMaterial();
    if (mat == MATERIAL_OPAQUE) {
        vec3 L = vec3(0.0);
        if (pc.lightFieldOriginSize.w > 0) {
            L += alb * lightFieldAmbient(hitP);
        } else {
            L += alb * giHemisphereAmbient(n, hitP);
        }
        // Primary NEE: the active directional light (sun above horizon, moon at night).
        vec3 lightDir = pc.lightDir.xyz;
        float lightHalf = pc.lightDir.w;
        if (lightHalf > 0.0) lightDir = sampleSquare(lightDir, lightHalf, seed);
        float ndl = max(0.0, dot(n, lightDir));
        if (ndl > 0.0) {
            vec3 vis = visibility(hitP + n * SURF_BIAS, lightDir, pc.maxRayDistance);
            L += alb * INV_PI * pc.lightRadiance.xyz * ndl * vis;
        }
        // Secondary moon-NEE: when the primary light is the sun, add the moon as a low-weight second
        // light to lift tree-cover shadows at night.
        if ((pc.flags & 4u) != 0u && pc.moonDir.w < 3.5) {
            vec3 moonL = pc.moonDir.xyz;
            float moonNdl = max(0.0, dot(n, moonL));
            if (moonNdl > 0.0) {
                vec3 moonVis = visibility(hitP + n * SURF_BIAS, moonL, pc.maxRayDistance);
                float moonPhaseWeight = max(0.0, 1.0 - pc.moonDir.w * 0.25);
                vec3 moonRad = pc.lightRadiance.xyz * 0.045 * moonPhaseWeight * moonPhaseWeight;
                L += alb * INV_PI * moonRad * moonNdl * moonVis;
            }
        }
        float peak = maxChannel(L);
        if (peak > GI_FIREFLY_CLAMP) L *= GI_FIREFLY_CLAMP / peak;
        return L;
    }
    if (mat == MATERIAL_GLASS) {
        return alb * 0.45;
    }
    return alb * 0.12;
}

vec3 evalGIReSTIR(ivec2 pix, ivec2 size, vec3 hitPos, vec3 normal, vec3 albedo,
                  vec2 motion, inout uint seed) {
    if (pc.giEnabled == 0u) return vec3(0.0);

    // TILE-BASED SHARED GI: 4×4 pixels share 1 GI sample for massive speedup + coherence.
    // v0.6.17: 8x8 was causing visible block artifacts in dark scenes — 4x4 was the fix.
    const int TILE_SIZE = 4;
    ivec2 tileID = pix / TILE_SIZE;
    ivec2 tileOrigin = tileID * TILE_SIZE;

    bool isTileLeader = (pix.x == tileOrigin.x && pix.y == tileOrigin.y);

    if (isTileLeader) {
        // Tile leader: run full ReSTIR pipeline
        ReservoirGI r = giGenerateCandidates(hitPos, normal, seed);
        r = giTemporalReuse(r, pix, motion, size, hitPos, normal, seed);
        r = giSpatialReuse(r, pix, size, hitPos, normal, seed);

        if (isReservoirGIEmpty(r)) return vec3(0.0);

        // Visibility reuse: skip trace if cached visibility is valid and fresh.
        bool needsTrace = (r.cachedVisibility < 0.0) || (r.age < 2.0) || (rndf(seed) < 0.1);

        vec3 origin = hitPos + normal * SURF_BIAS;
        vec3 radiance;
        float newVisibility = r.cachedVisibility;

        if (needsTrace) {
            radiance = giTraceAndShade(origin, r.dir, GI_MAX_RADIUS, seed, newVisibility);
            r.cachedVisibility = newVisibility;
        } else {
            if (r.cachedVisibility > 0.5) {
                radiance = giTraceAndShade(origin, r.dir, GI_MAX_RADIUS, seed, newVisibility);
                r.cachedVisibility = newVisibility;
            } else {
                vec3 skyDir = r.dir;
                float skyUp = max(0.0, skyDir.y);
                vec3 skyCol = mix(vec3(0.18, 0.22, 0.35), vec3(0.55, 0.65, 0.85), pc.sunDir.w);
                radiance = skyCol * mix(0.3, 1.0, skyUp);
            }
        }

        r.age = min(r.age + 1.0, 4096.0);
        imageStore(gGiReservoirCurrA, pix, packReservoirGiA(r));
        imageStore(gGiReservoirCurrB, pix, packReservoirGiB(r));

        float invM = 1.0 / max(r.M, 1.0);
        float W = r.wSum * invM;
        return albedo * radiance * W;
    } else {
        // Tile follower: reuse tile leader's direction but trace its own GI ray for correct radiance.
        // v0.6.14: followers used to fake-sky; in low-light the result was completely wrong.
        vec4 leaderA = imageLoad(gGiReservoirCurrA, tileOrigin);
        vec4 leaderB = imageLoad(gGiReservoirCurrB, tileOrigin);
        ReservoirGI r = unpackReservoirGI(leaderA, leaderB);

        if (isReservoirGIEmpty(r)) return vec3(0.0);

        vec3 origin = hitPos + normal * SURF_BIAS;
        float vis = 0.0;
        vec3 radiance = giTraceAndShade(origin, r.dir, GI_MAX_RADIUS, seed, vis);

        float invM = 1.0 / max(r.M, 1.0);
        float W = r.wSum * invM;
        return albedo * radiance * W;
    }
}

// ============================================================================
// Hybrid rendering complexity heuristic (v0.6)
//
// Decides whether a bounce-0 opaque pixel is "simple" enough to skip ReSTIR DI
// and ReSTIR GI entirely (relying on NEE + lightfield + hemisphere ambient).
// Cheap enough to evaluate inline in tracePath; no pre-pass needed.
//
// Returns true (= needs RT) when:
//   - The material is glass / water / particle (always benefits from RT)
//   - The surface is smooth enough to have a meaningful specular lobe (rough < 0.5)
//   - The lightfield reports nearby block-light level above HYBRID_LIGHTFIELD_THRESHOLD
//     (cheap proxy for "there's an emitter within RESTIR_MAX_RADIUS")
//
// Returns false (= raster fallback) when:
//   - Matte opaque surface (rough >= 0.5)
//   - No nearby block lights (lightfield block < threshold)
//   - No specular contribution (metal < 0.05 OR F0 very low)
// ============================================================================
const float HYBRID_LIGHTFIELD_THRESHOLD = 0.05;  // lightfield block level (0..1) above which we run RT

bool isLightingComplex(vec3 hitPos, vec3 normal, vec3 albedo, vec3 F0, float rough, float metal, uint material) {
    // Always RT for non-opaque: glass / water / particle handling needs proper bounce logic.
    if (material != MATERIAL_OPAQUE) return true;

    // Smooth specular lobe -> RT for accurate reflection of nearby lights / sky.
    if (rough < 0.5) return true;

    // Metal with any meaningful F0 -> RT (no diffuse contribution to fall back on).
    if (metal > 0.5) return true;
    float f0Lum = maxChannel(F0);
    if (f0Lum > 0.04) return true;

    // Cheap "any light within RESTIR_MAX_RADIUS" proxy via lightfield SSBO.
    if (pc.lightFieldOriginSize.w > 0) {
        vec2 sb = sampleLightField01(hitPos);
        if (sb.y > HYBRID_LIGHTFIELD_THRESHOLD) return true;
    } else if (pc.blockLightCount > 0u) {
        // No lightfield SSBO: be conservative -- if any block lights exist at all,
        // assume they're near enough to matter and run RT.
        return true;
    }

    return false;
}

#endif // RGEN_RESTIR_GI_GLSL