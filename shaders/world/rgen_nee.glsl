#ifndef RGEN_NEE_GLSL
#define RGEN_NEE_GLSL

// ============================================================================
// rgen_nee.glsl — shadow visibility + screen-space projection helpers.
//
// Depends on rgen_common (SBT/CULL constants / payload accessors / PCG RNG).
// The tracePath in world.rgen also depends on these helpers for primary NEE.
//
// Provides:
//   - visibility(origin, dir, tmax) — soft-shadow trace with two backends (SER / no-SER)
//   - projectPrevNdc / previousReflectionNdc / specularReflectionMotion
//     (mirror-image projection of reflection rays for the RR spec-motion guide)
// ============================================================================

// Shadow / visibility trace. Soft shadows: hit-object miss state IS the visibility answer in the
// SER path (no miss shader runs); the no-SER path uses shadow.rmiss (SBT miss index 1) which
// flips shadowVis.escaped to 1.0 when the ray reaches open sky. TerminateOnFirstHit +
// SkipClosestHit ends traversal on any opaque blocker (no light) or on open sky (full light).
// shadowVis.tint accumulates colored-glass / water transmittance along the way.
vec3 visibility(vec3 origin, vec3 dir, float tmax) {
    shadowVis.tint = vec3(1.0);   // full transmittance until any-hit tints it
    shadowVis.waterT = -1.0;      // water-crossing sentinel, filled by the water any-hit
    shadowVis.escaped = 0.0;      // no-SER: shadow.rmiss flips this to 1.0 when the ray reaches the sun
#ifdef CAUSTICA_SER_NONE
    // No SER: trace with a real miss shader (SBT miss index 1 = shadow.rmiss) that marks escape
    // into shadowVis.escaped.
    traceRayEXT(topLevelAS,
                gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
                CULL_SECONDARY, SBT_SHADOW, SBT_STRIDE_BUCKET, 1u, origin, RAY_TMIN, dir, tmax, 1);
    return shadowVis.escaped > 0.5 ? shadowVis.tint : vec3(0.0);
#else
    // SER: trace-without-execute; the hit object's miss state IS the visibility answer (no miss
    // shader run), so there is no closest or miss shader worth executing here.
    hitObjectEXT hObj;
    hitObjectTraceRayEXT(hObj, topLevelAS,
                gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
                CULL_SECONDARY, SBT_SHADOW, SBT_STRIDE_BUCKET, 0u, origin, RAY_TMIN, dir, tmax, 1);
    return hitObjectIsMissEXT(hObj) ? shadowVis.tint : vec3(0.0);
#endif
}

// ============================================================================
// Reflection (specular) motion vector helpers
// ============================================================================
vec2 projectPrevNdc(vec3 worldPos) {
    vec4 clip = pc.prevViewProj * vec4(worldPos - pc.camOffset + pc.camDelta, 1.0);
    return clip.xy / clip.w;
}

// Previous-frame screen position of a planar reflection. The reflected image of a world point P
// seen in a planar reflector is the MIRROR image V = mirror(P) across the surface plane: the eye
// sees V along a straight line, so the reflection appears at proj(V). Project the mirror image
// directly. Mirroring is division-free, so static MV is bit-exact zero at any incidence.
vec2 previousReflectionNdc(vec3 surfacePos, vec3 surfaceNormal, vec3 reflectedWorldPos, vec3 reflectedMotionPrev) {
    vec3 n = normalize(surfaceNormal);
    vec3 prevHit = reflectedWorldPos - reflectedMotionPrev;
    vec3 mirroredHit = prevHit - 2.0 * n * dot(prevHit - surfacePos, n);
    return projectPrevNdc(mirroredHit);
}

// Reflection (specular) motion vector for temporal reconstruction. Covers smooth specular blocks
// (metals / LabPBR _s) and water reflections including underwater total-internal-reflection — any
// primary surface with a sharp specular lobe (specular albedo > 0, roughness <= 0.5). A sky miss
// is treated as a reflected hit at infinity along the reflected direction.
vec2 specularReflectionMotion(vec3 surfacePos, vec3 primaryDir, vec2 currentNdc, vec2 size, float primaryConeSpread) {
    if (length(gv_normal) < 0.5 || maxChannel(gv_specAlb) <= 0.001 || gv_rough > 0.5) {
        return vec2(0.0);
    }

    vec3 n = normalize(gv_normal);
    vec3 specDir = reflect(primaryDir, n);
    payload.hitT = -1.0;
    payloadSetTraceState(false,
            max(length(surfacePos - pc.camOffset) * primaryConeSpread, RAY_CONE_MIN_WIDTH),
            max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
    hitObjectEXT hObj;
    hitObjectTraceReorderExecuteEXT(hObj, topLevelAS, gl_RayFlagsNoneEXT, CULL_SECONDARY,
                SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u, surfacePos + n * SURF_BIAS, RAY_TMIN, specDir, pc.maxRayDistance, 0);

    vec3 reflectedHit;
    vec3 reflectedMotionPrev;
    if (payload.hitT > 0.0) {
        reflectedHit = surfacePos + specDir * payload.hitT;
        reflectedMotionPrev = payloadMaterial() == MATERIAL_OPAQUE ? payload.motionPrev : vec3(0.0);
    } else {
        reflectedHit = surfacePos + specDir * 1.0e6;
        reflectedMotionPrev = vec3(0.0);
    }
    vec2 prevNdc = previousReflectionNdc(surfacePos, n, reflectedHit, reflectedMotionPrev);
    return (prevNdc - currentNdc) * 0.5 * size;
}

#endif // RGEN_NEE_GLSL