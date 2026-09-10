#ifndef RGEN_BRDF_GLSL
#define RGEN_BRDF_GLSL

// ============================================================================
// rgen_brdf.glsl — GGX/Fresnel BRDF + importance sampling + primary ray construction.
//
// Depends on rgen_common (PI / INV_PI / payload accessors / SBT constants).
//
// Provides:
//   - MAX_HDR_RADIANCE / MAX_BOUNCE_RADIANCE / RR_* / SURF_BIAS / RAY_TMIN constants
//   - computeAdaptiveClampThreshold (material-aware firefly ceiling)
//   - luminance / ggxD / ggxG1 / fresnelSchlick / hg / rrSpecularAlbedo / fresnelDielectric
//   - primaryRayDir / primaryRayConeSpread (unproject clip -> world-space ray)
//   - cosineDir / sampleSquare / sampleGGXVNDF (importance-sampling helpers)
// ============================================================================

// ---- HDR radiance clamps ----
// Hard upper bound on per-pixel radiance written to outImage. The Cook–Torrance
// specular term `(D·G·F) / (4·ndv·ndl)` for a perfectly smooth mirror under
// direct noon sun evaluates to >100 before the brdf·radiance multiplier, producing a
// single-sample firefly >2000. With SPP=1 the path tracer occasionally hits that
// alignment and writes the ~2000 to the rgba16f storage image, which the FFX atrous
// colour weight (sigma 0.55..3.4) cannot dampen. The result is a saturated bright
// pixel that survives into history and reads as an orange halo around small emissive
// sources (torches, lanterns, glowstone) and around smooth surfaces near specular
// alignment. Material-aware adaptive clamping (v0.5.4): smooth metals get higher
// threshold, rough diffuse surfaces get stricter clamp, emissive-adjacent pixels get
// even tighter.
const float MAX_HDR_RADIANCE_BASE = 1.0;             // 2026-07-25: was 1.2
const float MAX_HDR_RADIANCE_SMOOTH_METAL = 2.0;     // was 2.5
const float MAX_HDR_RADIANCE_ROUGH = 0.65;            // was 0.8
const float EMISSIVE_CLAMP_SCALE = 0.35;              // was 0.4
// Per-bounce radiance clamp + tighter Russian-roulette. Bounds one bounce (NEE/BSDF/sky) so a
// single lucky sun sample cannot dump 8× energy into the plate before the adaptive HDR clamp runs.
const float MAX_BOUNCE_RADIANCE = 4.0;                // was 8.0
const float RR_Q_MIN = 0.05;                          // raise from 0.02
const float RR_Q_MAX = 0.95;                          // raise from 1.0
const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface

// ---- Reused small helpers ----
// Clamp a non-negative HDR contribution to the per-bounce ceiling. Inlined by glslangValidator; the
// helper exists so every "acc.X += clamp(throughput * ...)" in tracePath references the same constant
// rather than re-creating vec3(0.0) / vec3(MAX_BOUNCE_RADIANCE) literals at each call site — each
// inlined literal pair still expands to OpConstantF, but a single helper centralises the policy so
// future clamp changes (e.g. a sqrt-based perceptual roll-off) happen in one place.
vec3 clampToMaxBounce(vec3 x) {
    return clamp(x, vec3(0.0), vec3(MAX_BOUNCE_RADIANCE));
}

// Material-aware adaptive firefly clamping: returns the appropriate radiance ceiling for this pixel
// based on surface properties. Smooth metals allow higher peaks (legitimate specular highlights),
// rough surfaces get stricter suppression, emissive-adjacent pixels get tightest clamp.
float computeAdaptiveClampThreshold(float roughness, float metalness, bool nearEmissive) {
    float threshold = MAX_HDR_RADIANCE_BASE;

    if (roughness < 0.15 && metalness > 0.8) {
        threshold = MAX_HDR_RADIANCE_SMOOTH_METAL;
    }
    else if (roughness > 0.7) {
        threshold = MAX_HDR_RADIANCE_ROUGH;
    }

    if (nearEmissive) {
        threshold *= EMISSIVE_CLAMP_SCALE;
    }

    return threshold;
}

// ---- Color / luminance ----
float luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// ---- GGX BRDF primitives ----
// Roughness is clamped away from 0 so the delta sun's specular highlight stays finite
// and the VNDF sample is well-conditioned. alpha = roughness^2 (perceptual -> GGX).
const float MIN_ROUGH = 0.045;

