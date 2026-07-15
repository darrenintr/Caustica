// Caustica FidelityFX Denoiser shim — probe + create/destroy skeleton.
// Probe-only build avoids pulling Windows-centric FFX headers (FFX_API=__declspec).
// Full link (CAUSTICA_FFX_DENOISER_FULL) uses official ffx_denoiser.h in Task 4+.

#include "ffx_denoiser_shim.h"

#include <new>

// Official FidelityFX Denoiser 1.2.0 version (ffx_denoiser.h).
#ifndef FFX_DENOISER_VERSION_MAJOR
#define FFX_DENOISER_VERSION_MAJOR 1
#define FFX_DENOISER_VERSION_MINOR 2
#define FFX_DENOISER_VERSION_PATCH 0
#endif

struct CausticaDenoiserCtx {
    bool created = false;
};

extern "C" int caustica_ffx_denoiser_probe(void) {
    return FFX_DENOISER_VERSION_MAJOR * 10000
         + FFX_DENOISER_VERSION_MINOR * 100
         + FFX_DENOISER_VERSION_PATCH;
}

extern "C" int caustica_ffx_denoiser_create(
    uint64_t /*vk_device*/,
    uint64_t /*vk_physical*/,
    uint32_t /*flags*/,
    uint32_t width,
    uint32_t height,
    uint32_t /*normals_format*/,
    void** out_ctx) {
    if (out_ctx == nullptr || width == 0 || height == 0) {
        return -1;
    }
    auto* ctx = new (std::nothrow) CausticaDenoiserCtx();
    if (ctx == nullptr) {
        return -2;
    }
    ctx->created = false;
    *out_ctx = ctx;
    // Non-zero: context shell allocated; full ffxDenoiserContextCreate is Task 4.
    return 1;
}

extern "C" int caustica_ffx_denoiser_destroy(void* ctx) {
    if (ctx == nullptr) {
        return 0;
    }
    delete static_cast<CausticaDenoiserCtx*>(ctx);
    return 0;
}

extern "C" int caustica_ffx_denoiser_dispatch_shadows(
    void* /*ctx*/,
    uint64_t /*vk_command_buffer*/,
    uint64_t /*hit_mask_view*/,
    uint64_t /*depth_view*/,
    uint64_t /*velocity_view*/,
    uint64_t /*normal_view*/,
    uint64_t /*shadow_out_view*/,
    uint32_t /*width*/,
    uint32_t /*height*/,
    float /*motion_scale_x*/,
    float /*motion_scale_y*/,
    uint32_t /*frame_index*/) {
    // SPIR-V hosted path in OfficialFfxDenoiseBackend owns shadow denoise until
    // CAUSTICA_FFX_DENOISER_FULL links ffxDenoiserContextDispatchShadows.
    return -100;
}

extern "C" int caustica_ffx_denoiser_dispatch_reflections(
    void* /*ctx*/,
    uint64_t /*vk_command_buffer*/,
    uint32_t /*width*/,
    uint32_t /*height*/,
    uint32_t /*frame_index*/) {
    return -100;
}
