/*
 * FSR3 Wine Bridge Server - TCP Socket Version
 * Uses TCP sockets for cross-process communication
 */

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include "ffx_api.h"

#pragma comment(lib, "d3d12.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "ws2_32.lib")

#define SOCKET_PATH "/tmp/fsr3_bridge.sock"
#define SERVER_PORT 19573  // Random high port for FSR3 bridge

// Command protocol
enum CommandType : uint32_t {
    CMD_INIT = 1,
    CMD_CREATE_CONTEXT = 2,
    CMD_DISPATCH = 3,
    CMD_DESTROY = 4,
    CMD_SHUTDOWN = 5
};

struct CommandHeader {
    CommandType type;
    uint32_t dataSize;
};

struct CreateContextData {
    uint32_t renderWidth;
    uint32_t renderHeight;
    uint32_t displayWidth;
    uint32_t displayHeight;
    uint32_t qualityMode;  // ffxFsr3QualityMode
    uint64_t flags;
};

struct DispatchData {
    uint64_t colorHandle;
    uint64_t depthHandle;
    uint64_t motionHandle;
    uint64_t outputHandle;
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
};

struct Response {
    int32_t result;
    char message[256];
};

// Global DX12 state
ID3D12Device* g_device = nullptr;
ID3D12CommandQueue* g_commandQueue = nullptr;
IDXGIFactory4* g_factory = nullptr;
HMODULE g_fsrDll = nullptr;
ID3D12CommandAllocator* g_commandAllocator = nullptr;
ID3D12GraphicsCommandList* g_commandList = nullptr;

// FFX function pointers
PFN_ffxConfigure ffxConfigure = nullptr;
PFN_ffxCreateContext ffxCreateContext = nullptr;
PFN_ffxDestroyContext ffxDestroyContext = nullptr;
PFN_ffxDispatch ffxDispatch = nullptr;
PFN_ffxQuery ffxQuery = nullptr;

// FSR3 context
ffxContext g_fsr3Context = nullptr;

// D3D12 resource cache for imported Vulkan textures
struct ImportedResource {
    ID3D12Resource* resource;
    uint64_t vulkanHandle;
};
ImportedResource g_colorResource = {nullptr, 0};
ImportedResource g_depthResource = {nullptr, 0};
ImportedResource g_motionResource = {nullptr, 0};
ImportedResource g_outputResource = {nullptr, 0};

bool InitD3D12() {
    HRESULT hr;

    hr = CreateDXGIFactory2(0, IID_PPV_ARGS(&g_factory));
    if (FAILED(hr)) {
        printf("CreateDXGIFactory2 failed: 0x%08X\n", hr);
        return false;
    }
    printf("DXGI Factory created\n");

    IDXGIAdapter1* adapter = nullptr;
    bool foundAdapter = false;

    for (UINT i = 0; ; ++i) {
        hr = g_factory->EnumAdapters1(i, &adapter);
        if (hr == DXGI_ERROR_NOT_FOUND) break;
        if (FAILED(hr)) continue;

        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);
        wprintf(L"Adapter %d: %s\n", i, desc.Description);

        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) {
            adapter->Release();
            continue;
        }

        hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&g_device));
        if (SUCCEEDED(hr)) {
            wprintf(L"✓ Using adapter: %s (Feature Level 12_0)\n", desc.Description);
            foundAdapter = true;
            adapter->Release();
            break;
        }

        // Wine typically only supports Feature Level 11_0
        hr = D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&g_device));
        if (SUCCEEDED(hr)) {
            wprintf(L"✓ Using adapter: %s (Feature Level 11_0)\n", desc.Description);
            foundAdapter = true;
            adapter->Release();
            break;
        }

        printf("  Failed to create device on this adapter\n");
        adapter->Release();
    }

    if (!foundAdapter) {
        printf("No hardware adapter, trying WARP (software fallback)...\n");
        hr = D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&g_device));
        if (FAILED(hr)) {
            printf("WARP device creation failed: 0x%08X\n", hr);
            return false;
        }
        printf("⚠ Using WARP software rasterizer\n");
    }

    D3D12_COMMAND_QUEUE_DESC queueDesc = {};
    queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    queueDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;

    hr = g_device->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&g_commandQueue));
    if (FAILED(hr)) {
        printf("CreateCommandQueue failed: 0x%08X\n", hr);
        return false;
    }

    // Create command allocator and list for FSR3 dispatch
    hr = g_device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&g_commandAllocator));
    if (FAILED(hr)) {
        printf("CreateCommandAllocator failed: 0x%08X\n", hr);
        return false;
    }

    hr = g_device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, g_commandAllocator, nullptr, IID_PPV_ARGS(&g_commandList));
    if (FAILED(hr)) {
        printf("CreateCommandList failed: 0x%08X\n", hr);
        return false;
    }

    printf("D3D12 device initialized successfully\n");
    return true;
}

