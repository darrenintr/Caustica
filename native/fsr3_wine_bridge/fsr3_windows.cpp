/*
 * FSR3 Windows In-Process Integration - Implementation
 * Vulkan <-> D3D12 interop for FSR3 on Windows
 */

#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_win32.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <map>

#include "ffx_api.h"
#include "fsr3_windows.h"

// Vulkan external memory function pointers (will be loaded dynamically)
static PFN_vkGetMemoryWin32HandleKHR pfn_vkGetMemoryWin32HandleKHR = nullptr;
static PFN_vkGetSemaphoreWin32HandleKHR pfn_vkGetSemaphoreWin32HandleKHR = nullptr;

// Internal structure
struct FSR3WindowsContext {
    // Vulkan
    VkInstance vkInstance;
    VkPhysicalDevice vkPhysicalDevice;
    VkDevice vkDevice;
    uint32_t vkQueueFamilyIndex;
    VkQueue vkQueue;

    // D3D12
    ID3D12Device* d3d12Device;
    ID3D12CommandQueue* d3d12Queue;
    ID3D12CommandAllocator* d3d12Allocator;
    ID3D12GraphicsCommandList* d3d12CommandList;
    IDXGIFactory4* dxgiFactory;

    // FSR3
    HMODULE fsrDll;
    PFN_ffxConfigure ffxConfigure;
    PFN_ffxCreateContext ffxCreateContext;
    PFN_ffxDestroyContext ffxDestroyContext;
    PFN_ffxDispatch ffxDispatch;
    ffxContext fsr3Context;

    // Shared resources cache
    struct SharedResource {
        VkImage vkImage;
        ID3D12Resource* d3d12Resource;
        HANDLE sharedHandle;
    };
    std::map<VkImage, SharedResource> sharedResources;

    // Error handling
    char errorMsg[512];

    FSR3WindowsContext() {
        memset(this, 0, sizeof(FSR3WindowsContext));
    }
};

// Helper: Load Vulkan function pointers
static bool LoadVulkanFunctions(VkInstance instance) {
    pfn_vkGetMemoryWin32HandleKHR = (PFN_vkGetMemoryWin32HandleKHR)
        vkGetInstanceProcAddr(instance, "vkGetMemoryWin32HandleKHR");
    pfn_vkGetSemaphoreWin32HandleKHR = (PFN_vkGetSemaphoreWin32HandleKHR)
        vkGetInstanceProcAddr(instance, "vkGetSemaphoreWin32HandleKHR");

    return pfn_vkGetMemoryWin32HandleKHR && pfn_vkGetSemaphoreWin32HandleKHR;
}

// Helper: Initialize D3D12 device
static bool InitD3D12(FSR3WindowsContext* ctx) {
    HRESULT hr = CreateDXGIFactory2(0, IID_PPV_ARGS(&ctx->dxgiFactory));
    if (FAILED(hr)) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "CreateDXGIFactory2 failed: 0x%08X", hr);
        return false;
    }

    // Enumerate adapters
    IDXGIAdapter1* adapter = nullptr;
    for (UINT i = 0; ; ++i) {
        hr = ctx->dxgiFactory->EnumAdapters1(i, &adapter);
        if (hr == DXGI_ERROR_NOT_FOUND) break;
        if (FAILED(hr)) continue;

        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);

        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) {
            adapter->Release();
            continue;
        }

        // Try Feature Level 12_0 first
        hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&ctx->d3d12Device));
        if (SUCCEEDED(hr)) {
            adapter->Release();
            break;
        }

        // Fallback to 11_0
        hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&ctx->d3d12Device));
        if (SUCCEEDED(hr)) {
            adapter->Release();
            break;
        }

        adapter->Release();
    }

    if (!ctx->d3d12Device) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg), "No suitable D3D12 device found");
        return false;
    }

    // Create command queue
    D3D12_COMMAND_QUEUE_DESC queueDesc = {};
    queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    hr = ctx->d3d12Device->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&ctx->d3d12Queue));
    if (FAILED(hr)) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "CreateCommandQueue failed: 0x%08X", hr);
        return false;
    }

    // Create command allocator
    hr = ctx->d3d12Device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                                                   IID_PPV_ARGS(&ctx->d3d12Allocator));
    if (FAILED(hr)) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "CreateCommandAllocator failed: 0x%08X", hr);
        return false;
    }

    // Create command list
    hr = ctx->d3d12Device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT,
                                              ctx->d3d12Allocator, nullptr,
                                              IID_PPV_ARGS(&ctx->d3d12CommandList));
    if (FAILED(hr)) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "CreateCommandList failed: 0x%08X", hr);
        return false;
    }

    return true;
}

