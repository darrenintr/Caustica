#include <vulkan/vulkan.h>
// Thin C ABI for Caustica — wraps classic FSR2 Vulkan.
#include "ffx_fsr2.h"
#include "vk/ffx_fsr2_vk.h"
#include <cstdlib>
#include <cstring>
#include <new>
#include <vector>

struct CausticaFsr2 {
    FfxFsr2Context ctx{};
    std::vector<uint8_t> scratch;
    uint32_t maxRenderW = 0, maxRenderH = 0;
    uint32_t displayW = 0, displayH = 0;
    bool created = false;
};

extern "C" int caustica_ffx_fsr2_probe(void) {
    return FFX_FSR2_VERSION_MAJOR * 10000
         + FFX_FSR2_VERSION_MINOR * 100
         + FFX_FSR2_VERSION_PATCH;
}

extern "C" int caustica_ffx_fsr2_create(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint32_t flags,
    uint32_t max_render_w,
    uint32_t max_render_h,
    uint32_t display_w,
    uint32_t display_h,
    void** out_ctx)
{
    if (!out_ctx || !vk_device || !vk_physical || !max_render_w || !max_render_h || !display_w || !display_h)
        return -1;

    auto* c = new (std::nothrow) CausticaFsr2();
    if (!c) return -2;

    VkDevice device = (VkDevice)vk_device;
    VkPhysicalDevice phys = (VkPhysicalDevice)vk_physical;

    size_t scratchSize = ffxFsr2GetScratchMemorySizeVK(phys);
    c->scratch.resize(scratchSize);

    FfxFsr2Interface iface{};
    FfxErrorCode err = ffxFsr2GetInterfaceVK(&iface, c->scratch.data(), scratchSize, phys, vkGetDeviceProcAddr);
    if (err != FFX_OK) {
        delete c;
        return (int)err;
    }

    // Caustica defaults: HDR path-traced color, reverse-Z, render-res MVs (not display res).
    uint32_t f = flags;
    if (f == 0) {
        // No AUTO_EXPOSURE: Caustica never binds an exposure texture; enabling it
        // blacks the output on RADV while ffxFsr2ContextDispatch still returns FFX_OK.
        f = FFX_FSR2_ENABLE_HIGH_DYNAMIC_RANGE
          | FFX_FSR2_ENABLE_DEPTH_INVERTED
          | FFX_FSR2_ENABLE_DEPTH_INFINITE;
    }

    FfxFsr2ContextDescription desc{};
    desc.flags = f;
    desc.maxRenderSize = { max_render_w, max_render_h };
    desc.displaySize = { display_w, display_h };
    desc.callbacks = iface;
    desc.device = ffxGetDeviceVK(device);
    desc.fpMessage = nullptr;

    err = ffxFsr2ContextCreate(&c->ctx, &desc);
    if (err != FFX_OK) {
        delete c;
        return (int)err;
    }
    c->maxRenderW = max_render_w;
    c->maxRenderH = max_render_h;
    c->displayW = display_w;
    c->displayH = display_h;
    c->created = true;
    *out_ctx = c;
    return 0;
}

extern "C" int caustica_ffx_fsr2_destroy(void* ctx) {
    if (!ctx) return 0;
    auto* c = static_cast<CausticaFsr2*>(ctx);
    if (c->created) {
        ffxFsr2ContextDestroy(&c->ctx);
    }
    delete c;
    return 0;
}

static FfxResource makeTex(FfxFsr2Context* ctx, uint64_t image, uint64_t view,
                           uint32_t w, uint32_t h, VkFormat fmt, FfxResourceStates state)
{
    return ffxGetTextureResourceVK(ctx, (VkImage)image, (VkImageView)view, w, h, fmt,
                                   nullptr, state);
}

