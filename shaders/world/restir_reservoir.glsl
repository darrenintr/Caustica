// ReSTIR reservoir for Direct Illumination sampling.
// See: Bitterli et al. 2020, "Spatiotemporal reservoir resampling..."

#ifndef RESTIR_RESERVOIR_GLSL
#define RESTIR_RESERVOIR_GLSL

// Reservoir state (packed into uvec4 = 128-bit per pixel)
struct Reservoir {
    uint lightIndex;    // selected light ID (0-based index into block light buffer)
    float wSum;         // sum of weights (for unbiased estimator)
    float M;            // effective sample count (# of samples contributing)
    uint age;           // frames since last reset (for staleness check)
};

// Pack reservoir into uvec4 for storage
uvec4 packReservoir(Reservoir r) {
    return uvec4(
        r.lightIndex,
        floatBitsToUint(r.wSum),
        packHalf2x16(vec2(r.M, float(r.age))),
        0u
    );
}

// Unpack reservoir from uvec4
Reservoir unpackReservoir(uvec4 packed) {
    Reservoir r;
    r.lightIndex = packed.x;
    r.wSum = uintBitsToFloat(packed.y);
    vec2 mAge = unpackHalf2x16(packed.z);
    r.M = mAge.x;
    r.age = uint(mAge.y);
    return r;
}

// Initialize empty reservoir
Reservoir emptyReservoir() {
    Reservoir r;
    r.lightIndex = 0xFFFFFFFFu; // invalid
    r.wSum = 0.0;
    r.M = 0.0;
    r.age = 0u;
    return r;
}

// Update reservoir with a new sample using weighted reservoir sampling (WRS)
// weight = contribution / pdf (target function / proposal density)
void updateReservoir(inout Reservoir r, uint lightIdx, float weight, inout uint seed) {
    r.wSum += weight;
    r.M += 1.0;
    // Weighted random selection: accept with probability weight / wSum
    if (rndf(seed) < weight / max(r.wSum, 1e-6)) {
        r.lightIndex = lightIdx;
    }
}

// Combine two reservoirs (temporal or spatial reuse)
// MIS weight for unbiased combination: w_q = p_q(y) / p_combined(y)
Reservoir combineReservoirs(Reservoir ra, Reservoir rb, float misWeight, float maxM) {
    if (rb.lightIndex == 0xFFFFFFFFu) return ra;
    if (ra.lightIndex == 0xFFFFFFFFu) return rb;

    Reservoir r;
    r.wSum = ra.wSum + rb.wSum * misWeight;
    r.M = min(ra.M + rb.M * misWeight, maxM);
    r.age = min(ra.age, rb.age);

    // Select sample from combined distribution
    float totalWeight = ra.wSum + rb.wSum * misWeight;
    if (totalWeight > 1e-6) {
        // Keep ra's sample with probability ra.wSum / totalWeight
        if (hash1(floatBitsToUint(totalWeight)) < ra.wSum / totalWeight) {
            r.lightIndex = ra.lightIndex;
        } else {
            r.lightIndex = rb.lightIndex;
        }
    } else {
        r.lightIndex = ra.lightIndex;
    }

    return r;
}

// Finalize reservoir: compute unbiased contribution weight
// Returns: selected_light_contribution / p_target(selected)
// where p_target = sum of all target functions seen
float getReservoirContribution(Reservoir r) {
    if (r.lightIndex == 0xFFFFFFFFu || r.M < 0.5) return 0.0;
    // Unbiased estimator: (1/M) * wSum
    return r.wSum / max(r.M, 1.0);
}

#endif // RESTIR_RESERVOIR_GLSL