// Helper: Load FSR3 DLL
static bool LoadFSR3(FSR3WindowsContext* ctx) {
    const char* dllPaths[] = {
        "amd_fidelityfx_upscaler_dx12.dll",
        "C:\\Windows\\System32\\amd_fidelityfx_upscaler_dx12.dll",
        nullptr
    };

    for (int i = 0; dllPaths[i]; i++) {
        ctx->fsrDll = LoadLibraryA(dllPaths[i]);
        if (ctx->fsrDll) break;
    }

    if (!ctx->fsrDll) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg), "Failed to load FSR3 DLL");
        return false;
    }

    ctx->ffxConfigure = (PFN_ffxConfigure)GetProcAddress(ctx->fsrDll, "ffxConfigure");
    ctx->ffxCreateContext = (PFN_ffxCreateContext)GetProcAddress(ctx->fsrDll, "ffxCreateContext");
    ctx->ffxDestroyContext = (PFN_ffxDestroyContext)GetProcAddress(ctx->fsrDll, "ffxDestroyContext");
    ctx->ffxDispatch = (PFN_ffxDispatch)GetProcAddress(ctx->fsrDll, "ffxDispatch");

    if (!ctx->ffxConfigure || !ctx->ffxCreateContext || !ctx->ffxDestroyContext || !ctx->ffxDispatch) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg), "Failed to load FFX functions");
        return false;
    }

    // Configure FFX
    ffxConfigureDescription configDesc = {};
    configDesc.apiVersion = FFX_API_VERSION_1_1;
    configDesc.device = ctx->d3d12Device;

    ffxReturnCode_t result = ctx->ffxConfigure(&configDesc);
    if (result != FFX_OK) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "ffxConfigure failed: %d", result);
        return false;
    }

    return true;
}

// Helper: Import Vulkan image to D3D12
static ID3D12Resource* ImportVulkanImage(FSR3WindowsContext* ctx, VkImage image,
                                         uint32_t width, uint32_t height, DXGI_FORMAT format) {
    // Check cache
    auto it = ctx->sharedResources.find(image);
    if (it != ctx->sharedResources.end()) {
        return it->second.d3d12Resource;
    }

    // TODO: Get Vulkan image memory and export as Windows HANDLE
    // This requires:
    // 1. vkGetImageMemoryRequirements
    // 2. vkGetMemoryWin32HandleKHR
    // 3. d3d12Device->OpenSharedHandle
    // 4. d3d12Device->CreatePlacedResource

    // For now, create a dummy D3D12 resource
    D3D12_HEAP_PROPERTIES heapProps = {};
    heapProps.Type = D3D12_HEAP_TYPE_DEFAULT;

    D3D12_RESOURCE_DESC resourceDesc = {};
    resourceDesc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    resourceDesc.Width = width;
    resourceDesc.Height = height;
    resourceDesc.DepthOrArraySize = 1;
    resourceDesc.MipLevels = 1;
    resourceDesc.Format = format;
    resourceDesc.SampleDesc.Count = 1;
    resourceDesc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;

    ID3D12Resource* resource = nullptr;
    HRESULT hr = ctx->d3d12Device->CreateCommittedResource(
        &heapProps, D3D12_HEAP_FLAG_NONE, &resourceDesc,
        D3D12_RESOURCE_STATE_COMMON, nullptr, IID_PPV_ARGS(&resource));

    if (FAILED(hr)) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "CreateCommittedResource failed: 0x%08X", hr);
        return nullptr;
    }

    // Cache the resource
    FSR3WindowsContext::SharedResource shared = {};
    shared.vkImage = image;
    shared.d3d12Resource = resource;
    shared.sharedHandle = nullptr;
    ctx->sharedResources[image] = shared;

    return resource;
}

// API Implementation

FSR3WindowsHandle fsr3_windows_create(const FSR3WindowsConfig* config) {
    if (!config || !config->vkInstance || !config->vkDevice) {
        return nullptr;
    }

    auto* ctx = new FSR3WindowsContext();
    ctx->vkInstance = config->vkInstance;
    ctx->vkPhysicalDevice = config->vkPhysicalDevice;
    ctx->vkDevice = config->vkDevice;
    ctx->vkQueueFamilyIndex = config->vkQueueFamilyIndex;
    ctx->vkQueue = config->vkQueue;

    if (!LoadVulkanFunctions(ctx->vkInstance)) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "Failed to load Vulkan external memory functions");
        delete ctx;
        return nullptr;
    }

    if (!InitD3D12(ctx)) {
        delete ctx;
        return nullptr;
    }

    if (!LoadFSR3(ctx)) {
        delete ctx;
        return nullptr;
    }

    return ctx;
}

