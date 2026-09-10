// Caustica — Vulkan renderer header (spike)
//
// Minimal interface that mirrors what MCVR-side FSR3 dispatch host would
// need: an open VkDevice + VkPhysicalDevice + a compute pipeline wired
// against a one-stage SPIR-V blob. The actual implementation just
// proves the toolchain (RADV + glslc + the C++ path to ffx-api Vulkan
// backend) can co-exist on this build host without breaking the Java
// build pipeline.
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include <string>
#include <vector>

namespace caustica_mcvr {

class VkRenderer {
public:
    VkRenderer();
    ~VkRenderer();

    // Initialise instance + device + compute pipeline using the first
    // physical device that supports Vulkan 1.2. spvPath is the absolute
    // or exe-relative path to a pre-compiled compute shader (built by
    // the CMake post-build step). Returns VK_SUCCESS or an early-abort
    // VkResult.
    VkResult init(const char* spvPath);

    // Run a one-shot dispatch of the bundled compute shader. The
    // dispatch writes to a small GPU scratch buffer; we read back and
    // verify the result. This is purely a "did Vulkan + SPIR-V pipeline
    // actually execute?" smoke test.
    int runSpike();

    const std::string& statusMessage() const { return status_; }

private:
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue computeQueue_ = VK_NULL_HANDLE;
    uint32_t computeFamily_ = 0;

    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkBuffer scratchBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory scratchMemory_ = VK_NULL_HANDLE;
    VkMemoryPropertyFlags scratchMemoryFlags_ = 0;

    std::string status_;

    void shutdown();
    VkResult createInstance();
    VkResult pickPhysical();
    VkResult createDeviceAndQueue();
    VkResult loadSpirv(const char* path, std::vector<uint32_t>& out);
    VkResult createComputePipeline(const std::vector<uint32_t>& spv);
    VkResult createScratch(size_t bytes);
    VkResult createDescriptorResources();
};

} // namespace caustica_mcvr
