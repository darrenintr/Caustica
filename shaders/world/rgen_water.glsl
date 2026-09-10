#ifndef RGEN_WATER_GLSL
#define RGEN_WATER_GLSL

// ============================================================================
// rgen_water.glsl — water + glass dielectric handling and animated wave field.
//
// Depends on rgen_common (PI / payload accessors / SBT constants) and
// rgen_brdf (rrSpecularAlbedo / MIN_ROUGH).
//
// Provides:
//   - WATER_IOR / GLASS_IOR / SKY_*_ALBEDO / ray-cone / absorption constants
//   - waterExtinction / waterWaveGrad / applyWaterWaves / waterCaustic / causticLanding
//   - refractedGuideHit (water surface refracted-content guide for denoise)
//   - mirrorLobeRadiance (deterministic primary-water / glass Fresnel reflection)
// ============================================================================

// ---- Water / glass constants ----
// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
// Slightly above a pure mirror (0.02) so REBLUR is less aggressive on SPP-1 glint fireflies.
const float WATER_IOR = 1.333;
const float WATER_GUIDE_ROUGH = 0.06;
// RR guide default for sky pixels.
const vec3 SKY_SPEC_ALBEDO = vec3(0.5);
const vec3 SKY_DIFF_ALBEDO = vec3(0.5);

// Stained glass / ice (material 3): a thin colored dielectric. IOR ~1.5 (soda-lime glass).
const float GLASS_IOR = 1.52;
const float GLASS_GUIDE_ROUGH = 0.06;
// Transmitted-ray origin offset when crossing a glass face. Must be SMALLER than the terrain-side
// TRANSLUCENT_INSET (RtTerrain, 2e-4 blocks): glass is recessed into its block by that inset, so
// a face touching a slab/stair leaves a tiny gap before the neighbour's surface.
const float GLASS_TRANSMIT_BIAS = 1.0e-4;

// ---- Beer-Lambert water extinction ----
// Per-channel extinction from a water body's biome tint (carried in payload.albedo for water hits,
// or pc.waterParams.xyz for the camera's own biome when starting submerged). A blue ocean tint
// (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue.
const float WATER_DENSITY = 0.006;
const vec3  WATER_ABSORB_FLOOR = vec3(0.0015, 0.001, 0.0005);

vec3 waterExtinction(vec3 tint) {
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (vec3(1.0) - clamp(tint, 0.0, 1.0));
}

// ---- Animated water wave field ----
// Animated surface waves — a directional wave spectrum, gradient-only (the surface stays
// geometrically flat; only the normal tilts). Three things make it read as water rather than
// animated glass: sharp-crested profile, real deep-water dispersion, wind + meander. The domain
// is world-anchored (see applyWaterWaves) so the pattern is pinned in world space.
const float WATER_WAVE_STRENGTH = 0.18;
const float WAVE_SPEED = 0.8;
const int   WAVE_COUNT = 10;
const float WAVE_G = 9.81;
const vec2  WAVE_WIND = normalize(vec2(1.0, 0.35));
const float WAVE_MEANDER = 1.1;

vec2 waterWaveGrad(vec2 p, float t) {
    const float Q[WAVE_COUNT] = float[](0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038);
    t *= WAVE_SPEED;
    vec2 g = vec2(0.0);
    float lambda = 14.0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3);
        float cs = cos(spread), sn = sin(spread);
        vec2 d = vec2(WAVE_WIND.x * cs - WAVE_WIND.y * sn, WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        vec2 dph = k * d;
        if (i < 4) {
            vec2 pd = vec2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
        }
        float S = 1.2 + 0.12 * float(i);
        float e = exp(S * (sin(ph) - 1.0));
        g += (Q[i] / k) * (S * cos(ph) * e) * dph;
        lambda /= 1.5;
    }
    return g * WATER_WAVE_STRENGTH;
}

// Perturb a (near-)horizontal water surface normal by the wave field.
vec3 applyWaterWaves(vec3 nGeo, vec2 worldXZ, float t) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    vec2 grad = waterWaveGrad(worldXZ, t);
    vec3 up = normalize(vec3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
}

// ---- Water caustics ----
// Sunlight refracting through the waved surface converges/diverges before it reaches an underwater
// receiver. The caustic intensity is the inverse Jacobian determinant of that surface→floor
// mapping (area compression = brightening, real fold caustics where det → 0).
const float CAUSTIC_EPS = 0.25;
const float CAUSTIC_MAX = 6.0;

// Horizontal landing offset of a refracted sun ray entering the surface at world-stable xz.
vec2 causticLanding(vec2 xz, float t, vec3 inc, float h) {
    vec2 g = waterWaveGrad(xz, t);
    vec3 nw = normalize(vec3(-g.x, 1.0, -g.y));
    vec3 rdw = refract(inc, nw, 1.0 / WATER_IOR);
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

float waterCaustic(vec3 exitPos, vec3 lightDir, float waterDist) {
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return 1.0;
    float h = max(waterDist * lightDir.y, 0.05);
    float t = pc.waterParams.w;
    vec3 inc = -lightDir;
    vec2 base = exitPos.xz + pc.waterAnchor.xy;
    vec2 p0 = causticLanding(base, t, inc, h);
    vec2 px = causticLanding(base + vec2(CAUSTIC_EPS, 0.0), t, inc, h);
    vec2 pz = causticLanding(base + vec2(0.0, CAUSTIC_EPS), t, inc, h);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5);
    return mix(1.0, min(focus, CAUSTIC_MAX), fade);
}

