#include "ffx_fsr2_shim.h"
#include <new>

#ifndef FFX_FSR2_VERSION_MAJOR
#define FFX_FSR2_VERSION_MAJOR 2
#define FFX_FSR2_VERSION_MINOR 2
#define FFX_FSR2_VERSION_PATCH 2
#endif

struct CausticaFsr2Ctx {
    bool full = false;
};

extern "C" int caustica_ffx_fsr2_probe(void) {
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
