// Caustica — Vulkan renderer implementation (spike)
//
// Walks through the minimum Vulkan lifecycle needed to confirm:
//   1) RADV loads on this box (VkInstance)
//   2) We can open a device with a Vulkan 1.2-capable queue (VkDevice)
//   3) We can load SPIR-V produced by glslc (VkShaderModule)
//   4) We can build a compute pipeline and dispatch it (VkPipeline +
//      VkCommandBuffer + VkQueue)
//   5) The dispatch produced real GPU work we can read back
//
// None of the buffers / shaders here are interesting — the spike is
// "do all of the above actually work end-to-end". If this builds and
// runs cleanly, the same project structure is what we'd extend for
// the FSR3 compute pass, BLAS build, and full RT pipeline.
#include "vk_renderer.h"

#include <cstdio>
#include <cstring>
#include <vector>
#include <algorithm>

namespace caustica_mcvr {

namespace {
VKAPI_ATTR VkBool32 VKAPI_CALL debugCb(VkDebugUtilsMessageSeverityFlagBitsEXT sev,
                                        VkDebugUtilsMessageTypeFlagsEXT,
                                        const VkDebugUtilsMessengerCallbackDataEXT* p,
                                        void* user) {
    (void)sev; (void)user;
    std::fprintf(stderr, "[vulkan] %s\n", p ? (p->pMessage ? p->pMessage : "(null)") : "(null)");
    return VK_FALSE;
}

VkBool32 pickFirst(VkPhysicalDevice dev, void* ud) {
    *static_cast<VkPhysicalDevice*>(ud) = dev;
    return VK_FALSE;
}
} // namespace

VkRenderer::VkRenderer() = default;
VkRenderer::~VkRenderer() { shutdown();}

void VkRenderer::shutdown() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        if (pipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, pipeline_, nullptr);
            pipeline_ = VK_NULL_HANDLE;
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
            pipelineLayout_ = VK_NULL_HANDLE;
        }
        if (descriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
            descriptorPool_ = VK_NULL_HANDLE;
            descriptorSet_ = VK_NULL_HANDLE;
        }
        if (descriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr);
            descriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (shaderModule_ != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device_, shaderModule_, nullptr);
            shaderModule_ = VK_NULL_HANDLE;
        }
        if (scratchBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, scratchBuffer_, nullptr);
            scratchBuffer_ = VK_NULL_HANDLE;
        }
        if (scratchMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, scratchMemory_, nullptr);
            scratchMemory_ = VK_NULL_HANDLE;
            scratchMemoryFlags_ = 0;
        }
        if (commandPool_ != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device_, commandPool_, nullptr);
            commandPool_ = VK_NULL_HANDLE;
        }
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        computeQueue_ = VK_NULL_HANDLE;
    }
    physical_ = VK_NULL_HANDLE;
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
}

VkResult VkRenderer::createInstance() {
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "caustica_mcvr_spike";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "caustica";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = VK_API_VERSION_1_2;

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    VkResult r = vkCreateInstance(&ci, nullptr, &instance_);
    if (r != VK_SUCCESS) {
        status_ = "vkCreateInstance failed: " + std::to_string(r);
        return r;
    }
    return VK_SUCCESS;
}

VkResult VkRenderer::pickPhysical() {
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(instance_, &n, nullptr);
    if (n == 0) return VK_ERROR_INITIALIZATION_FAILED;
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(instance_, &n, devs.data());
    // Pick the first device that supports Vulkan 1.2.
    for (auto d : devs) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(d, &props);
        if (props.apiVersion >= VK_API_VERSION_1_2) {
            physical_ = d;
            status_ = "Selected physical device: " + std::string(props.deviceName);
            return VK_SUCCESS;
        }
    }
    return VK_ERROR_INITIALIZATION_FAILED;
}

VkResult VkRenderer::createDeviceAndQueue() {
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical_, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(physical_, &qn, qf.data());

    computeFamily_ = UINT32_MAX;
    for (uint32_t i = 0; i < qn; i++) {
        if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            computeFamily_ = i;
            break;
        }
    }
    if (computeFamily_ == UINT32_MAX) return VK_ERROR_INITIALIZATION_FAILED;

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = computeFamily_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;

    VkPhysicalDeviceFeatures feats{};
    VkDeviceCreateInfo dci{};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.pEnabledFeatures = &feats;

    VkResult r = vkCreateDevice(physical_, &dci, nullptr, &device_);
    if (r != VK_SUCCESS) {
        status_ = "vkCreateDevice failed: " + std::to_string(r);
        return r;
    }
    vkGetDeviceQueue(device_, computeFamily_, 0, &computeQueue_);
    return VK_SUCCESS;
}

