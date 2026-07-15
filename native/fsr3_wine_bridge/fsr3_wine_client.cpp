/*
 * FSR3 Wine Bridge Client (Linux side)
 * Communicates with Wine server to access Windows FSR3 DLL
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
            float jitterX;
            float jitterY;
        } dispatch;
    };

    int32_t result;
};

class FSR3WineBridge {
private:
    int shmFd;
    FSR3Command* cmd;

public:
    FSR3WineBridge() : shmFd(-1), cmd(nullptr) {}

    bool init() {
        // Open shared memory (created by Wine server)
        shmFd = shm_open(SHARED_MEM_NAME, O_RDWR, 0666);
        if (shmFd == -1) {
            perror("shm_open failed");
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

        printf("FSR3 Wine bridge connected\n");
        return true;
    }

    bool createContext(uint32_t renderW, uint32_t renderH, uint32_t displayW, uint32_t displayH) {
        cmd->type = FSR3Command::CREATE_CONTEXT;
        cmd->createContext.renderWidth = renderW;
        cmd->createContext.renderHeight = renderH;
        cmd->createContext.displayWidth = displayW;
        cmd->createContext.displayHeight = displayH;

        // Wait for result (simplified)
        usleep(1000);

        return cmd->result == 0;
    }

    // Export Vulkan image as external memory handle
    uint64_t exportVulkanImage(VkDevice device, VkImage image) {
        // TODO: Use VK_KHR_external_memory_fd to export
        // VkMemoryGetFdInfoKHR fdInfo = {};
        // fdInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
        // fdInfo.memory = imageMemory;
        // fdInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        //
        // int fd;
        // vkGetMemoryFdKHR(device, &fdInfo, &fd);
        // return (uint64_t)fd;

        return 0; // Stub
    }

    bool dispatch(VkImage colorImage, VkImage depthImage, VkImage motionImage, VkImage outputImage,
                  float jitterX, float jitterY) {
        cmd->type = FSR3Command::DISPATCH;
        // Export Vulkan images to handles...
        cmd->dispatch.colorHandle = exportVulkanImage(VK_NULL_HANDLE, colorImage);
        // ... etc

        cmd->dispatch.jitterX = jitterX;
        cmd->dispatch.jitterY = jitterY;

        usleep(1000);
        return cmd->result == 0;
    }

    void shutdown() {
        if (cmd) {
            cmd->type = FSR3Command::SHUTDOWN;
            munmap(cmd, sizeof(FSR3Command));
        }
        if (shmFd != -1) {
            close(shmFd);
        }
    }

    ~FSR3WineBridge() {
        shutdown();
    }
};

// C API for JNI
extern "C" {

void* fsr3_wine_bridge_create() {
    auto* bridge = new FSR3WineBridge();
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

void fsr3_wine_bridge_destroy(void* handle) {
    delete static_cast<FSR3WineBridge*>(handle);
}

} // extern "C"
