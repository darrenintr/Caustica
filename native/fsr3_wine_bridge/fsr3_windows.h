/*
 * FSR3 Windows In-Process Integration
 * Direct Vulkan <-> D3D12 interop without cross-process communication
 */

#ifndef FSR3_WINDOWS_H
#define FSR3_WINDOWS_H

#include <vulkan/vulkan.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include "ffx_api.h"

#ifdef __cplusplus
extern "C" {
#endif

// FSR3 Windows handle
typedef void* FSR3WindowsHandle;

// Configuration
typedef struct FSR3WindowsConfig {
    VkInstance vkInstance;
    VkPhysicalDevice vkPhysicalDevice;
    VkDevice vkDevice;
    uint32_t vkQueueFamilyIndex;
    VkQueue vkQueue;
} FSR3WindowsConfig;

// Context creation parameters
typedef struct FSR3ContextParams {
    uint32_t renderWidth;
    uint32_t renderHeight;
    uint32_t displayWidth;
    uint32_t displayHeight;
    uint32_t qualityMode;  // ffxFsr3QualityMode
    uint64_t flags;
} FSR3ContextParams;

// Dispatch parameters
typedef struct FSR3DispatchParams {
    VkImage colorImage;
    VkImage depthImage;
    VkImage motionImage;
    VkImage outputImage;
    uint32_t renderWidth;
    uint32_t renderHeight;
    float jitterX;
    float jitterY;
    float frameTimeDelta;
    float sharpness;
    float cameraNear;
    float cameraFar;
    float cameraFovVertical;
    int32_t reset;
} FSR3DispatchParams;

// API Functions

/**
 * Create FSR3 Windows integration handle
 * @param config Vulkan device configuration
 * @return Handle to FSR3 integration, or NULL on failure
 */
FSR3WindowsHandle fsr3_windows_create(const FSR3WindowsConfig* config);

/**
 * Create FSR3 context
 * @param handle FSR3 Windows handle
 * @param params Context parameters
 * @return 0 on success, negative on error
 */
int fsr3_windows_create_context(FSR3WindowsHandle handle, const FSR3ContextParams* params);

/**
 * Dispatch FSR3 upscaling
 * @param handle FSR3 Windows handle
 * @param params Dispatch parameters
 * @return 0 on success, negative on error
 */
int fsr3_windows_dispatch(FSR3WindowsHandle handle, const FSR3DispatchParams* params);

/**
 * Destroy FSR3 context and handle
 * @param handle FSR3 Windows handle
 */
void fsr3_windows_destroy(FSR3WindowsHandle handle);

/**
 * Get last error message
 * @param handle FSR3 Windows handle
 * @return Error message string, or NULL if no error
 */
const char* fsr3_windows_get_error(FSR3WindowsHandle handle);

#ifdef __cplusplus
}
#endif

#endif // FSR3_WINDOWS_H