bool LoadFSR3() {
    const char* dllPaths[] = {
        "amd_fidelityfx_upscaler_dx12.dll",
        "C:\\windows\\system32\\amd_fidelityfx_upscaler_dx12.dll",
        NULL
    };

    for (int i = 0; dllPaths[i] != NULL; i++) {
        g_fsrDll = LoadLibraryA(dllPaths[i]);
        if (g_fsrDll) {
            printf("Loaded FSR3 DLL from: %s\n", dllPaths[i]);
            break;
        }
    }

    if (!g_fsrDll) {
        printf("WARNING: Could not load FSR3 DLL\n");
        return false;
    }

    // Load function pointers
    ffxConfigure = (PFN_ffxConfigure)GetProcAddress(g_fsrDll, "ffxConfigure");
    ffxCreateContext = (PFN_ffxCreateContext)GetProcAddress(g_fsrDll, "ffxCreateContext");
    ffxDestroyContext = (PFN_ffxDestroyContext)GetProcAddress(g_fsrDll, "ffxDestroyContext");
    ffxDispatch = (PFN_ffxDispatch)GetProcAddress(g_fsrDll, "ffxDispatch");
    ffxQuery = (PFN_ffxQuery)GetProcAddress(g_fsrDll, "ffxQuery");

    if (!ffxConfigure || !ffxCreateContext || !ffxDestroyContext || !ffxDispatch) {
        printf("ERROR: Failed to load FFX functions\n");
        return false;
    }

    printf("FFX functions loaded successfully\n");

    // Configure FFX with D3D12 device
    ffxConfigureDescription configDesc = {};
    configDesc.apiVersion = FFX_API_VERSION_1_1;
    configDesc.device = g_device;

    ffxReturnCode_t result = ffxConfigure(&configDesc);
    if (result != FFX_OK) {
        printf("ERROR: ffxConfigure failed: %d\n", result);
        return false;
    }

    printf("FFX configured successfully\n");
    return true;
}

