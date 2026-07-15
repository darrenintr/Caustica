/* Caustica C ABI for NVIDIA NRD REBLUR (Vulkan). */
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/** Packed version major*10000+minor*100+build, or 0 if unavailable. */
int caustica_nrd_probe(void);

/**
 * Create REBLUR_DIFFUSE_SPECULAR context.
 * @param vk_device VkDevice
 * @param vk_physical VkPhysicalDevice
 * @param get_device_proc_addr vkGetDeviceProcAddr
 * @param width,height render resolution
 * @param out_ctx opaque handle
 * @return 0 on success
 */
int caustica_nrd_create(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint64_t get_device_proc_addr,
    uint32_t width,
    uint32_t height,
    void** out_ctx);

int caustica_nrd_destroy(void* ctx);

int caustica_nrd_resize(void* ctx, uint32_t width, uint32_t height);

/**
 * Dispatch one REBLUR_DIFFUSE_SPECULAR frame.
 * Images are VkImage; views are VkImageView. All GENERAL layout.
 * Matrices: 16 floats column-major (same as JOML / glm value_ptr).
 *  viewToClip / viewToClipPrev / worldToView / worldToViewPrev
 * Motion vectors: pixel-space (prev-cur); scale applied inside as 1/w,1/h.
 * @return 0 on success
 */
int caustica_nrd_dispatch(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t in_diff_image, uint64_t in_diff_view,
    uint64_t in_spec_image, uint64_t in_spec_view,
    uint64_t in_mv_image, uint64_t in_mv_view,
    uint64_t in_normal_image, uint64_t in_normal_view,
    uint64_t in_viewz_image, uint64_t in_viewz_view,
    uint64_t out_diff_image, uint64_t out_diff_view,
    uint64_t out_spec_image, uint64_t out_spec_view,
    const float* view_to_clip,      /* 16 */
    const float* view_to_clip_prev, /* 16 */
    const float* world_to_view,     /* 16 */
    const float* world_to_view_prev,/* 16 */
    float jitter_x, float jitter_y,
    float jitter_x_prev, float jitter_y_prev,
    uint32_t frame_index,
    int reset);

#ifdef __cplusplus
}
#endif
