#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// RADV bisect step: real section-table + prim BDA loads, no atlas/entity/WorldPush BDA.
// If this faults, the section table or primAddr chain is bad.
// If this is stable, the crash is in UV/atlas/WorldPush/any-hit side effects.

struct Prim {
    vec4 normal;
    vec4 tint;
    vec4 mat;
};
struct Section {
    uint64_t primAddr;
    uint64_t uvAddr;
    uint triBase[4];
};

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Prims { Prim p[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer SectionTable { Section s[]; };

layout(push_constant) uniform PushAddr {
    uint64_t worldPushAddr;
    uint64_t tableAddr;
    uint64_t entityTableAddr;
    uint frameIndex;
} pcAddr;

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
    // Null table → constant grey (should not happen once terrain is ready).
    if (pcAddr.tableAddr == 0ul) {
        payload.albedo = vec3(1.0, 0.0, 1.0);
        payload.normal = vec3(0.0, 1.0, 0.0);
        payload.hitT = gl_HitTEXT;
        payload.motionPrev = vec3(0.0);
        payload.f0 = vec3(0.04);
        payload.flags = 0u;
        payload.roughMetal = packHalf2x16(vec2(0.8, 0.0));
        payload.emissionSss = packHalf2x16(vec2(0.0, 0.0));
        payload.rayCone = 0u;
        return;
    }

    Section sec = SectionTable(pcAddr.tableAddr).s[gl_InstanceCustomIndexEXT];
    // Single-geom RADV BLAS: geometryIndex is always 0; still honor triBase for multi-geom layout data.
    uint pid = uint(gl_PrimitiveID) + sec.triBase[min(gl_GeometryIndexEXT, 3)];
    if (sec.primAddr == 0ul) {
        payload.albedo = vec3(1.0, 1.0, 0.0);
        payload.normal = vec3(0.0, 1.0, 0.0);
        payload.hitT = gl_HitTEXT;
        payload.motionPrev = vec3(0.0);
        payload.f0 = vec3(0.04);
        payload.flags = 0u;
        payload.roughMetal = packHalf2x16(vec2(0.8, 0.0));
        payload.emissionSss = packHalf2x16(vec2(0.0, 0.0));
        payload.rayCone = 0u;
        return;
    }

    Prim pr = Prims(sec.primAddr).p[pid];
    vec3 n = normalize(pr.normal.xyz);
    if (dot(n, -gl_WorldRayDirectionEXT) < 0.0) n = -n;

    // Tint only — no atlas / UV / WorldPush loads.
    payload.albedo = max(pr.tint.rgb, vec3(0.05));
    payload.normal = n;
    payload.hitT = gl_HitTEXT;
    payload.motionPrev = vec3(0.0);
    payload.f0 = vec3(0.04);
    payload.flags = pr.tint.w > 0.5 ? 1u : 0u; // water flag only
    payload.roughMetal = packHalf2x16(vec2(clamp(pr.mat.x, 0.05, 1.0), clamp(pr.mat.y, 0.0, 1.0)));
    payload.emissionSss = packHalf2x16(vec2(0.0, 0.0));
    payload.rayCone = 0u;
}