int fsr3_windows_create_context(FSR3WindowsHandle handle, const FSR3ContextParams* params) {
    auto* ctx = static_cast<FSR3WindowsContext*>(handle);
    if (!ctx || !params) return -1;

    if (ctx->fsr3Context) {
        ctx->ffxDestroyContext(&ctx->fsr3Context, nullptr);
        ctx->fsr3Context = nullptr;
    }

    ffxCreateContextDescUpscale createDesc = {};
    createDesc.flags = params->flags;
    createDesc.maxRenderSize[0] = params->renderWidth;
    createDesc.maxRenderSize[1] = params->renderHeight;
    createDesc.displaySize[0] = params->displayWidth;
    createDesc.displaySize[1] = params->displayHeight;
    createDesc.device = ctx->d3d12Device;
    createDesc.callbacks = nullptr;

    ffxReturnCode_t result = ctx->ffxCreateContext(&ctx->fsr3Context, &createDesc, nullptr);
    if (result != FFX_OK) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "ffxCreateContext failed: %d", result);
        return -1;
    }

    return 0;
}

int fsr3_windows_dispatch(FSR3WindowsHandle handle, const FSR3DispatchParams* params) {
    auto* ctx = static_cast<FSR3WindowsContext*>(handle);
    if (!ctx || !params || !ctx->fsr3Context) return -1;

    // Import Vulkan images to D3D12
    auto* colorRes = ImportVulkanImage(ctx, params->colorImage, params->renderWidth,
                                       params->renderHeight, DXGI_FORMAT_R16G16B16A16_FLOAT);
    auto* depthRes = ImportVulkanImage(ctx, params->depthImage, params->renderWidth,
                                       params->renderHeight, DXGI_FORMAT_R32_FLOAT);
    auto* motionRes = ImportVulkanImage(ctx, params->motionImage, params->renderWidth,
                                        params->renderHeight, DXGI_FORMAT_R16G16_FLOAT);
    auto* outputRes = ImportVulkanImage(ctx, params->outputImage, params->renderWidth,
                                        params->renderHeight, DXGI_FORMAT_R16G16B16A16_FLOAT);

    if (!colorRes || !depthRes || !motionRes || !outputRes) {
        return -1;
    }

    // Prepare FFX resources
    ffxResource colorFFX = {};
    colorFFX.resource = colorRes;
    colorFFX.description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
    colorFFX.description.format = FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT;
    colorFFX.description.width = params->renderWidth;
    colorFFX.description.height = params->renderHeight;
    colorFFX.description.mipCount = 1;

    // Similar for depth, motion, output...

    ffxDispatchDescUpscale dispatchDesc = {};
    dispatchDesc.commandList = ctx->d3d12CommandList;
    dispatchDesc.color = colorFFX;
    // ... fill other fields
    dispatchDesc.jitterOffset[0] = params->jitterX;
    dispatchDesc.jitterOffset[1] = params->jitterY;
    dispatchDesc.renderSize[0] = params->renderWidth;
    dispatchDesc.renderSize[1] = params->renderHeight;
    dispatchDesc.sharpness = params->sharpness;
    dispatchDesc.frameTimeDelta = params->frameTimeDelta;

    // Reset command list
    ctx->d3d12Allocator->Reset();
    ctx->d3d12CommandList->Reset(ctx->d3d12Allocator, nullptr);

    // Dispatch FSR3
    ffxReturnCode_t result = ctx->ffxDispatch(ctx->fsr3Context, &dispatchDesc);
    if (result != FFX_OK) {
        snprintf(ctx->errorMsg, sizeof(ctx->errorMsg),
                 "ffxDispatch failed: %d", result);
        return -1;
    }

    // Execute
    ctx->d3d12CommandList->Close();
    ID3D12CommandList* cmdLists[] = { ctx->d3d12CommandList };
    ctx->d3d12Queue->ExecuteCommandLists(1, cmdLists);

    // TODO: Add synchronization with Vulkan

    return 0;
}

void fsr3_windows_destroy(FSR3WindowsHandle handle) {
    auto* ctx = static_cast<FSR3WindowsContext*>(handle);
    if (!ctx) return;

    // Destroy FSR3 context
    if (ctx->fsr3Context) {
        ctx->ffxDestroyContext(&ctx->fsr3Context, nullptr);
    }

    // Cleanup shared resources
    for (auto& pair : ctx->sharedResources) {
        if (pair.second.d3d12Resource) {
            pair.second.d3d12Resource->Release();
        }
        if (pair.second.sharedHandle) {
            CloseHandle(pair.second.sharedHandle);
        }
    }

    // Cleanup D3D12
    if (ctx->d3d12CommandList) ctx->d3d12CommandList->Release();
    if (ctx->d3d12Allocator) ctx->d3d12Allocator->Release();
    if (ctx->d3d12Queue) ctx->d3d12Queue->Release();
    if (ctx->d3d12Device) ctx->d3d12Device->Release();
    if (ctx->dxgiFactory) ctx->dxgiFactory->Release();

    // Cleanup FSR3 DLL
    if (ctx->fsrDll) FreeLibrary(ctx->fsrDll);

    delete ctx;
}

const char* fsr3_windows_get_error(FSR3WindowsHandle handle) {
    auto* ctx = static_cast<FSR3WindowsContext*>(handle);
    if (!ctx || ctx->errorMsg[0] == '\0') return nullptr;
    return ctx->errorMsg;
}
