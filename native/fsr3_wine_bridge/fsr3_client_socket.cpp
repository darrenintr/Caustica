/*
 * FSR3 Wine Bridge Client - TCP Socket Version
 */

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vulkan/vulkan.h>

#define SERVER_PORT 19573

// Must match server
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

class FSR3WineBridge {
private:
    int sockFd;
    VkDevice vkDevice;
    VkPhysicalDevice vkPhysicalDevice;
    VkInstance vkInstance;

    PFN_vkGetMemoryFdKHR vkGetMemoryFdKHR;

public:
    FSR3WineBridge(VkInstance instance, VkPhysicalDevice physDevice, VkDevice device)
        : sockFd(-1), vkInstance(instance),
          vkPhysicalDevice(physDevice), vkDevice(device) {

        if (device != VK_NULL_HANDLE) {
            vkGetMemoryFdKHR = (PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(device, "vkGetMemoryFdKHR");
        }
    }

    bool init() {
        sockFd = socket(AF_INET, SOCK_STREAM, 0);
        if (sockFd == -1) {
            perror("socket() failed");
            return false;
        }

        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons(SERVER_PORT);
        addr.sin_addr.s_addr = inet_addr("127.0.0.1");

        if (connect(sockFd, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
            perror("connect() failed - is Wine server running?");
            close(sockFd);
            sockFd = -1;
            return false;
        }

        printf("Connected to FSR3 Wine server (localhost:%d)\n", SERVER_PORT);
        return true;
    }

    bool createContext(uint32_t renderW, uint32_t renderH, uint32_t displayW, uint32_t displayH,
                       uint32_t qualityMode = 2, uint64_t flags = 0) {
        if (sockFd == -1) return false;

        CommandHeader header = { CMD_CREATE_CONTEXT, sizeof(CreateContextData) };
        CreateContextData data = { renderW, renderH, displayW, displayH, qualityMode, flags };

        if (send(sockFd, &header, sizeof(header), 0) == -1) {
            perror("send header failed");
            return false;
        }

        if (send(sockFd, &data, sizeof(data), 0) == -1) {
            perror("send data failed");
            return false;
        }

        Response resp;
        if (recv(sockFd, &resp, sizeof(resp), 0) == -1) {
            perror("recv response failed");
            return false;
        }

        printf("FSR3 context created: %s\n", resp.message);
        return resp.result == 0;
    }

    int exportVulkanImageMemory(VkDeviceMemory memory) {
        if (!vkGetMemoryFdKHR || memory == VK_NULL_HANDLE) {
            return -1;
        }

        VkMemoryGetFdInfoKHR fdInfo = {};
        fdInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
        fdInfo.memory = memory;
        fdInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

        int fd;
        VkResult result = vkGetMemoryFdKHR(vkDevice, &fdInfo, &fd);
        if (result != VK_SUCCESS) {
            printf("vkGetMemoryFdKHR failed: %d\n", result);
            return -1;
        }

        return fd;
    }

    bool dispatch(VkDeviceMemory colorMem, VkDeviceMemory depthMem,
                  VkDeviceMemory motionMem, VkDeviceMemory outputMem,
                  uint32_t renderWidth, uint32_t renderHeight,
                  float jitterX, float jitterY,
                  float frameTimeDelta = 16.67f, float sharpness = 0.5f,
                  float cameraNear = 0.1f, float cameraFar = 1000.0f,
                  float cameraFov = 1.57f, int32_t reset = 0) {
        if (sockFd == -1) return false;

        // For now, use dummy handles (will implement real export later)
        CommandHeader header = { CMD_DISPATCH, sizeof(DispatchData) };
        DispatchData data = {};
        data.colorHandle = 0;  // TODO: export real handles
        data.depthHandle = 0;
        data.motionHandle = 0;
        data.outputHandle = 0;
        data.renderWidth = renderWidth;
        data.renderHeight = renderHeight;
        data.jitterX = jitterX;
        data.jitterY = jitterY;
        data.frameTimeDelta = frameTimeDelta;
        data.sharpness = sharpness;
        data.cameraNear = cameraNear;
        data.cameraFar = cameraFar;
        data.cameraFovVertical = cameraFov;
        data.reset = reset;

        if (send(sockFd, &header, sizeof(header), 0) == -1) {
            perror("send header failed");
            return false;
        }

        if (send(sockFd, &data, sizeof(data), 0) == -1) {
            perror("send data failed");
            return false;
        }

        Response resp;
        if (recv(sockFd, &resp, sizeof(resp), 0) == -1) {
            perror("recv response failed");
            return false;
        }

        printf("Dispatch completed: %s\n", resp.message);
        return resp.result == 0;
    }

    void shutdown() {
        if (sockFd != -1) {
            CommandHeader header = { CMD_SHUTDOWN, 0 };
            send(sockFd, &header, sizeof(header), 0);
            close(sockFd);
            sockFd = -1;
        }
    }

    ~FSR3WineBridge() {
        shutdown();
    }
};

// C API for JNI
extern "C" {

void* fsr3_wine_bridge_create(VkInstance instance, VkPhysicalDevice physDevice, VkDevice device) {
    auto* bridge = new FSR3WineBridge(instance, physDevice, device);
    if (!bridge->init()) {
        delete bridge;
        return nullptr;
    }
    return bridge;
}

int fsr3_wine_bridge_create_context(void* handle, uint32_t rw, uint32_t rh, uint32_t dw, uint32_t dh) {
    auto* bridge = static_cast<FSR3WineBridge*>(handle);
    return bridge->createContext(rw, rh, dw, dh, 2, 0) ? 0 : -1;  // Default: BALANCED mode, no flags
}

int fsr3_wine_bridge_dispatch(void* handle,
                               VkDeviceMemory colorMem, VkDeviceMemory depthMem,
                               VkDeviceMemory motionMem, VkDeviceMemory outputMem,
                               uint32_t renderW, uint32_t renderH,
                               float jitterX, float jitterY) {
    auto* bridge = static_cast<FSR3WineBridge*>(handle);
    return bridge->dispatch(colorMem, depthMem, motionMem, outputMem,
                           renderW, renderH, jitterX, jitterY) ? 0 : -1;
}

void fsr3_wine_bridge_destroy(void* handle) {
    delete static_cast<FSR3WineBridge*>(handle);
}

} // extern "C"
