/* Caustica thin C ABI over official FidelityFX Denoiser 1.2 (Vulkan).
 * Java FFM binds these symbols only — no C++ ABI leaks. */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Packed version: major*10000 + minor*100 + patch (e.g. 10200 for 1.2.0). */
int caustica_ffx_denoiser_probe(void);

/**
 * Create a denoiser context.
 * @param vk_device        VkDevice as uint64_t
 * @param vk_physical      VkPhysicalDevice as uint64_t
 * @param flags            FfxDenoiserInitializationFlagBits (SHADOWS|REFLECTIONS|DEPTH_INVERTED)
 * @param width            render width
 * @param height           render height
 * @param normals_format   FfxSurfaceFormat enum value for normals history
 * @param out_ctx          opaque context pointer (heap-allocated; destroy with destroy)
 * @return FFX_OK (0) on success
 */
int caustica_ffx_denoiser_create(
    uint64_t vk_device,
    uint64_t vk_physical,
    uint32_t flags,
    uint32_t width,
    uint32_t height,
    uint32_t normals_format,
    void** out_ctx);

int caustica_ffx_denoiser_destroy(void* ctx);

/**
 * Dispatch shadow denoise (full SDK only). Returns non-zero when unavailable / failed.
 * Opaque resource handles are VkImage/VkImageView pairs packed by the Java side later;
 * for now this is a stub that returns -100 (not implemented) unless CAUSTICA_FFX_DENOISER_FULL.
 */
int caustica_ffx_denoiser_dispatch_shadows(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t hit_mask_view,
    uint64_t depth_view,
    uint64_t velocity_view,
    uint64_t normal_view,
    uint64_t shadow_out_view,
    uint32_t width,
    uint32_t height,
    float motion_scale_x,
    float motion_scale_y,
    uint32_t frame_index);

/** Stub until CAUSTICA_FFX_DENOISER_FULL; SPIR-V reflection path is used in-engine. */
int caustica_ffx_denoiser_dispatch_reflections(
    void* ctx,
    uint64_t vk_command_buffer,
    uint32_t width,
    uint32_t height,
    uint32_t frame_index);

#ifdef __cplusplus
}
#endif
