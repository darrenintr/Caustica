#version 460
#extension GL_EXT_ray_tracing : require

// RADV isolation stub: closest-hit that writes a constant payload and never follows
// buffer_reference BDAs (section table / prim / uv). If the fixed SQC GPUVM fault
// disappears with this shader, the crash is in the hit BDA chain; if it remains, the
// crash is in AS/SBT/trace infrastructure before shading.

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    vec3 motionPrev;
    vec3 f0;
    uint flags;
    uint roughMetal;
    uint emissionSss;
    uint rayCone;
};
layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 attribs;

void main() {
    payload.albedo = vec3(0.55, 0.55, 0.55);
    // Constant toward-viewer-ish normal so lighting doesn't NaN; exact value unused for isolation.
    payload.normal = vec3(0.0, 1.0, 0.0);
    payload.hitT = gl_HitTEXT;
    payload.motionPrev = vec3(0.0);
    payload.f0 = vec3(0.04);
    payload.flags = 0u; // MATERIAL_OPAQUE
    payload.roughMetal = packHalf2x16(vec2(0.8, 0.0));
    payload.emissionSss = packHalf2x16(vec2(0.0, 0.0));
    // Leave rayCone alone if raygen seeded it; zero is safe for stub.
    payload.rayCone = 0u;
}