// ============================================================================
// Water / glass guide helpers
// ============================================================================
// One-interface refraction for the water guide buffers: trace the refracted ray from the surface
// into (or out of) the water and report the content it lands on. Diffuse AND specular albedo, plus
// MV, then track that hit as its own feature (the water surface itself has no true diffuse/specular
// response of its own worth demodulating by — it's a clear interface).
void refractedGuideHit(vec3 surfacePos, vec3 incidentDir, vec3 surfaceNormal, bool inWaterAtSurface,
                       float rayConeWidth, float rayConeSpread,
                       out vec3 hitCamRel, out vec3 motionPrev, out bool refracted, out vec3 diffuseAlbedo,
                       out vec3 specAlbedo) {
    hitCamRel = surfacePos - pc.camOffset;
    motionPrev = vec3(0.0);
    refracted = false;
    diffuseAlbedo = vec3(0.0);
    specAlbedo = vec3(0.0);

    float etaI = inWaterAtSurface ? WATER_IOR : 1.0;
    float etaT = inWaterAtSurface ? 1.0 : WATER_IOR;
    vec3 refractedDir = refract(incidentDir, surfaceNormal, etaI / etaT);
    if (dot(refractedDir, refractedDir) <= 0.0) {
        return; // total internal reflection: no refracted guide hit exists
    }
    refractedDir = normalize(refractedDir);
    refracted = true;

    vec3 ro = surfacePos - surfaceNormal * SURF_BIAS;
    payload.hitT = -1.0;
    payloadSetTraceState(false, rayConeWidth, rayConeSpread);
    hitObjectEXT hObj;
    hitObjectTraceReorderExecuteEXT(hObj, topLevelAS, gl_RayFlagsNoneEXT, CULL_SECONDARY,
                SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u, ro, RAY_TMIN, refractedDir, 10000.0, 0);
    if (payload.hitT < 0.0) {
        hitCamRel = (ro + refractedDir * 1.0e6) - pc.camOffset;
        motionPrev = vec3(0.0);
        diffuseAlbedo = SKY_DIFF_ALBEDO;
        specAlbedo = SKY_SPEC_ALBEDO;
        return;
    }

    vec3 hitPos = ro + refractedDir * payload.hitT;
    hitCamRel = hitPos - pc.camOffset;
    uint material = payloadMaterial();
    motionPrev = material != MATERIAL_WATER ? payload.motionPrev : vec3(0.0);
    if (material == MATERIAL_OPAQUE) {
        bool pbr = (pc.flags & 2u) != 0u;
        diffuseAlbedo = pbr ? payload.albedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0)) : payload.albedo;
        if (pbr) {
            vec3 f0 = payload.f0;
            float rough = clamp(payloadRoughness(), MIN_ROUGH, 1.0);
            float NoV = clamp(dot(payload.normal, -refractedDir), 0.0, 1.0);
            specAlbedo = rrSpecularAlbedo(f0, rough * rough, NoV);
        }
    }
}

// Combined NEE shadow-visibility + water-caustic modulation. tracePath calls this on the primary
// bounce, secondary bounce, and SSS back-face — three identical inline blocks otherwise. The two
// flags come from the caller's tracePath state (kept as parameters so the helper is pure).
//
// `visT_waterT` is set by the visibility() shadow trace when the ray crossed a water face; the
// caustic only fires when the ray actually crossed water, was inside water at the receiver, and the
// caller wants animated waves.
vec3 applyNeeVisibility(vec3 origin, vec3 lightDir, float tmax, bool inWater, bool waterWaves) {
    vec3 vis = visibility(origin, lightDir, tmax);
    if (inWater && waterWaves && shadowVis.waterT > 0.0) {
        vis *= waterCaustic(origin + lightDir * shadowVis.waterT, lightDir, shadowVis.waterT);
    }
    return vis;
}

// One-bounce radiance along a mirror ray (sky miss or lit opaque hit). Used to evaluate the
// Fresnel reflection lobe on primary water/ice every frame so SPP-1 does not stochastically
// skip it (~F=0.02 when looking down → black lakes without this).
vec3 mirrorLobeRadiance(vec3 origin, vec3 dir, float coneWidth, float coneSpread,
                        out float secondaryHitDistance) {
    payload.hitT = -1.0;
    payloadSetTraceState(true, coneWidth, coneSpread);
    hitObjectEXT hObj;
    hitObjectTraceReorderExecuteEXT(hObj, topLevelAS, gl_RayFlagsNoneEXT, CULL_SECONDARY,
                SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u, origin, RAY_TMIN, dir, 10000.0, 0);
    secondaryHitDistance = payload.hitT > 0.0 ? payload.hitT : 0.0;
    if (payload.hitT < 0.0) {
        return max(payload.albedo, vec3(0.0));
    }
    uint mat = payloadMaterial();
    vec3 hitP = origin + dir * payload.hitT;
    vec3 hn = payload.normal;
    vec3 alb = max(payload.albedo, vec3(0.0));
    if (mat == MATERIAL_OPAQUE) {
        vec3 L = vec3(0.0);
        float emission = payloadEmission();
        if (emission > 0.0) {
            L += alb * emission * EMISSIVE_STRENGTH;
        }
        vec3 lightDir = pc.lightDir.xyz;
        float ndl = max(0.0, dot(hn, lightDir));
        if (ndl > 0.0) {
            vec3 vis = visibility(hitP + hn * SURF_BIAS, lightDir, pc.maxRayDistance);
            L += alb * INV_PI * pc.lightRadiance.xyz * ndl * vis;
        }
        // Lightfield GI for the *reflected* surface (filled in by tracePath's include chain).
        L += alb * lightFieldAmbient(hitP);
        L += alb * 0.01;
        return L;
    }
    if (mat == MATERIAL_GLASS) {
        return alb * 0.45;
    }
    return alb * 0.12;
}

#endif // RGEN_WATER_GLSL