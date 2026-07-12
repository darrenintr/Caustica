#version 460
#extension GL_EXT_ray_tracing : require

// Shadow / sky-visibility miss (SBT miss index 1), used by the no-SER (CAUSTICA_SER_NONE) world raygen.
// Secondary shadow rays are traced with TerminateOnFirstHit | SkipClosestHit, so reaching this shader means
// the ray escaped without hitting opaque geometry -> the surface point is visible to the sun (or to open sky
// for AO). visibility() seeds shadowVis.escaped = 0.0 (plus the transmittance / waterT lanes) before tracing;
// here we only mark the ray escaped, leaving the colored-glass transmittance and the water-crossing t the
// any-hit accumulated intact. (The SER variants query the hit object's miss state and never run this shader.)
struct ShadowRay {
    vec3 tint;
    float waterT;
    float escaped;
};
layout(location = 1) rayPayloadInEXT ShadowRay shadowVis;

void main() {
    shadowVis.escaped = 1.0;
}