VkResult VkRenderer::loadSpirv(const char* path, std::vector<uint32_t>& out) {
    FILE* f = std::fopen(path, "rb");
    if (!f) return VK_ERROR_INITIALIZATION_FAILED;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (sz <= 0 || (sz % 4) != 0) {
        std::fclose(f);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    out.resize(sz / 4);
    size_t r = std::fread(out.data(), 1, sz, f);
    std::fclose(f);
    return (r == (size_t)sz) ? VK_SUCCESS : VK_ERROR_INITIALIZATION_FAILED;
}

VkResult VkRenderer::createComputePipeline(const std::vector<uint32_t>& spv) {
    VkShaderModuleCreateInfo smci{};
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = spv.size() * sizeof(uint32_t);
    smci.pCode = spv.data();
    VkResult r = vkCreateShaderModule(device_, &smci, nullptr, &shaderModule_);
    if (r != VK_SUCCESS) return r;

    VkPipelineLayoutCreateInfo plci{};
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &descriptorSetLayout_;
    plci.pushConstantRangeCount = 0;
    plci.pPushConstantRanges = nullptr;
    r = vkCreatePipelineLayout(device_, &plci, nullptr, &pipelineLayout_);
    if (r != VK_SUCCESS) return r;

    VkComputePipelineCreateInfo cpci{};
    cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = shaderModule_;
    cpci.stage.pName = "main";
    cpci.layout = pipelineLayout_;
    return vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipeline_);
}

VkResult VkRenderer::createScratch(size_t bytes) {
    VkBufferCreateInfo bci{};
    bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size = bytes;
    bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult r = vkCreateBuffer(device_, &bci, nullptr, &scratchBuffer_);
    if (r != VK_SUCCESS) return r;

    VkMemoryRequirements mem{};
    vkGetBufferMemoryRequirements(device_, scratchBuffer_, &mem);
    uint32_t typeBits = mem.memoryTypeBits;
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(physical_, &mp);
    uint32_t hostVisible = UINT32_MAX;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        const VkMemoryPropertyFlags flags = mp.memoryTypes[i].propertyFlags;
        if ((typeBits & (1u << i))
                && (flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
                        == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            hostVisible = i;
            scratchMemoryFlags_ = flags;
            break;
        }
    }
    if (hostVisible == UINT32_MAX) {
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
            const VkMemoryPropertyFlags flags = mp.memoryTypes[i].propertyFlags;
            if ((typeBits & (1u << i)) && (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
                hostVisible = i;
                scratchMemoryFlags_ = flags;
                break;
            }
        }
    }
    if (hostVisible == UINT32_MAX) return VK_ERROR_INITIALIZATION_FAILED;

    VkMemoryAllocateInfo mai{};
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize = mem.size;
    mai.memoryTypeIndex = hostVisible;
    r = vkAllocateMemory(device_, &mai, nullptr, &scratchMemory_);
    if (r != VK_SUCCESS) return r;
    return vkBindBufferMemory(device_, scratchBuffer_, scratchMemory_, 0);
}

VkResult VkRenderer::createDescriptorResources() {
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &binding;
    VkResult r = vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &descriptorSetLayout_);
    if (r != VK_SUCCESS) return r;

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    r = vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_);
    if (r != VK_SUCCESS) return r;

    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = descriptorPool_;
    allocateInfo.descriptorSetCount = 1;
    allocateInfo.pSetLayouts = &descriptorSetLayout_;
    r = vkAllocateDescriptorSets(device_, &allocateInfo, &descriptorSet_);
    if (r != VK_SUCCESS) return r;

    VkDescriptorBufferInfo bufferInfo{};
    bufferInfo.buffer = scratchBuffer_;
    bufferInfo.offset = 0;
    bufferInfo.range = 2 * sizeof(uint32_t);

    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = descriptorSet_;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    write.pBufferInfo = &bufferInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    return VK_SUCCESS;
}

VkResult VkRenderer::init(const char* spvPath) {
    VkResult r = createInstance();
    if (r != VK_SUCCESS) return r;
    r = pickPhysical();
    if (r != VK_SUCCESS) return r;
    r = createDeviceAndQueue();
    if (r != VK_SUCCESS) return r;

    VkCommandPoolCreateInfo cpci{};
    cpci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpci.queueFamilyIndex = computeFamily_;
    r = vkCreateCommandPool(device_, &cpci, nullptr, &commandPool_);
    if (r != VK_SUCCESS) return r;

    std::vector<uint32_t> spv;
    r = loadSpirv(spvPath, spv);
    if (r != VK_SUCCESS) {
        status_ = std::string("Failed to load vk_cube.comp.spv at ") + (spvPath ? spvPath : "(null)");
        return r;
    }

    r = createScratch(2 * sizeof(uint32_t));
    if (r != VK_SUCCESS) {
        status_ = "Failed to create host-visible scratch buffer: " + std::to_string(r);
        return r;
    }
    r = createDescriptorResources();
    if (r != VK_SUCCESS) {
        status_ = "Failed to create scratch descriptor resources: " + std::to_string(r);
        return r;
    }
    r = createComputePipeline(spv);
    if (r != VK_SUCCESS) {
        status_ = "Failed to create compute pipeline: " + std::to_string(r);
        return r;
    }
    return VK_SUCCESS;
}