// GGX normal distribution.
float ggxD(float ndh, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
float ggxG1(float ndx, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

// Schlick approximation of Fresnel reflectance for an F0 base colour.
vec3 fresnelSchlick(float cosT, vec3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
const float SSS_G = 0.6;  // forward-scatter anisotropy; leaves/grass are quite directional
const float SSS_STRENGTH = 1.0;  // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)
const int MAX_SSS_BOUNCE = 1;  // fire the transmission shadow ray on primary + first indirect hit only
float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// Reconstruction guide helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (perceptual roughness squared), NoV is the first-hit view cosine.
vec3 rrSpecularAlbedo(vec3 specularColor, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    vec4 X = vec4(1.0, NoV, NoV2, NoV * NoV2);
    vec4 Y = vec4(1.0, alpha, alpha2, alpha * alpha2);

    vec2 m1 = vec2(
            dot(vec2(0.99044, -1.28514), X.xy),
            dot(vec2(1.29678, -0.755907), X.xy));
    vec3 Xxyw = vec3(X.x, X.y, X.w);
    vec3 m2 = vec3(
            dot(vec3(1.0, 2.92338, 59.4188), Xxyw),
            dot(vec3(20.3225, -27.0302, 222.592), Xxyw),
            dot(vec3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, vec3(Y.x, Y.y, Y.w)), 1.0e-7);

    vec2 m3 = vec2(
            dot(vec2(0.0365463, 3.32707), X.xy),
            dot(vec2(9.0632, -9.04756), X.xy));
    vec3 Xxzw = vec3(X.x, X.z, X.w);
    vec3 m4 = vec3(
            dot(vec3(1.0, 3.59685, -1.36772), Xxzw),
            dot(vec3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(vec3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, vec3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + vec3(max(0.0, bias));
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches GLSL refract() returning the zero
// vector — so reflect/refract selection and TIR stay consistent.
float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// ============================================================================
// Primary ray construction
// ============================================================================
// invViewProj = inverse(proj * viewRot) maps clip to camera-relative world. Primary rays are
// reconstructed from two unprojected points (near and far plane) rather than assuming the eye is
// at the origin: Minecraft bakes view bobbing into the projection, so the effective eye comes from
// the matrix. camOffset shifts camera-relative space into the terrain's rebased coordinates. Depth
// is reversed-Z (near=1, far=0).
vec3 primaryRayDir(vec2 ndc) {
    vec4 nearH = pc.invViewProj * vec4(ndc.x, ndc.y, 1.0, 1.0);
    vec4 farH = pc.invViewProj * vec4(ndc.x, ndc.y, 0.0, 1.0);
    vec3 nearP = nearH.xyz / nearH.w;
    vec3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

// Per-pixel ray-cone spread from neighbouring ray angles. Used by hit shaders to pick texture LOD.
float primaryRayConeSpread(vec2 ndc, vec2 size, vec3 dir) {
    vec2 onePixelNdc = 2.0 / size;
    vec3 dx = primaryRayDir(ndc + vec2(onePixelNdc.x, 0.0));
    vec3 dy = primaryRayDir(ndc + vec2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), 1.0e-5);
}

// ============================================================================
// Importance-sampling helpers
// ============================================================================
// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
vec3 cosineDir(vec3 n, inout uint s) {
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    vec3 t = normalize(abs(n.x) > 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0));
    vec3 b = normalize(cross(n, t));
    t = cross(b, n);
    vec3 local = vec3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's
// sun/moon are square quads, so the NEE shadow ray samples a square (not a cone) — the same square
// the visible disc in world.rmiss uses, built from the same celestial tangent frame. halfAngle <= 0
// ⇒ exact direction (hard). (In current v0.6.4+ the path tracer disables this and uses the centre
// direction for noise-free shadows; the helper is kept for future soft-penumbra work and for any
// sampler that still wants it.)
vec3 sampleSquare(vec3 axis, float halfAngle, inout uint s) {
    vec3 right = normalize(cross(axis, pc.celestial.xyz));
    vec3 up = cross(right, axis);
    float t = tan(halfAngle);
    float u = (rndf(s) * 2.0 - 1.0) * t;
    float v = (rndf(s) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. alpha = rough^2. Paired with the separable
// Smith term, the importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
vec3 sampleGGXVNDF(vec3 n, vec3 ve, float rough, inout uint s) {
    vec3 t = normalize(abs(n.x) > 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0));
    vec3 b = normalize(cross(n, t));
    t = cross(b, n);
    vec3 Ve = vec3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = rough * rough;
    vec3 Vh = normalize(vec3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    vec3 T1 = lensq > 0.0 ? vec3(-Vh.y, Vh.x, 0.0) * inversesqrt(lensq) : vec3(1.0, 0.0, 0.0);
    vec3 T2 = cross(Vh, T1);
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    vec3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    vec3 Ne = normalize(vec3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);             // -> world space
}

#endif // RGEN_BRDF_GLSL