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

#ifdef __cplusplus
}
#endif
