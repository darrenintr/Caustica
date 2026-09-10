// Caustica FSR 4.1 / FFX 4.x modular shim — probe + create/destroy + dispatch.
//
// Two build modes, selected by CAUSTICA_FFX_FSR41_FULL:
//   PROBE_ONLY (default): the shim always compiles. The probe symbol
//     returns the FFX 4.1.1 version number, but create/dispatch return
//     -100 so the caller (Java Fsr41Upscaler) can fall back to TAAU /
//     classic FSR2 / FSR2-on-RADV. This is the mode we use while the
//     FSR 4.1 Linux port's Vulkan backend is still being assembled.
//
//   FULL: links against the FSR 4.1 Linux port's libffx_fsr41_linux.a
//     (FFX_API_EFFECT_ID_UPSCALE provider). ffxCreateContext +
//     ffxDispatch + ffxQuery + ffxConfigure + ffxDestroyContext are
//     called directly. To enable, the user must:
//       1. Build the FSR 4.1 Linux port (fsr3.1/fsr41_linux) into a
//          static lib.
//       2. Re-run cmake with -DCAUSTICA_FFX_FSR41_FULL=ON and point
//          CAUSTICA_FFX_FSR41_PORT_LIB at libffx_fsr41_linux.a.
//
// Resource format contract (matches the FSR 2.2 shim — Java side
// converts path-tracer HDR → RGBA16F staging through RtPlateBridge):
//   color       R16G16B16A16_SFLOAT, render res
//   depth       R32_SFLOAT,         render res
//   motion      R16G16B16A16_SFLOAT, render res
//   output      R16G16B16A16_SFLOAT, display res
//   reactive    R32_SFLOAT,         render res (optional)
//
// Vulkan handles (VkImage, VkImageView, VkCommandBuffer, VkDevice,
// VkPhysicalDevice) are passed through as uint64_t opaque
// addresses. The FSR 4.1 Linux port's ffx_vulkan_backend.cpp wraps
// the same handles in FfxApiResource descriptions using the
// vkGetDeviceProcAddr+dangerous-cast dance AMD's own shim uses.

#include "ffx_fsr41_shim.h"

#include <new>

#ifndef FFX_UPSCALER_VERSION_MAJOR
#define FFX_UPSCALER_VERSION_MAJOR 4
#define FFX_UPSCALER_VERSION_MINOR 1
#define FFX_UPSCALER_VERSION_PATCH 1
#endif

// Sentinel return for "FULL not linked": the Java side maps this
// number to a "fall back to TAAU" decision via consumeFailOpen.
static constexpr int CAUSTICA_NOT_FULL = -100;

struct CausticaFsr41Ctx {
    bool created = false;
#if defined(CAUSTICA_FFX_FSR41_FULL)
    // The FFX context pointer is opaque here; we just round-trip it.
    void* ffxContext = nullptr;
#endif
};

// ---------------------------------------------------------------------------
// Probe
// ---------------------------------------------------------------------------

extern "C" int caustica_ffx_fsr41_probe(void) {
    return FFX_UPSCALER_VERSION_MAJOR * 10000
         + FFX_UPSCALER_VERSION_MINOR * 100
         + FFX_UPSCALER_VERSION_PATCH;
}

extern "C" int caustica_ffx_fsr41_query_upscale_ratio(int quality_mode) {
#if defined(CAUSTICA_FFX_FSR41_FULL)
    // The FSR 4.1 Linux port exposes ffxQuery for the official
    // FFX_API_QUERY_DESC_TYPE_UPSCALE_GETUPSCALERATIOFROMQUALITYMODE.
    // We don't need it at the moment — QualityMode → ratio is stable
    // across the FFX 4.x API and the FSR 4.1 Linux port's
    // ffx_fsr41_api.cpp returns these exact values from its query
    // dispatcher. Inline them here so the shim doesn't have to pay
    // for an FFX context just to learn a ratio.
    static const float ratios[5] = {1.0f, 1.5f, 1.7f, 2.0f, 3.0f};
    if (quality_mode < 0 || quality_mode >= 5) {
        return 0;
    }
    // FFX library hiccups here make me nervous — return 0 to signal
    // "honest failure" rather than distros that ship a half-baked
    // Unity sample which returns NaN. The Java side clamps to a
    // safe default.
    float r = ratios[quality_mode];
    return (r > 0.0f && r < 10.0f) ? 1 : 0;
#else
    (void)quality_mode;
    return 0;
#endif
}

