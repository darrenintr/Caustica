/* Caustica thin C ABI over the FSR 4.1 / FFX API modular loader.
 *
 * Java FFM binds these symbols only — no C++ ABI leaks. The shim is a
 * C-ABI wrapper around the FFX API declared in
 * third_party/FidelityFX-SDK (or the in-tree FSR 4.1 Linux port headers):
 *
 *   ffxConfigure        — global / context config
 *   ffxCreateContext    — create an FSR 4.1 upscale context
 *   ffxDispatch         — dispatch one upscale frame
 *   ffxQuery            — capability queries (jitter, ratios, …)
 *   ffxDestroyContext   — release the context
 *
 * Two build modes:
 *   - PROBE_ONLY (default): the probe symbol always works; create/destroy/
 *     dispatch return -100. Used while the FSR 4.1 Linux port's Vulkan
 *     backend is still being assembled.
 *   - FULL (CAUSTICA_FFX_FSR41_FULL=1): links against the FSR 4.1 Linux
 *     port's libffx_fsr41_linux.a and dispatches straight through.
 *
 * ABI: opaque VkImage / VkImageView handles as uint64_t. Color / output
 * are expected to be VK_FORMAT_R16G16B16A16_SFLOAT staging (the same
 * contract the FSR 2.2 shim uses, since Caustica's RtPlateBridge already
 * converts path-tracer HDR to RGBA16F on the way in). Depth is
 * VK_FORMAT_R32_SFLOAT, motion is VK_FORMAT_R16G16B16A16_SFLOAT (xy used).
 * The reactive mask is R32_SFLOAT when supplied.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Packed version: major*10000 + minor*100 + patch (e.g. 40101 for 4.1.1). */
int caustica_ffx_fsr41_probe(void);

/**
 * Quality mode → upscale ratio query (matches the FFX 4.1 quality enum).
 *   0 = NativeAA (1.0x)
 *   1 = Quality (1.5x)
 *   2 = Balanced (1.7x)
 *   3 = Performance (2.0x)
 *   4 = Ultra Performance (3.0x)
 * Returns 0 if the FSR 4.1 native isn't loaded.
 */
int caustica_ffx_fsr41_query_upscale_ratio(int quality_mode);

/** Tween-pattern jitter phase count + per-index offset. */
int caustica_ffx_fsr41_query_jitter_phase_count(int render_w, int display_w);
int caustica_ffx_fsr41_query_jitter_offset(int index, int phase_count,
                                           float* out_x, float* out_y);

/**
 * Create an FSR 4.1 context.
 * @param vk_device        VkDevice as uint64_t
 * @param vk_physical      VkPhysicalDevice as uint64_t
 * @param flags            FfxApiCreateContextUpscaleFlags (HDR | DEPTH_INVERTED | DEPTH_INFINITE | …)
 * @param max_render_w     max render width  (≥ 1)
 * @param max_render_h     max render height (≥ 1)
 * @param upscale_w        display width
 * @param upscale_h        display height
 * @param out_ctx          opaque context pointer (heap-allocated; destroy with caustica_ffx_fsr41_destroy)
 * @return 0 on success, -100 if not FULL.
 */
int caustica_ffx_fsr41_create(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint32_t flags,
    uint32_t max_render_w,
    uint32_t max_render_h,
    uint32_t upscale_w,
    uint32_t upscale_h,
    void** out_ctx);

int caustica_ffx_fsr41_destroy(void* ctx);

/**
 * Dispatch one upscale frame.
 * @param reactive_image/reactive_view 0 means "no reactive mask" — FSR 4.1 then
 *                                      falls back to its built-in auto-reactive heuristics.
 * Image view handles are VkImage / VkImageView as uint64_t.
 * @return 0 on success, -100 if not FULL or any SDK error is propagated.
 */
int caustica_ffx_fsr41_dispatch(
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
    int reset);

#ifdef __cplusplus
}
#endif
