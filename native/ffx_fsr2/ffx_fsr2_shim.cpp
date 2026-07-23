#include "ffx_fsr2_shim.h"
#include <new>

#ifndef FFX_FSR2_VERSION_MAJOR
#define FFX_FSR2_VERSION_MAJOR 2
#define FFX_FSR2_VERSION_MINOR 2
#define FFX_FSR2_VERSION_PATCH 2
#endif

// ABI version of the shim itself — bumped when the dispatch signature gains
// fields. The probe return value embeds both FSR SDK version (high digits) and
// shim ABI (low 2 digits): 20202 → v1 (legacy), 20302 → v2 (reactive mask).
#define CAUSTICA_FFX_FSR2_SHIM_ABI_V1 1
#define CAUSTICA_FFX_FSR2_SHIM_ABI_V2 2

struct CausticaFsr2Ctx {
    bool full = false;
};

extern "C" int caustica_ffx_fsr2_probe(void) {
    // FSR SDK version only. Java detects v2 ABI by SymbolLookup presence of
    // caustica_ffx_fsr2_dispatch_v2 — keeps the probe number comparable to
    // old SOs (20202 = FSR 2.2.2 across both ABIs).
    return FFX_FSR2_VERSION_MAJOR * 10000
         + FFX_FSR2_VERSION_MINOR * 100
         + FFX_FSR2_VERSION_PATCH;
}

extern "C" int caustica_ffx_fsr2_create(
    uint64_t /*vk_device*/,
    uint64_t /*vk_physical*/,
    uint32_t /*flags*/,
    uint32_t max_render_w,
    uint32_t max_render_h,
    uint32_t display_w,
    uint32_t display_h,
    void** out_ctx) {
    if (out_ctx == nullptr || max_render_w == 0 || max_render_h == 0 || display_w == 0 || display_h == 0) {
        return -1;
    }
#if defined(CAUSTICA_FFX_FSR2_FULL)
    // TODO: ffxGetInterfaceVK + ffxFsr2ContextCreate
    return -100;
#else
    auto* ctx = new (std::nothrow) CausticaFsr2Ctx();
    if (!ctx) return -2;
    ctx->full = false;
    *out_ctx = ctx;
    return 1; // shell only
#endif
}

extern "C" int caustica_ffx_fsr2_destroy(void* ctx) {
    if (!ctx) return 0;
    delete static_cast<CausticaFsr2Ctx*>(ctx);
    return 0;
}

extern "C" int caustica_ffx_fsr2_dispatch(
    void* /*ctx*/,
    uint64_t /*vk_command_buffer*/,
    uint64_t /*color_view*/,
    uint64_t /*depth_view*/,
    uint64_t /*motion_view*/,
    uint64_t /*output_view*/,
    uint32_t /*render_w*/,
    uint32_t /*render_h*/,
    float /*jitter_x*/,
    float /*jitter_y*/,
    float /*frame_time_delta*/,
    float /*pre_exposure*/,
    float /*camera_near*/,
    float /*camera_far*/,
    float /*camera_fov_y*/,
    int /*reset*/) {
    return -100; // not FULL
}

extern "C" int caustica_ffx_fsr2_dispatch_v2(
    void* /*ctx*/,
    uint64_t /*vk_command_buffer*/,
    uint64_t /*color_view*/,
    uint64_t /*depth_view*/,
    uint64_t /*motion_view*/,
    uint64_t /*output_view*/,
    uint64_t /*reactive_view*/,
    uint32_t /*render_w*/,
    uint32_t /*render_h*/,
    float /*jitter_x*/,
    float /*jitter_y*/,
    float /*frame_time_delta*/,
    float /*pre_exposure*/,
    float /*camera_near*/,
    float /*camera_far*/,
    float /*camera_fov_y*/,
    int /*reset*/) {
    return -100; // not FULL
}