void HandleCreateContext(SOCKET clientSock, const CreateContextData* data) {
    printf("CREATE_CONTEXT: %dx%d -> %dx%d, quality=%d, flags=0x%llx\n",
        data->renderWidth, data->renderHeight,
        data->displayWidth, data->displayHeight,
        data->qualityMode, data->flags);

    Response resp = {};

    if (!ffxCreateContext) {
        resp.result = -1;
        strcpy(resp.message, "FFX not initialized");
        send(clientSock, (char*)&resp, sizeof(resp), 0);
        return;
    }

    if (g_fsr3Context != nullptr) {
        printf("FSR3 context already exists, destroying old one\n");
        ffxDestroyContext(&g_fsr3Context, nullptr);
        g_fsr3Context = nullptr;
    }

    // Create FSR3 context with proper parameters
    ffxCreateContextDescUpscale createDesc = {};
    createDesc.flags = data->flags;
    createDesc.maxRenderSize[0] = data->renderWidth;
    createDesc.maxRenderSize[1] = data->renderHeight;
    createDesc.displaySize[0] = data->displayWidth;
    createDesc.displaySize[1] = data->displayHeight;
    createDesc.device = g_device;
    createDesc.callbacks = nullptr;  // Use default backend callbacks

    printf("Calling ffxCreateContext...\n");
    ffxReturnCode_t result = ffxCreateContext(&g_fsr3Context, &createDesc, nullptr);

    if (result != FFX_OK) {
        printf("ERROR: ffxCreateContext failed: %d\n", result);
        resp.result = result;
        snprintf(resp.message, sizeof(resp.message), "ffxCreateContext failed: %d", result);
    } else {
        printf("FSR3 context created successfully!\n");
        resp.result = 0;
        snprintf(resp.message, sizeof(resp.message),
                 "FSR3 context ready (%dx%d -> %dx%d)",
                 data->renderWidth, data->renderHeight,
                 data->displayWidth, data->displayHeight);
    }

    send(clientSock, (char*)&resp, sizeof(resp), 0);
}

ID3D12Resource* ImportVulkanResource(uint64_t vulkanHandle, uint32_t width, uint32_t height, DXGI_FORMAT format) {
    if (vulkanHandle == 0) {
        printf("WARNING: Null Vulkan handle, creating dummy resource\n");

        // Create a dummy D3D12 resource for testing
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
        resourceDesc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
        resourceDesc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;

        ID3D12Resource* resource = nullptr;
        HRESULT hr = g_device->CreateCommittedResource(
            &heapProps,
            D3D12_HEAP_FLAG_NONE,
            &resourceDesc,
            D3D12_RESOURCE_STATE_COMMON,
            nullptr,
            IID_PPV_ARGS(&resource));

        if (FAILED(hr)) {
            printf("ERROR: Failed to create dummy resource: 0x%08X\n", hr);
            return nullptr;
        }

        return resource;
    }

    // TODO: Implement real external memory import
    // For Wine interop, we would use:
    // 1. Receive file descriptor from Linux side
    // 2. Convert fd to Windows HANDLE (Wine specific)
    // 3. Use ID3D12Device::OpenSharedHandle to import

    printf("TODO: Real Vulkan->D3D12 import not yet implemented\n");
    return nullptr;
}