extern "C" int caustica_ffx_fsr2_dispatch(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t color_image, uint64_t color_view,
    uint64_t depth_image, uint64_t depth_view,
    uint64_t motion_image, uint64_t motion_view,
    uint64_t output_image, uint64_t output_view,
    uint32_t render_w, uint32_t render_h,
    float jitter_x, float jitter_y,
    float frame_time_delta_ms,
    float pre_exposure,
    float camera_near, float camera_far, float camera_fov_y,
    int reset)
{
    if (!ctx || !vk_command_buffer) return -1;
    auto* c = static_cast<CausticaFsr2*>(ctx);
    if (!c->created) return -2;

    FfxFsr2DispatchDescription d{};
    memset(&d, 0, sizeof(d));
    d.commandList = ffxGetCommandListVK((VkCommandBuffer)vk_command_buffer);
    // Caustica writes color/depth/MV via storage imageStore → layout GENERAL.
    // FFX_RESOURCE_STATE_COMPUTE_READ maps to SHADER_READ_ONLY_OPTIMAL and lies about the
    // current layout, so FSR's internal barriers become no-ops / wrong → history never locks
    // and the image stays SPP-1 grainy forever. UNORDERED_ACCESS / GENERIC_READ = GENERAL.
    // FSR2 classic shaders are authored for RGBA16F color/output. Caustica's beauty
    // plate is B10G11R11 — the Java upscaler converts to/from RGBA16F staging
    // images before calling this entry point. Motion is also rgba16f in Caustica
    // (xy used); claiming RG16F while the image is RGBA16F blacks out on RADV.
    d.color = makeTex(&c->ctx, color_image, color_view, render_w, render_h,
                      VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.depth = makeTex(&c->ctx, depth_image, depth_view, render_w, render_h,
                      VK_FORMAT_R32_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.motionVectors = makeTex(&c->ctx, motion_image, motion_view, render_w, render_h,
                              VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.output = makeTex(&c->ctx, output_image, output_view, c->displayW, c->displayH,
                       VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);

    d.jitterOffset.x = jitter_x;
    d.jitterOffset.y = jitter_y;
    // gMotion is render-pixel offset (prevPx - curPx). FSR2 does:
    //   constants.scale = params.motionVectorScale / renderSize
    //   uvMotion = pixelMV * constants.scale
    // so for pixel MVs params.scale must be 1 (sample uses renderSize only for UV MVs).
    d.motionVectorScale.x = 1.0f;
    d.motionVectorScale.y = 1.0f;
    d.renderSize = { render_w, render_h };
    // RCAS on by default — post-NRD plates are soft; PT without sharpen looks mushy at 1.5×.
    // pre_exposure packs optional sharpness: if |pre| > 1.5, interpret as 1+sharpness sentinel.
    // Normal path uses pre_exposure in (0,2]; sharpness from env default 0.55.
    d.enableSharpening = true;
    d.sharpness = 0.55f;
    if (pre_exposure > 2.0f && pre_exposure <= 3.0f) {
        // Caller packed sharpness as 2.0 + sharpness[0..1]
        d.sharpness = pre_exposure - 2.0f;
    } else if (pre_exposure < 0.0f) {
        d.enableSharpening = false;
        d.sharpness = 0.0f;
    }
    d.frameTimeDelta = frame_time_delta_ms > 0 ? frame_time_delta_ms : 16.6f;
    // Real exposure must stay ~1 for HDR PT; sharpen packing uses the >2 branch above.
    d.preExposure = (pre_exposure > 0.0f && pre_exposure <= 2.0f) ? pre_exposure : 1.0f;
    d.reset = reset != 0;
    d.cameraNear = camera_near > 0.0f ? camera_near : 0.05f;
    d.cameraFar = camera_far; // caller supplies a positive far sentinel with DEPTH_INFINITE
    // Guard absurd FOV (missing/identity matrix) — wrong FOV = depth clip thrash = camera shake.
    float fov = camera_fov_y;
    if (!(fov > 0.15f && fov < 2.5f)) {
        fov = 1.2217305f; // ~70 deg
    }
    d.cameraFovAngleVertical = fov;
    d.viewSpaceToMetersFactor = 1.0f;

    FfxErrorCode err = ffxFsr2ContextDispatch(&c->ctx, &d);
    return (int)err;
}

extern "C" float caustica_ffx_fsr2_get_upscale_ratio(int quality_mode) {
    return ffxFsr2GetUpscaleRatioFromQualityMode((FfxFsr2QualityMode)quality_mode);
}

extern "C" int caustica_ffx_fsr2_get_jitter_phase_count(int render_w, int display_w) {
    return ffxFsr2GetJitterPhaseCount(render_w, display_w);
}

extern "C" int caustica_ffx_fsr2_get_jitter_offset(float* out_x, float* out_y, int index, int phase_count) {
    return (int)ffxFsr2GetJitterOffset(out_x, out_y, index, phase_count);
}

// ---------------------------------------------------------------------------
// v2 dispatch: adds the reactive mask (R32F, render res). The Java upscaler
// feeds the self-derived motion+depth divergence signal (see
// shaders/display/denoise_ffx/fsr2_reactive_mask.comp — ported from
// iterationRP's DepthClip_CS.glsl:82-151 motion+depth divergence).
//
// reactive_image == 0 → reactive mask disabled (behaves like v1 dispatch).
// ---------------------------------------------------------------------------
extern "C" int caustica_ffx_fsr2_dispatch_v2(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t color_image, uint64_t color_view,
    uint64_t depth_image, uint64_t depth_view,
    uint64_t motion_image, uint64_t motion_view,
    uint64_t output_image, uint64_t output_view,
    uint64_t reactive_image, uint64_t reactive_view,
    uint32_t render_w, uint32_t render_h,
    float jitter_x, float jitter_y,
    float frame_time_delta_ms,
    float pre_exposure,
    float camera_near, float camera_far, float camera_fov_y,
    int reset)
{
    if (!ctx || !vk_command_buffer) return -1;
    auto* c = static_cast<CausticaFsr2*>(ctx);
    if (!c->created) return -2;

    FfxFsr2DispatchDescription d{};
    memset(&d, 0, sizeof(d));
    d.commandList = ffxGetCommandListVK((VkCommandBuffer)vk_command_buffer);
    d.color = makeTex(&c->ctx, color_image, color_view, render_w, render_h,
                      VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.depth = makeTex(&c->ctx, depth_image, depth_view, render_w, render_h,
                      VK_FORMAT_R32_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.motionVectors = makeTex(&c->ctx, motion_image, motion_view, render_w, render_h,
                              VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.output = makeTex(&c->ctx, output_image, output_view, c->displayW, c->displayH,
                       VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);

    if (reactive_image != 0 && reactive_view != 0) {
        d.reactive = makeTex(&c->ctx, reactive_image, reactive_view, render_w, render_h,
                             VK_FORMAT_R32_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    }

    d.jitterOffset.x = jitter_x;
    d.jitterOffset.y = jitter_y;
    d.motionVectorScale.x = 1.0f;
    d.motionVectorScale.y = 1.0f;
    d.renderSize = { render_w, render_h };
    d.enableSharpening = true;
    d.sharpness = 0.55f;
    if (pre_exposure > 2.0f && pre_exposure <= 3.0f) {
        d.sharpness = pre_exposure - 2.0f;
    } else if (pre_exposure < 0.0f) {
        d.enableSharpening = false;
        d.sharpness = 0.0f;
    }
    d.frameTimeDelta = frame_time_delta_ms > 0 ? frame_time_delta_ms : 16.6f;
    d.preExposure = (pre_exposure > 0.0f && pre_exposure <= 2.0f) ? pre_exposure : 1.0f;
    d.reset = reset != 0;
    d.cameraNear = camera_near > 0.0f ? camera_near : 0.05f;
    d.cameraFar = camera_far;
    float fov = camera_fov_y;
    if (!(fov > 0.15f && fov < 2.5f)) {
        fov = 1.2217305f;
    }
    d.cameraFovAngleVertical = fov;
    d.viewSpaceToMetersFactor = 1.0f;

    FfxErrorCode err = ffxFsr2ContextDispatch(&c->ctx, &d);
    return (int)err;
}
