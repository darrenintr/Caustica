/* Caustica C ABI for classic FSR2 (Vulkan). */
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/** 2*10000+2*100+2 = 20202 for FSR2 2.2.2 */
int caustica_ffx_fsr2_probe(void);

/**
 * Create FSR2 context. Returns 0 on success.
 * flags: FfxFsr2InitializationFlagBits (DEPTH_INVERTED | HDR | …)
 */
int caustica_ffx_fsr2_create(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint32_t flags,
    uint32_t max_render_w,
    uint32_t max_render_h,
    uint32_t display_w,
    uint32_t display_h,
    void** out_ctx);

int caustica_ffx_fsr2_destroy(void* ctx);

/**
 * Dispatch one frame. Image views are VkImageView as uint64_t.
 * Returns 0 on success; -100 if FULL not linked.
 */
int caustica_ffx_fsr2_dispatch(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t color_view,
    uint64_t depth_view,
    uint64_t motion_view,
    uint64_t output_view,
    uint32_t render_w,
    uint32_t render_h,
    float jitter_x,
    float jitter_y,
    float frame_time_delta,
    float pre_exposure,
    float camera_near,
    float camera_far,
    float camera_fov_y,
    int reset);

#ifdef __cplusplus
}
#endif