void HandleDispatch(SOCKET clientSock, const DispatchData* data) {
    printf("DISPATCH: %dx%d, jitter=(%.3f, %.3f), sharpness=%.2f\n",
        data->renderWidth, data->renderHeight,
        data->jitterX, data->jitterY, data->sharpness);

    Response resp = {};

    if (!g_fsr3Context || !ffxDispatch) {
        resp.result = -1;
        strcpy(resp.message, "FSR3 context not created");
        send(clientSock, (char*)&resp, sizeof(resp), 0);
        return;
    }

    // Import or retrieve D3D12 resources
    if (g_colorResource.vulkanHandle != data->colorHandle) {
        if (g_colorResource.resource) g_colorResource.resource->Release();
        g_colorResource.resource = ImportVulkanResource(data->colorHandle, data->renderWidth, data->renderHeight, DXGI_FORMAT_R16G16B16A16_FLOAT);
        g_colorResource.vulkanHandle = data->colorHandle;
    }

    if (g_depthResource.vulkanHandle != data->depthHandle) {
        if (g_depthResource.resource) g_depthResource.resource->Release();
        g_depthResource.resource = ImportVulkanResource(data->depthHandle, data->renderWidth, data->renderHeight, DXGI_FORMAT_R32_FLOAT);
        g_depthResource.vulkanHandle = data->depthHandle;
    }

    if (g_motionResource.vulkanHandle != data->motionHandle) {
        if (g_motionResource.resource) g_motionResource.resource->Release();
        g_motionResource.resource = ImportVulkanResource(data->motionHandle, data->renderWidth, data->renderHeight, DXGI_FORMAT_R16G16_FLOAT);
        g_motionResource.vulkanHandle = data->motionHandle;
    }

    if (g_outputResource.vulkanHandle != data->outputHandle) {
        if (g_outputResource.resource) g_outputResource.resource->Release();
        g_outputResource.resource = ImportVulkanResource(data->outputHandle, data->renderWidth, data->renderHeight, DXGI_FORMAT_R16G16B16A16_FLOAT);
        g_outputResource.vulkanHandle = data->outputHandle;
    }

    // Prepare FFX resources
    ffxResource colorRes = {};
    colorRes.resource = g_colorResource.resource;
    colorRes.description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
    colorRes.description.format = FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT;
    colorRes.description.width = data->renderWidth;
    colorRes.description.height = data->renderHeight;
    colorRes.description.depth = 1;
    colorRes.description.mipCount = 1;

    ffxResource depthRes = {};
    depthRes.resource = g_depthResource.resource;
    depthRes.description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
    depthRes.description.format = FFX_SURFACE_FORMAT_R32_FLOAT;
    depthRes.description.width = data->renderWidth;
    depthRes.description.height = data->renderHeight;
    depthRes.description.depth = 1;
    depthRes.description.mipCount = 1;

    ffxResource motionRes = {};
    motionRes.resource = g_motionResource.resource;
    motionRes.description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
    motionRes.description.format = FFX_SURFACE_FORMAT_R16G16_FLOAT;
    motionRes.description.width = data->renderWidth;
    motionRes.description.height = data->renderHeight;
    motionRes.description.depth = 1;
    motionRes.description.mipCount = 1;

    ffxResource outputRes = {};
    outputRes.resource = g_outputResource.resource;
    outputRes.description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
    outputRes.description.format = FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT;
    outputRes.description.width = data->renderWidth;
    outputRes.description.height = data->renderHeight;
    outputRes.description.depth = 1;
    outputRes.description.mipCount = 1;

    // Prepare dispatch descriptor
    ffxDispatchDescUpscale dispatchDesc = {};
    dispatchDesc.commandList = g_commandList;
    dispatchDesc.color = colorRes;
    dispatchDesc.depth = depthRes;
    dispatchDesc.motionVectors = motionRes;
    dispatchDesc.output = outputRes;
    dispatchDesc.jitterOffset[0] = data->jitterX;
    dispatchDesc.jitterOffset[1] = data->jitterY;
    dispatchDesc.motionVectorScale[0] = 1.0f;
    dispatchDesc.motionVectorScale[1] = 1.0f;
    dispatchDesc.renderSize[0] = data->renderWidth;
    dispatchDesc.renderSize[1] = data->renderHeight;
    dispatchDesc.enableSharpening = 1;
    dispatchDesc.sharpness = data->sharpness;
    dispatchDesc.frameTimeDelta = data->frameTimeDelta;
    dispatchDesc.preExposure = 1.0f;
    dispatchDesc.reset = data->reset;
    dispatchDesc.cameraNear = data->cameraNear;
    dispatchDesc.cameraFar = data->cameraFar;
    dispatchDesc.cameraFovAngleVertical = data->cameraFovVertical;

    // Reset command list
    g_commandAllocator->Reset();
    g_commandList->Reset(g_commandAllocator, nullptr);

    // Dispatch FSR3
    printf("Calling ffxDispatch...\n");
    ffxReturnCode_t result = ffxDispatch(g_fsr3Context, &dispatchDesc);

    if (result != FFX_OK) {
        printf("ERROR: ffxDispatch failed: %d\n", result);
        resp.result = result;
        snprintf(resp.message, sizeof(resp.message), "ffxDispatch failed: %d", result);
    } else {
        printf("FSR3 dispatch completed successfully!\n");

        // Execute command list
        g_commandList->Close();
        ID3D12CommandList* cmdLists[] = { g_commandList };
        g_commandQueue->ExecuteCommandLists(1, cmdLists);

        resp.result = 0;
        strcpy(resp.message, "Dispatch completed");
    }

    send(clientSock, (char*)&resp, sizeof(resp), 0);
}

