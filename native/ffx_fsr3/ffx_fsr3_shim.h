// Caustica — Linux Vulkan FSR3 upscaler JNI shim
//
// Links against the vendored FFX SDK (third_party/FidelityFX-SDK-vendored)
// — specifically amd_fidelityfx_vk + ffx_backend_vk + ffx_fsr3upscaler.
// Exports a thin C ABI for the Java FFM bridge.
//
// Symbols:
//   caustica_ffx_fsr3_probe                  -> packed FFX version (e.g. 30400)
//   caustica_ffx_fsr3_query_upscale_ratio    -> quality mode -> per-axis ratio (1.5/1.7/2.0/3.0/1.0)
//   caustica_ffx_fsr3_query_jitter_phase_count
//   caustica_ffx_fsr3_query_jitter_offset
//   caustica_ffx_fsr3_create                 -> VkDevice+VkPhysicalDevice -> context*
//   caustica_ffx_fsr3_destroy
//   caustica_ffx_fsr3_dispatch               -> per-frame Vulkan work
//
// Create/Destroy take uint64_t handles (VkDevice, VkPhysicalDevice,
// VkCommandBuffer) and dispatch takes uint64_t VkImage + VkImageView
// addresses. The downstream AMD ffx-api Vulkan backend uses volk to
// resolve function pointers from the device — see the `customVk*
// helpers the AMD sources ship with. Java loads volk dynamically via
// the same `vkGetDeviceProcAddr` it gets from LWJGL.

#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int32_t caustica_ffx_fsr3_probe(void);
int32_t caustica_ffx_fsr3_query_upscale_ratio(int32_t quality_mode);
int32_t caustica_ffx_fsr3_query_jitter_phase_count(int32_t render_w, int32_t display_w);
int32_t caustica_ffx_fsr3_query_jitter_offset(int32_t index, int32_t phase_count,
                                              float* out_x, float* out_y);

int32_t caustica_ffx_fsr3_create(uint64_t vk_device,
                                uint64_t vk_physical,
                                uint32_t flags,
                                uint32_t max_render_w,
                                uint32_t max_render_h,
                                uint32_t upscale_w,
                                uint32_t upscale_h,
                                void** out_ctx);

int32_t caustica_ffx_fsr3_destroy(void* ctx);

int32_t caustica_ffx_fsr3_dispatch(void* ctx,
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
                                   uint32_t allow_tearing_check);

#ifdef __cplusplus
}
#endif