int VkRenderer::runSpike() {
    struct SpikeResult {
        uint32_t counter;
        uint32_t marker;
    };

    void* mapped = nullptr;
    VkResult r = vkMapMemory(device_, scratchMemory_, 0, sizeof(SpikeResult), 0, &mapped);
    if (r != VK_SUCCESS) {
        status_ = "vkMapMemory for sentinel failed: " + std::to_string(r);
        return 2;
    }
    auto* sentinel = static_cast<SpikeResult*>(mapped);
    sentinel->counter = 0xDEADBEEFu;
    sentinel->marker = 0xDEADBEEFu;
    if ((scratchMemoryFlags_ & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = scratchMemory_;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        r = vkFlushMappedMemoryRanges(device_, 1, &range);
        if (r != VK_SUCCESS) {
            vkUnmapMemory(device_, scratchMemory_);
            status_ = "vkFlushMappedMemoryRanges failed: " + std::to_string(r);
            return 3;
        }
    }
    vkUnmapMemory(device_, scratchMemory_);

    r = vkResetCommandPool(device_, commandPool_, 0);
    if (r != VK_SUCCESS) {
        status_ = "vkResetCommandPool failed: " + std::to_string(r);
        return 4;
    }

    VkCommandBufferAllocateInfo cbai{};
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = commandPool_;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    r = vkAllocateCommandBuffers(device_, &cbai, &cmd);
    if (r != VK_SUCCESS) {
        status_ = "vkAllocateCommandBuffers failed: " + std::to_string(r);
        return 5;
    }

    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    r = vkBeginCommandBuffer(cmd, &cbbi);
    if (r != VK_SUCCESS) {
        vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);
        status_ = "vkBeginCommandBuffer failed: " + std::to_string(r);
        return 6;
    }

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                            0, 1, &descriptorSet_, 0, nullptr);
    vkCmdDispatch(cmd, 1, 1, 1);

    VkBufferMemoryBarrier readbackBarrier{};
    readbackBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    readbackBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    readbackBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    readbackBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    readbackBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    readbackBarrier.buffer = scratchBuffer_;
    readbackBarrier.offset = 0;
    readbackBarrier.size = sizeof(SpikeResult);
    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT,
                         0, 0, nullptr, 1, &readbackBarrier, 0, nullptr);

    r = vkEndCommandBuffer(cmd);
    if (r != VK_SUCCESS) {
        vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);
        status_ = "vkEndCommandBuffer failed: " + std::to_string(r);
        return 7;
    }

    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    r = vkQueueSubmit(computeQueue_, 1, &si, VK_NULL_HANDLE);
    if (r != VK_SUCCESS) {
        vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);
        status_ = "vkQueueSubmit failed: " + std::to_string(r);
        return 8;
    }

    r = vkQueueWaitIdle(computeQueue_);
    if (r != VK_SUCCESS) {
        vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);
        status_ = "vkQueueWaitIdle failed: " + std::to_string(r);
        return 9;
    }
    vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);

    mapped = nullptr;
    r = vkMapMemory(device_, scratchMemory_, 0, sizeof(SpikeResult), 0, &mapped);
    if (r != VK_SUCCESS) {
        status_ = "vkMapMemory for readback failed: " + std::to_string(r);
        return 10;
    }
    if ((scratchMemoryFlags_ & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = scratchMemory_;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        r = vkInvalidateMappedMemoryRanges(device_, 1, &range);
        if (r != VK_SUCCESS) {
            vkUnmapMemory(device_, scratchMemory_);
            status_ = "vkInvalidateMappedMemoryRanges failed: " + std::to_string(r);
            return 11;
        }
    }

    const SpikeResult result = *static_cast<const SpikeResult*>(mapped);
    vkUnmapMemory(device_, scratchMemory_);
    if (result.counter != 7u || result.marker != 0xCAFEu) {
        char message[128];
        std::snprintf(message, sizeof(message),
                      "GPU readback mismatch: counter=%u marker=0x%x",
                      result.counter, result.marker);
        status_ = message;
        return 12;
    }

    char message[128];
    std::snprintf(message, sizeof(message),
                  "GPU readback verified: counter=%u marker=0x%x",
                  result.counter, result.marker);
    status_ = message;
    return 0;
}

} // namespace caustica_mcvr
