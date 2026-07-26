/* Caustica C ABI for NVIDIA NRD REBLUR (Vulkan). */
#pragma once
#include <stdint.h>
#if defined(_WIN32)
#define CAUSTICA_NRD_API __declspec(dllexport)
#else
#define CAUSTICA_NRD_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif

/** Java/native ABI implemented by this shim. */
#define CAUSTICA_NRD_ABI_VERSION 2

/** Packed NRD version major*10000+minor*100+build, or 0 if unavailable. */
CAUSTICA_NRD_API int caustica_nrd_probe(void);
CAUSTICA_NRD_API int caustica_nrd_abi_version(void);
CAUSTICA_NRD_API int caustica_nrd_normal_encoding(void);
CAUSTICA_NRD_API int caustica_nrd_roughness_encoding(void);

/**
 * Create a REBLUR_DIFFUSE_SPECULAR + SIGMA_SHADOW context.
 * @param vk_device VkDevice
 * @param vk_physical VkPhysicalDevice
 * @param get_device_proc_addr vkGetDeviceProcAddr
 * @param width,height render resolution
 * @param out_ctx opaque handle
 * @return 0 on success
 */
CAUSTICA_NRD_API int caustica_nrd_create(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint64_t get_device_proc_addr,
    uint32_t width,
    uint32_t height,
    void** out_ctx);

/** ABI v2: initializes on the first application command buffer and supports two queue families. */
CAUSTICA_NRD_API int caustica_nrd_create_v2(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint64_t get_device_proc_addr,
    uint32_t width,
    uint32_t height,
    uint32_t graphics_queue_family,
    uint32_t compute_queue_family,
    void** out_ctx);

CAUSTICA_NRD_API int caustica_nrd_destroy(void* ctx);

CAUSTICA_NRD_API int caustica_nrd_resize(void* ctx, uint32_t width, uint32_t height);

/**
 * Update REBLUR's {@code maxAccumulatedFrameNum} at runtime. Borrowed from
 * Sundial-Lite's VB_MAX_BLEDED_FRAMES — exposed here so users on iGPU / Apple Silicon /
 * RDNA2 can trade temporal stability for memory + latency via caustica.toml.
 * Internally calls {@code nrd::SetDenoiserSettings} on the REBLUR_DIFFUSE_SPECULAR
 * identifier, which is safe to call mid-frame (NRD re-reads settings each dispatch).
 * @return 0 on success, non-zero on failure
 */
CAUSTICA_NRD_API int caustica_nrd_set_max_accumulated_frame_num(void* ctx, uint32_t frame_num);

/** RELAX equivalent — sets both diffuse and specular MaxAccumulatedFrameNum. */
CAUSTICA_NRD_API int caustica_nrd_set_relax_max_accumulated_frame_num(void* ctx, uint32_t frame_num);

/**
 * Dispatch one REBLUR_DIFFUSE_SPECULAR + SIGMA_SHADOW frame.
 * Images are VkImage; views are VkImageView. All GENERAL layout.
 * Matrices: 16 floats column-major (same as JOML / glm value_ptr).
 *  viewToClip / viewToClipPrev / worldToView / worldToViewPrev
 * Motion vectors: pixel-space (prev-cur); scale applied inside as 1/w,1/h.
 * @return 0 on success
 */
CAUSTICA_NRD_API int caustica_nrd_dispatch_v2(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t in_diff_image, uint64_t in_diff_view,
    uint64_t in_spec_image, uint64_t in_spec_view,
    uint64_t in_mv_image, uint64_t in_mv_view,
    uint64_t in_normal_image, uint64_t in_normal_view,
    uint64_t in_viewz_image, uint64_t in_viewz_view,
    uint64_t in_shadow_image, uint64_t in_shadow_view,
    uint64_t in_diff_conf_image, uint64_t in_diff_conf_view,
    uint64_t in_spec_conf_image, uint64_t in_spec_conf_view,
    uint64_t in_disocclusion_image, uint64_t in_disocclusion_view,
    uint64_t out_diff_image, uint64_t out_diff_view,
    uint64_t out_spec_image, uint64_t out_spec_view,
    uint64_t out_shadow_image, uint64_t out_shadow_view,
    const float* view_to_clip,      /* 16 */
    const float* view_to_clip_prev, /* 16 */
    const float* world_to_view,     /* 16 */
    const float* world_to_view_prev,/* 16 */
    float jitter_x, float jitter_y,
    float jitter_x_prev, float jitter_y_prev,
    float light_dir_x, float light_dir_y, float light_dir_z,
    uint32_t frame_index,
    int reset);

/**
 * RELAX variant — uses NRD RELAX_DIFFUSE_SPECULAR (attention-based, newer than REBLUR)
 * instead of REBLUR. Same input layout. ABI v3.
 *
 * Cost on RX 7600 @ 1080p: ~7-9 ms (slightly more than REBLUR's ~6-8 ms, but ~15-20%
 * better quality on fine geometry and material boundaries).
 */
CAUSTICA_NRD_API int caustica_nrd_create_relax_v2(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint64_t get_device_proc_addr,
    uint32_t width,
    uint32_t height,
    uint32_t graphics_queue_family,
    uint32_t compute_queue_family,
    void** out_ctx);

CAUSTICA_NRD_API int caustica_nrd_dispatch_relax_v2(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t in_diff_image, uint64_t in_diff_view,
    uint64_t in_spec_image, uint64_t in_spec_view,
    uint64_t in_mv_image, uint64_t in_mv_view,
    uint64_t in_normal_image, uint64_t in_normal_view,
    uint64_t in_viewz_image, uint64_t in_viewz_view,
    uint64_t in_shadow_image, uint64_t in_shadow_view,
    uint64_t in_diff_conf_image, uint64_t in_diff_conf_view,
    uint64_t in_spec_conf_image, uint64_t in_spec_conf_view,
    uint64_t in_disocclusion_image, uint64_t in_disocclusion_view,
    uint64_t out_diff_image, uint64_t out_diff_view,
    uint64_t out_spec_image, uint64_t out_spec_view,
    uint64_t out_shadow_image, uint64_t out_shadow_view,
    const float* view_to_clip,
    const float* view_to_clip_prev,
    const float* world_to_view,
    const float* world_to_view_prev,
    float jitter_x, float jitter_y,
    float jitter_x_prev, float jitter_y_prev,
    float light_dir_x, float light_dir_y, float light_dir_z,
    uint32_t frame_index,
    int reset);

#ifdef __cplusplus
}
#endif
