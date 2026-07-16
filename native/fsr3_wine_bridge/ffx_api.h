/*
 * FidelityFX API declarations for FSR3
 * Based on AMD FidelityFX SDK
 */

#ifndef FFX_API_H
#define FFX_API_H

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// FFX result codes
typedef int32_t ffxReturnCode_t;
#define FFX_OK 0
#define FFX_ERROR_INVALID_POINTER -1
#define FFX_ERROR_INVALID_ARGUMENT -2
#define FFX_ERROR_OUT_OF_MEMORY -3
#define FFX_ERROR_INCOMPLETE_INTERFACE -4
#define FFX_ERROR_INVALID_ENUM -5

// FFX resource types
typedef enum ffxResourceType {
    FFX_RESOURCE_TYPE_BUFFER = 0,
    FFX_RESOURCE_TYPE_TEXTURE1D = 1,
    FFX_RESOURCE_TYPE_TEXTURE2D = 2,
    FFX_RESOURCE_TYPE_TEXTURE_CUBE = 3,
    FFX_RESOURCE_TYPE_TEXTURE3D = 4
} ffxResourceType;

// FFX surface format
typedef enum ffxSurfaceFormat {
    FFX_SURFACE_FORMAT_UNKNOWN = 0,
    FFX_SURFACE_FORMAT_R32G32B32A32_TYPELESS = 1,
    FFX_SURFACE_FORMAT_R32G32B32A32_FLOAT = 2,
    FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT = 10,
    FFX_SURFACE_FORMAT_R32G32_FLOAT = 16,
    FFX_SURFACE_FORMAT_R11G11B10_FLOAT = 26,
    FFX_SURFACE_FORMAT_R16G16_FLOAT = 34,
    FFX_SURFACE_FORMAT_R32_FLOAT = 41,
    FFX_SURFACE_FORMAT_R16_FLOAT = 54,
    FFX_SURFACE_FORMAT_R8_UNORM = 61
} ffxSurfaceFormat;

// FFX resource description
typedef struct ffxResourceDescription {
    ffxResourceType type;
    ffxSurfaceFormat format;
    uint32_t width;
    uint32_t height;
    uint32_t depth;
    uint32_t mipCount;
    uint32_t flags;
} ffxResourceDescription;

// FFX resource (handle to GPU resource)
typedef struct ffxResource {
    void* resource;  // D3D12 resource pointer
    ffxResourceDescription description;
    uint32_t state;
    uint64_t descriptorData;
} ffxResource;

// FFX context handle
typedef void* ffxContext;

// FFX API version
typedef enum ffxApiVersion {
    FFX_API_VERSION_1_0 = (1 << 16) | 0,
    FFX_API_VERSION_1_1 = (1 << 16) | 1
} ffxApiVersion;

// Configure parameters
typedef struct ffxConfigureDescription {
    ffxApiVersion apiVersion;
    uint64_t flags;
    void* device;  // ID3D12Device*
} ffxConfigureDescription;

// FSR3 Quality modes
typedef enum ffxFsr3QualityMode {
    FFX_FSR3_QUALITY_MODE_QUALITY = 1,
    FFX_FSR3_QUALITY_MODE_BALANCED = 2,
    FFX_FSR3_QUALITY_MODE_PERFORMANCE = 3,
    FFX_FSR3_QUALITY_MODE_ULTRA_PERFORMANCE = 4
} ffxFsr3QualityMode;

// FSR3 context creation flags
#define FFX_FSR3_ENABLE_HIGH_DYNAMIC_RANGE (1 << 0)
#define FFX_FSR3_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS (1 << 1)
#define FFX_FSR3_ENABLE_MOTION_VECTORS_JITTER_CANCELLATION (1 << 2)
#define FFX_FSR3_ENABLE_DEPTH_INVERTED (1 << 3)
#define FFX_FSR3_ENABLE_DEPTH_INFINITE (1 << 4)
#define FFX_FSR3_ENABLE_AUTO_EXPOSURE (1 << 5)
#define FFX_FSR3_ENABLE_DYNAMIC_RESOLUTION (1 << 6)

// FSR3 dispatch flags
#define FFX_FSR3_DISPATCH_DRAW_DEBUG_VIEW (1 << 0)

// FSR3 Create Context Description
typedef struct ffxCreateContextDescUpscale {
    uint64_t flags;
    uint32_t maxRenderSize[2];      // [width, height]
    uint32_t displaySize[2];        // [width, height]
    void* device;                   // ID3D12Device*
    void* callbacks;                // Backend interface callbacks (can be NULL for default)
} ffxCreateContextDescUpscale;

// FSR3 Dispatch Description
typedef struct ffxDispatchDescUpscale {
    void* commandList;              // ID3D12GraphicsCommandList*
    ffxResource color;              // Input color buffer
    ffxResource depth;              // Input depth buffer
    ffxResource motionVectors;      // Input motion vectors
    ffxResource exposure;           // Input exposure (optional)
    ffxResource reactive;           // Reactive mask (optional)
    ffxResource transparencyAndComposition;  // Transparency mask (optional)
    ffxResource output;             // Output upscaled buffer
    float jitterOffset[2];          // [x, y] subpixel jitter
    float motionVectorScale[2];     // Motion vector scale
    uint32_t renderSize[2];         // Current render resolution
    int32_t enableSharpening;
    float sharpness;                // 0.0 to 1.0
    float frameTimeDelta;           // Frame time in milliseconds
    float preExposure;              // Pre-exposure value
    int32_t reset;                  // Reset accumulation
    float cameraNear;               // Camera near plane
    float cameraFar;                // Camera far plane (FLT_MAX for infinite)
    float cameraFovAngleVertical;   // Vertical FOV in radians
} ffxDispatchDescUpscale;

// Function pointers (loaded from DLL)
typedef ffxReturnCode_t (*PFN_ffxConfigure)(const ffxConfigureDescription* desc);
typedef ffxReturnCode_t (*PFN_ffxCreateContext)(ffxContext* context, const ffxCreateContextDescUpscale* createInfo, void* scratchBuffer);
typedef ffxReturnCode_t (*PFN_ffxDestroyContext)(ffxContext* context, void* scratchBuffer);
typedef ffxReturnCode_t (*PFN_ffxQuery)(ffxContext context, void* queryInfo, void* queryResult);
typedef ffxReturnCode_t (*PFN_ffxDispatch)(ffxContext context, const ffxDispatchDescUpscale* dispatchInfo);

#ifdef __cplusplus
}
#endif

#endif // FFX_API_H
