// Caustica — Linux Vulkan FSR3 upscaler JNI shim (implementation)
//
// Uses the AMD ffx-api C headers directly (ffx_api.h, ffx_upscale.h,
// ffx_api_vk.h). Plain C API keeps the shim small and matches the
// upstream pattern.
//
// The ffx_api.h headers declare types inside `extern "C"` and wrap the
// typedef `ffxContext` (a typedef of `void*`). Because it lives inside
// extern "C", C++ code referring to `ffxContext` as a struct member name
// collides. We keep the field opaque (`void*`) and pass its address to
// the C-style function call.

#include "ffx_fsr3_shim.h"

#include <ffx_api/ffx_api.h>
#include <ffx_api/ffx_upscale.h>
#include <ffx_api/vk/ffx_api_vk.h>

#include <new>

#ifndef FFX_UPSCALER_VERSION_MAJOR
#define FFX_UPSCALER_VERSION_MAJOR 3
#define FFX_UPSCALER_VERSION_MINOR 4
#define FFX_UPSCALER_VERSION_PATCH 0
#endif

namespace {
struct CausticaFsr3Ctx {
    void* ffxCtx = nullptr;
    uint32_t backendFlags = 0;
};

FfxApiResource wrapTexture(uint64_t imageHandle, uint64_t viewHandle,
                           uint32_t width, uint32_t height, VkFormat format,
                           FfxApiResourceState state,
                           FfxApiResourceUsage usage) {
    (void)viewHandle;
    (void)format;
    FfxApiResource r{};
    r.resource = reinterpret_cast<void*>(imageHandle);
    r.description.type = FFX_API_RESOURCE_TYPE_TEXTURE2D;
    r.description.width = width;
    r.description.height = height;
    r.description.depth = 1;
    r.description.mipCount = 1;
    r.description.usage = usage;
    r.state = state;
    return r;
}
} // namespace

extern "C" int32_t caustica_ffx_fsr3_probe(void) {
    return FFX_UPSCALER_VERSION_MAJOR * 10000
         + FFX_UPSCALER_VERSION_MINOR * 100
         + FFX_UPSCALER_VERSION_PATCH;
}

extern "C" int32_t caustica_ffx_fsr3_query_upscale_ratio(int32_t quality_mode) {
    static const float ratios[5] = {1.0f, 1.5f, 1.7f, 2.0f, 3.0f};
    if (quality_mode < 0 || quality_mode >= 5) return 0;
    float r = ratios[quality_mode];
    return (r > 0.0f && r < 10.0f) ? 1 : 0;
}

extern "C" int32_t caustica_ffx_fsr3_query_jitter_phase_count(int32_t render_w, int32_t display_w) {
    if (render_w <= 0 || display_w <= 0) return 0;
    int ratioNum = display_w / (render_w > 0 ? render_w : 1);
    if (ratioNum <= 1) return 8;
    if (ratioNum == 2) return 16;
    if (ratioNum == 3) return 32;
    return 16;
}

extern "C" int32_t caustica_ffx_fsr3_query_jitter_offset(int32_t index, int32_t phase_count,
                                                         float* out_x, float* out_y) {
    if (!out_x || !out_y || phase_count <= 0) return -1;
    static const int p2[] = {0, 1, 1, 1, 2, 2, 3, 3, 3, 3, 4, 4, 5};
    static const int p3[] = {0, 1, 1, 2, 1, 3, 2, 3, 1, 4, 3, 5, 2};
    int idx = ((index % phase_count) + phase_count) % phase_count;
    int i2 = idx & 0x7;
    int i3 = (idx / 8) & 0x7;
    *out_x = (2.0f * p2[i2] + 1.0f) / 16.0f - 0.5f;
    *out_y = (2.0f * p3[i3] + 1.0f) / 16.0f - 0.5f;
    return 0;
}

extern "C" int32_t caustica_ffx_fsr3_create(uint64_t vk_device,
                                           uint64_t vk_physical,
                                           uint32_t flags,
                                           uint32_t max_render_w,
                                           uint32_t max_render_h,
                                           uint32_t upscale_w,
                                           uint32_t upscale_h,
                                           void** out_ctx) {
    if (!out_ctx || !vk_device || !vk_physical
        || max_render_w == 0 || max_render_h == 0
        || upscale_w == 0 || upscale_h == 0) {
        return -1;
    }

    auto* c = new (std::nothrow) CausticaFsr3Ctx();
    if (!c) return -2;

    ffxCreateContextDescUpscale createDesc{};
    createDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    createDesc.flags = flags;
    createDesc.maxRenderSize.width = max_render_w;
    createDesc.maxRenderSize.height = max_render_h;
    createDesc.maxUpscaleSize.width = upscale_w;
    createDesc.maxUpscaleSize.height = upscale_h;

    // The Vulkan backend description is chained off the create desc's pNext
    // pointer. The third argument to ffxCreateContext is allocation
    // callbacks (nullptr = use defaults).
    ffxCreateBackendVKDesc backendDesc{};
    backendDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    backendDesc.vkDevice = reinterpret_cast<VkDevice>(vk_device);
    backendDesc.vkPhysicalDevice = reinterpret_cast<VkPhysicalDevice>(vk_physical);
    backendDesc.vkDeviceProcAddr = vkGetDeviceProcAddr;
    createDesc.header.pNext = &backendDesc.header;

    ffxReturnCode_t rc = ffxCreateContext(&c->ffxCtx, &createDesc.header, nullptr);
    if (rc != FFX_API_RETURN_OK) {
        delete c;
        return (int32_t)rc;
    }
    c->backendFlags = flags;
    *out_ctx = c;
    return 0;
}