void HandleClient(SOCKET clientSock) {
    printf("Client connected\n");

    while (true) {
        CommandHeader header;
        int received = recv(clientSock, (char*)&header, sizeof(header), 0);

        if (received <= 0) {
            printf("Client disconnected\n");
            break;
        }

        printf("Received command: %d\n", header.type);

        switch (header.type) {
            case CMD_CREATE_CONTEXT: {
                CreateContextData data;
                recv(clientSock, (char*)&data, sizeof(data), 0);
                HandleCreateContext(clientSock, &data);
                break;
            }
            case CMD_DISPATCH: {
                DispatchData data;
                recv(clientSock, (char*)&data, sizeof(data), 0);
                HandleDispatch(clientSock, &data);
                break;
            }
            case CMD_SHUTDOWN:
                printf("Shutdown requested\n");
                closesocket(clientSock);
                return;
            default:
                printf("Unknown command: %d\n", header.type);
                break;
        }
    }

    closesocket(clientSock);
}

int main() {
    printf("FSR3 Wine Bridge Server (Unix Socket version)\n");
    printf("==============================================\n\n");

    if (!InitD3D12()) {
        printf("D3D12 initialization failed\n");
        return 1;
    }

    LoadFSR3();

    // Initialize Winsock
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        printf("WSAStartup failed\n");
        return 1;
    }

    // Create TCP socket (Wine fully supports this)
    SOCKET listenSock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listenSock == INVALID_SOCKET) {
        printf("Socket creation failed: %d\n", WSAGetLastError());
        WSACleanup();
        return 1;
    }

    // Allow address reuse
    int opt = 1;
    setsockopt(listenSock, SOL_SOCKET, SO_REUSEADDR, (char*)&opt, sizeof(opt));

    // Bind to localhost
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  // 127.0.0.1
    addr.sin_port = htons(SERVER_PORT);

    if (bind(listenSock, (struct sockaddr*)&addr, sizeof(addr)) == SOCKET_ERROR) {
        printf("Bind failed: %d\n", WSAGetLastError());
        closesocket(listenSock);
        WSACleanup();
        return 1;
    }

    if (listen(listenSock, 5) == SOCKET_ERROR) {
        printf("Listen failed: %d\n", WSAGetLastError());
        closesocket(listenSock);
        WSACleanup();
        return 1;
    }

    printf("Server ready, listening on localhost:%d\n", SERVER_PORT);
    printf("Waiting for connections...\n\n");

    while (true) {
        SOCKET clientSock = accept(listenSock, NULL, NULL);
        if (clientSock == INVALID_SOCKET) {
            printf("Accept failed: %d\n", WSAGetLastError());
            continue;
        }

        HandleClient(clientSock);
    }

    closesocket(listenSock);
    WSACleanup();

    // Cleanup FSR3
    if (g_fsr3Context) {
        ffxDestroyContext(&g_fsr3Context, nullptr);
    }

    // Cleanup D3D12 resources
    if (g_colorResource.resource) g_colorResource.resource->Release();
    if (g_depthResource.resource) g_depthResource.resource->Release();
    if (g_motionResource.resource) g_motionResource.resource->Release();
    if (g_outputResource.resource) g_outputResource.resource->Release();
    if (g_commandList) g_commandList->Release();
    if (g_commandAllocator) g_commandAllocator->Release();
    if (g_commandQueue) g_commandQueue->Release();
    if (g_device) g_device->Release();
    if (g_factory) g_factory->Release();
    if (g_fsrDll) FreeLibrary(g_fsrDll);

    return 0;
}