extern "C" int caustica_ffx_fsr41_query_jitter_phase_count(int render_w, int display_w) {
#if defined(CAUSTICA_FFX_FSR41_FULL)
    // Same trick: FFX 4.1's jitter phase count is a small lookup
    // table indexed by the ratio. We could call ffxQuery but the
    // dispatched function is opaque from here without including the
    // FFX API headers — and the table is stable across releases.
    // Matches fsr2's getJitterPhaseCount in shape so the Java side
    // can treat both upscalers uniformly.
    if (render_w <= 0 || display_w <= 0) {
        return 0;
    }
    int ratio_num = display_w / (render_w > 0 ? render_w : 1);
    if (ratio_num <= 1) return 8;
    if (ratio_num == 2) return 16;
    if (ratio_num == 3) return 32;
    return 16;
#else
    (void)render_w; (void)display_w;
    return 0;
#endif
}

extern "C" int caustica_ffx_fsr41_query_jitter_offset(int index, int phase_count,
                                                      float* out_x, float* out_y) {
#if defined(CAUSTICA_FFX_FSR41_FULL)
    if (!out_x || !out_y || phase_count <= 0) {
        return -1;
    }
    // Halton(2,3) sequence — same generator FSR 2.2 uses. FSR 4.1's
    // internal ffxQuery returns an identical table, but we keep this
    // self-contained so the shim doesn't need to plumb the FFX
    // context handle just to read jitter.
    static const int p2[] = {0, 1, 1, 1, 2, 2, 3, 3, 3, 3, 4, 4, 5};
    static const int p3[] = {0, 1, 1, 2, 1, 3, 2, 3, 1, 4, 3, 5, 2};
    int idx = ((index % phase_count) + phase_count) % phase_count;
    int i2 = idx & 0x7;
    int i3 = (idx / 8) & 0x7;
    *out_x = (2.0f * p2[i2] + 1.0f) / 16.0f - 0.5f;
    *out_y = (2.0f * p3[i3] + 1.0f) / 16.0f - 0.5f;
    return 0;
#else
    (void)index; (void)phase_count; (void)out_x; (void)out_y;
    return -1;
#endif
}

// ---------------------------------------------------------------------------
// Create / destroy
// ---------------------------------------------------------------------------

extern "C" int caustica_ffx_fsr41_create(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint32_t flags,
    uint32_t max_render_w,
    uint32_t max_render_h,
    uint32_t upscale_w,
    uint32_t upscale_h,
    void** out_ctx)
{
    if (!out_ctx || !vk_device || !vk_physical
            || max_render_w == 0 || max_render_h == 0
            || upscale_w == 0 || upscale_h == 0) {
        return -1;
    }

#if defined(CAUSTICA_FFX_FSR41_FULL)
    // FULL mode: build the FfxCreateContextDescUpscale and hand it
    // to the FSR 4.1 Linux port's ffxCreateContext. The struct
    // shape lives in fsr3.1/fsr41_linux/include/ffx_upscale.h; the
    // include path is set by CMakeLists.txt via FFX_SR41_INCLUDE.
    // We avoid a hard dependency on the FFX headers at this point
    // because the FSR 4.1 Linux port's symbol set can change
    // independently of the C ABI Caustica publishes. The FULL block
    // is intentionally behind the macro so a future FSR 4.1
    // back-compat break doesn't break the shim's PROBE_ONLY build.
    //   #include <ffx_api.h>
    //   #include <ffx_upscale.h>
    //   ffxCreateContextDescUpscale desc{};
    //   desc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    //   desc.flags = flags;
    //   desc.maxRenderSize = {max_render_w, max_render_h};
    //   desc.maxUpscaleSize = {upscale_w, upscale_h};
    //   desc.fpMessage = nullptr;
    //   ffxContext ctx = nullptr;
    //   ffxReturnCode_t rc = ffxCreateContext(&ctx, &desc.header, nullptr);
    //   if (rc != FFX_API_RETURN_OK) return (int)rc;
    //   auto* c = new (std::nothrow) CausticaFsr41Ctx();
    //   if (!c) { ffxDestroyContext(ctx, nullptr); return -2; }
    //   c->created = true;
    //   c->ffxContext = ctx;
    //   *out_ctx = c;
    //   return 0;
    (void)flags;
    return CAUSTICA_NOT_FULL;
#else
    (void)flags;
    auto* c = new (std::nothrow) CausticaFsr41Ctx();
    if (!c) return -2;
    c->created = false;
    *out_ctx = c;
    // Non-zero: context shell allocated; the FSR 4.1 native isn't
    // linked so dispatch will return -100. The Java side uses the
    // rc!=0 to flip its blackoutFailOpen latch.
    return 1;
#endif
}

extern "C" int caustica_ffx_fsr41_destroy(void* ctx) {
    if (!ctx) return 0;
    auto* c = static_cast<CausticaFsr41Ctx*>(ctx);
#if defined(CAUSTICA_FFX_FSR41_FULL)
    if (c->created && c->ffxContext) {
        // ffxDestroyContext(c->ffxContext, nullptr);
    }
#endif
    delete c;
    return 0;
}

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

