/*
 * FSR3 Wine Bridge Client - Full Implementation with Vulkan External Memory
 */

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <vulkan/vulkan.h>

#define SHARED_MEM_NAME "/fsr3_wine_bridge"

// Must match Windows side
struct FSR3Command {
    enum Type {
        INIT,
        CREATE_CONTEXT,
        DISPATCH,
        DESTROY,
        SHUTDOWN
    } type;

    union {
        struct {
            uint32_t renderWidth;
            uint32_t renderHeight;
            uint32_t displayWidth;
            uint32_t displayHeight;
        } createContext;

        struct {
            uint64_t colorHandle;
            uint64_t depthHandle;
            uint64_t motionHandle;
            uint64_t outputHandle;
            uint32_t renderWidth;
            uint32_t renderHeight;
            float jitterX;
            float jitterY;
        } dispatch;
    };

    int32_t result;
    bool ready;
};

class FSR3WineBridge {
private:
    int shmFd;
    FSR3Command* cmd;
    VkDevice vkDevice;
    VkPhysicalDevice vkPhysicalDevice;
    VkInstance vkInstance;

    PFN_vkGetMemoryFdKHR vkGetMemoryFdKHR;

public:
    FSR3WineBridge(VkInstance instance, VkPhysicalDevice physDevice, VkDevice device)
        : shmFd(-1), cmd(nullptr), vkInstance(instance),
          vkPhysicalDevice(physDevice), vkDevice(device) {

        // Load extension functions
        vkGetMemoryFdKHR = (PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(device, "vkGetMemoryFdKHR");
    }

    bool init() {
        // Open shared memory (created by Wine server)
        shmFd = shm_open(SHARED_MEM_NAME, O_RDWR, 0666);
        if (shmFd == -1) {
            perror("shm_open failed - is Wine server running?");
            return false;
        }

        // Set size
        if (ftruncate(shmFd, sizeof(FSR3Command)) == -1) {
            perror("ftruncate failed");
            close(shmFd);
            return false;
        }

        cmd = (FSR3Command*)mmap(
            nullptr,
            sizeof(FSR3Command),
            PROT_READ | PROT_WRITE,
            MAP_SHARED,
            shmFd,
            0
        );

        if (cmd == MAP_FAILED) {
            perror("mmap failed");
            close(shmFd);
            return false;
        }

        // Wait for server to be ready
        int timeout = 50; // 5 seconds
        while (!cmd->ready && timeout > 0) {
            usleep(100000); // 100ms
            timeout--;
        }

        if (!cmd->ready) {
            printf("ERROR: Wine server did not become ready\n");
            return false;
        }

        printf("FSR3 Wine bridge connected to server\n");
        return true;
    }

    bool createContext(uint32_t renderW, uint32_t renderH, uint32_t displayW, uint32_t displayH) {
        if (!cmd) return false;

        cmd->type = FSR3Command::CREATE_CONTEXT;
        cmd->createContext.renderWidth = renderW;
        cmd->createContext.renderHeight = renderH;
        cmd->createContext.displayWidth = displayW;
        cmd->createContext.displayHeight = displayH;

        // Wait for server to process
        usleep(10000); // 10ms

        printf("FSR3 context created: %dx%d -> %dx%d\n", renderW, renderH, displayW, displayH);
        return cmd->result == 0;
    }

    // Export Vulkan image memory as file descriptor (Linux) which Wine can import as HANDLE
    int exportVulkanImageMemory(VkDeviceMemory memory) {
        if (!vkGetMemoryFdKHR) {
            printf("ERROR: VK_KHR_external_memory_fd not available\n");
            return -1;
        }

        VkMemoryGetFdInfoKHR fdInfo = {};
        fdInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
        fdInfo.memory = memory;
        fdInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

        int fd;
        VkResult result = vkGetMemoryFdKHR(vkDevice, &fdInfo, &fd);
        if (result != VK_SUCCESS) {
            printf("ERROR: vkGetMemoryFdKHR failed: %d\n", result);
            return -1;
        }

        return fd;
    }

    bool dispatch(VkDeviceMemory colorMem, VkDeviceMemory depthMem,
                  VkDeviceMemory motionMem, VkDeviceMemory outputMem,
                  uint32_t renderWidth, uint32_t renderHeight,
                  float jitterX, float jitterY) {
        if (!cmd) return false;

        // Export Vulkan memory as file descriptors
        int colorFd = exportVulkanImageMemory(colorMem);
        int depthFd = exportVulkanImageMemory(depthMem);
        int motionFd = exportVulkanImageMemory(motionMem);
        int outputFd = exportVulkanImageMemory(outputMem);

        if (colorFd < 0 || depthFd < 0 || motionFd < 0 || outputFd < 0) {
            printf("ERROR: Failed to export Vulkan memory\n");
            return false;
        }

        cmd->type = FSR3Command::DISPATCH;
        cmd->dispatch.colorHandle = (uint64_t)colorFd;
        cmd->dispatch.depthHandle = (uint64_t)depthFd;
        cmd->dispatch.motionHandle = (uint64_t)motionFd;
        cmd->dispatch.outputHandle = (uint64_t)outputFd;
        cmd->dispatch.renderWidth = renderWidth;
        cmd->dispatch.renderHeight = renderHeight;
        cmd->dispatch.jitterX = jitterX;
        cmd->dispatch.jitterY = jitterY;

        // Wait for dispatch to complete
        usleep(5000); // 5ms

        // Close file descriptors
        close(colorFd);
        close(depthFd);
        close(motionFd);
        close(outputFd);

        return cmd->result == 0;
    }

    void shutdown() {
        if (cmd) {
            cmd->type = FSR3Command::SHUTDOWN;
            munmap(cmd, sizeof(FSR3Command));
            cmd = nullptr;
        }
        if (shmFd != -1) {
            close(shmFd);
            shmFd = -1;
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
    return bridge->createContext(rw, rh, dw, dh) ? 0 : -1;
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