extern "C" int32_t caustica_ffx_fsr3_destroy(void* ctx) {
    if (!ctx) return 0;
    auto* c = static_cast<CausticaFsr3Ctx*>(ctx);
    if (c->ffxCtx) {
        ffxDestroyContext(&c->ffxCtx, nullptr);
    }
    delete c;
    return 0;
}

extern "C" int32_t caustica_ffx_fsr3_dispatch(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t color_image,    uint64_t color_view,
    uint64_t depth_image,    uint64_t depth_view,
    uint64_t motion_image,   uint64_t motion_view,
    uint64_t output_image,   uint64_t output_view,
    uint64_t reactive_image, uint64_t reactive_view,
    uint64_t exposure_image, uint64_t exposure_view,
    uint32_t render_w, uint32_t render_h,
    uint32_t upscale_w, uint32_t upscale_h,
    float jitter_x, float jitter_y,
    float frame_time_delta_ms,
    float pre_exposure,
    float camera_near,
    float camera_far,
    float camera_fov_y,
    float view_space_to_meters,
    uint32_t reset,
    uint32_t allow_tearing_check) {
    (void)view_space_to_meters;
    (void)allow_tearing_check;
    if (!ctx || !vk_command_buffer) return -1;
    auto* c = static_cast<CausticaFsr3Ctx*>(ctx);
    if (!c->ffxCtx) return -2;

    ffxDispatchDescUpscale d{};
    d.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    d.commandList = reinterpret_cast<void*>(vk_command_buffer);

    d.color = wrapTexture(color_image, color_view, render_w, render_h,
                          VK_FORMAT_R16G16B16A16_SFLOAT,
                          FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ,
                          FFX_API_RESOURCE_USAGE_READ_ONLY);
    d.depth = wrapTexture(depth_image, depth_view, render_w, render_h,
                          VK_FORMAT_R32_SFLOAT,
                          FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ,
                          FFX_API_RESOURCE_USAGE_READ_ONLY);
    d.motionVectors = wrapTexture(motion_image, motion_view, render_w, render_h,
                                   VK_FORMAT_R16G16B16A16_SFLOAT,
                                   FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ,
                                   FFX_API_RESOURCE_USAGE_READ_ONLY);
    d.output = wrapTexture(output_image, output_view, upscale_w, upscale_h,
                           VK_FORMAT_R16G16B16A16_SFLOAT,
                           FFX_API_RESOURCE_STATE_UNORDERED_ACCESS,
                           FFX_API_RESOURCE_USAGE_UAV);
    if (reactive_image && reactive_view) {
        d.reactive = wrapTexture(reactive_image, reactive_view, render_w, render_h,
                                 VK_FORMAT_R32_SFLOAT,
                                 FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ,
                                 FFX_API_RESOURCE_USAGE_READ_ONLY);
    }
    if (exposure_image && exposure_view) {
        d.exposure = wrapTexture(exposure_image, exposure_view, 1, 1,
                                 VK_FORMAT_R32_SFLOAT,
                                 FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ,
                                 FFX_API_RESOURCE_USAGE_READ_ONLY);
    }
    d.jitterOffset.x = jitter_x;
    d.jitterOffset.y = jitter_y;
    d.motionVectorScale.x = 1.0f;
    d.motionVectorScale.y = 1.0f;
    d.renderSize.width = render_w;
    d.renderSize.height = render_h;
    d.upscaleSize.width = upscale_w;
    d.upscaleSize.height = upscale_h;

    // Caustica packs (2.0 + sharpness) into the preExposure slot;
    // FSR3 expects a literal pre-exposure multiplier. Translate.
    d.preExposure = (pre_exposure >= 2.0f) ? (pre_exposure - 2.0f) : pre_exposure;
    if (d.preExposure <= 0.0f) d.preExposure = 1.0f;
    d.enableSharpening = pre_exposure > 0.0f;
    d.sharpness = (d.preExposure > 1.0f) ? (d.preExposure - 1.0f) : 0.55f;
    if (d.sharpness > 1.0f) d.sharpness = 1.0f;

    d.frameTimeDelta = frame_time_delta_ms > 0.0f ? frame_time_delta_ms : 16.6f;
    d.cameraNear = camera_near > 0.0f ? camera_near : 0.05f;
    d.cameraFar = camera_far > 0.0f ? camera_far : 10000.0f;
    d.cameraFovAngleVertical = (camera_fov_y > 0.15f && camera_fov_y < 2.5f)
                                  ? camera_fov_y : 1.2217305f;
    d.reset = reset != 0u;
    d.flags = 0;

    ffxReturnCode_t rc = ffxDispatch(&c->ffxCtx, &d.header);
    return (int32_t)rc;
}