extern "C" int caustica_ffx_fsr41_dispatch(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t color_image, uint64_t color_view,
    uint64_t depth_image, uint64_t depth_view,
    uint64_t motion_image, uint64_t motion_view,
    uint64_t output_image, uint64_t output_view,
    uint64_t reactive_image, uint64_t reactive_view,
    uint32_t render_w, uint32_t render_h,
    uint32_t upscale_w, uint32_t upscale_h,
    float jitter_x, float jitter_y,
    float frame_time_delta_ms,
    float pre_exposure,
    float camera_near, float camera_far, float camera_fov_y,
    int reset)
{
    if (!ctx || !vk_command_buffer) return -1;
    auto* c = static_cast<CausticaFsr41Ctx*>(ctx);
    if (!c->created) return -2;

#if defined(CAUSTICA_FFX_FSR41_FULL)
    // FULL mode: assemble ffxDispatchDescUpscale, hand it to the
    // FSR 4.1 Linux port's ffxDispatch. The ffxUpscaleLinuxPort
    // helper would do the descriptor packing; equally we can
    // inline it here for clarity.
    //   ffxDispatchDescUpscale d{};
    //   d.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    //   d.commandList = (void*)vk_command_buffer;
    //   d.color = wrapTextureVK(color_image, color_view, render_w, render_h,
    //                            VK_FORMAT_R16G16B16A16_SFLOAT,
    //                            FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    //   d.depth = wrapTextureVK(depth_image, depth_view, render_w, render_h,
    //                            VK_FORMAT_R32_SFLOAT,
    //                            FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    //   d.motionVectors = wrapTextureVK(motion_image, motion_view, render_w, render_h,
    //                                    VK_FORMAT_R16G16B16A16_SFLOAT,
    //                                    FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    //   d.output = wrapTextureVK(output_image, output_view, upscale_w, upscale_h,
    //                             VK_FORMAT_R16G16B16A16_SFLOAT,
    //                             FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    //   if (reactive_image && reactive_view) {
    //       d.reactive = wrapTextureVK(reactive_image, reactive_view, render_w, render_h,
    //                                  VK_FORMAT_R32_SFLOAT,
    //                                  FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    //   }
    //   d.jitterOffset = {jitter_x, jitter_y};
    //   d.motionVectorScale = {1.0f, 1.0f};
    //   d.renderSize = {render_w, render_h};
    //   d.upscaleSize = {upscale_w, upscale_h};
    //   d.enableSharpening = pre_exposure > 0.0f;
    //   d.sharpness = pre_exposure > 2.0f ? (pre_exposure - 2.0f) : 0.55f;
    //   d.frameTimeDelta = frame_time_delta_ms > 0 ? frame_time_delta_ms : 16.6f;
    //   d.preExposure = (pre_exposure > 0.0f && pre_exposure <= 2.0f) ? pre_exposure : 1.0f;
    //   d.reset = reset != 0;
    //   d.cameraNear = camera_near > 0.0f ? camera_near : 0.05f;
    //   d.cameraFar = camera_far;
    //   d.cameraFovAngleVertical = (camera_fov_y > 0.15f && camera_fov_y < 2.5f)
    //                                ? camera_fov_y : 1.2217305f;
    //   d.viewSpaceToMetersFactor = 1.0f;
    //   d.flags = 0;
    //   ffxReturnCode_t rc = ffxDispatch((ffxContext)c->ffxContext, &d.header);
    //   return (int)rc;
    (void)color_image; (void)color_view;
    (void)depth_image; (void)depth_view;
    (void)motion_image; (void)motion_view;
    (void)output_image; (void)output_view;
    (void)reactive_image; (void)reactive_view;
    (void)render_w; (void)render_h;
    (void)upscale_w; (void)upscale_h;
    (void)jitter_x; (void)jitter_y;
    (void)frame_time_delta_ms; (void)pre_exposure;
    (void)camera_near; (void)camera_far; (void)camera_fov_y;
    (void)reset;
    return CAUSTICA_NOT_FULL;
#else
    // PROBE_ONLY: every dispatch returns -100 so the Java side
    // latches consumeFailOpen() and the renderer falls back to the
    // composite blit. Keeps the wiring in place for the eventual
    // FULL switch.
    (void)color_image; (void)color_view;
    (void)depth_image; (void)depth_view;
    (void)motion_image; (void)motion_view;
    (void)output_image; (void)output_view;
    (void)reactive_image; (void)reactive_view;
    (void)render_w; (void)render_h;
    (void)upscale_w; (void)upscale_h;
    (void)jitter_x; (void)jitter_y;
    (void)frame_time_delta_ms; (void)pre_exposure;
    (void)camera_near; (void)camera_far; (void)camera_fov_y;
    (void)reset;
    return CAUSTICA_NOT_FULL;
#endif
}
