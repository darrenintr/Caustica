#pragma once

#include "caustica_backend.h"

#include <vulkan/vulkan.h>
#define VMA_STATS_VULKAN_VERSION 1000000
#define VMA_STATS_STRING_BUFFER_SIZE 1024
#include "../../../third_party/FidelityFX-SDK/Kits/OpenSource/amd/memoryallocator/vk_mem_alloc.h"

#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace caustica::renderer {

struct ShaderBlob {
    std::vector<uint32_t> words;
    uint64_t content_hash = 0;
};

struct FireflyPass {
    VkDescriptorSetLayout descriptor_set_layout = VK_NULL_HANDLE;
    VkDescriptorPool descriptor_pool = VK_NULL_HANDLE;
    VkDescriptorSet descriptor_set = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkImageView input_view = VK_NULL_HANDLE;
    VkImageView output_view = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    uint64_t generation = 0;
    bool prepared = false;
};

struct NativeBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VmaAllocation allocation = VK_NULL_HANDLE;
    VkDeviceAddress device_address = 0;
    void* mapped = nullptr;
    VkDeviceSize size = 0;
    VkBufferUsageFlags usage = 0;
    uint32_t flags = 0;
    uint32_t memory_type = 0;
    bool destroyed = false;
};

struct NativeImage {
    VkImage image = VK_NULL_HANDLE;
    VmaAllocation allocation = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t memory_type = 0;
    bool destroyed = false;
};

struct Backend {
    std::mutex mutex;
    ca_device_desc device_desc{};
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphics_queue = VK_NULL_HANDLE;
    VkQueue compute_queue = VK_NULL_HANDLE;
    VmaAllocator vma = VK_NULL_HANDLE;
    VkCommandPool command_pool = VK_NULL_HANDLE;
    uint64_t generation = 0;
    uint64_t next_frame_token = 1;
    uint64_t firefly_dispatch_count = 0;
    uint64_t next_resource_id = 1;
    uint32_t shader_group_handle_size = 0;
    uint32_t shader_group_base_alignment = 0;
    uint32_t shader_group_handle_alignment = 0;
    int max_shader_group_stride = 0;
    // RT pipeline function pointers resolved at attach time.
    PFN_vkCreateRayTracingPipelinesKHR pfnCreateRayTracingPipelinesKHR = nullptr;
    PFN_vkGetRayTracingShaderGroupHandlesKHR pfnGetRayTracingShaderGroupHandlesKHR = nullptr;
    PFN_vkCmdTraceRaysKHR pfnCmdTraceRaysKHR = nullptr;
    PFN_vkDestroyAccelerationStructureKHR pfnDestroyAccelerationStructureKHR = nullptr;
    PFN_vkGetAccelerationStructureBuildSizesKHR pfnGetAccelerationStructureBuildSizesKHR = nullptr;
    PFN_vkCreateAccelerationStructureKHR pfnCreateAccelerationStructureKHR = nullptr;
    PFN_vkGetAccelerationStructureDeviceAddressKHR pfnGetAccelerationStructureDeviceAddressKHR = nullptr;
    bool attached = false;
    bool reloading = false;
    std::unordered_map<uint32_t, ShaderBlob> shaders;
    FireflyPass firefly;
    std::unordered_map<uint64_t, std::unique_ptr<NativeBuffer>> buffers;
    std::unordered_map<uint64_t, std::unique_ptr<NativeImage>> images;
    std::unordered_map<uint64_t, void*> pipelines;
    std::string status = "lib ready";
};

Backend* from_handle(ca_backend_t handle);
ca_frame_result result(int32_t status, uint32_t commit_state, uint64_t token = 0,
                       uint64_t diagnostic = 0);
bool ensure_vma(Backend& backend);
void destroy_firefly(Backend& backend);
int32_t ensure_firefly_prepared(Backend& backend, const ca_firefly_desc& desc);
ca_frame_result record_firefly(Backend& backend, const ca_firefly_desc& desc);
ca_pipeline_t create_world_pipeline(Backend& backend, const ca_world_pipeline_desc& config);
void destroy_world_pipeline(Backend& backend, void* pipeline);
int32_t world_set_tlas(Backend& backend, void* pipeline, uint64_t tlas_handle);
int32_t world_set_storage_image(Backend& backend, void* pipeline, uint64_t image_view);
int32_t world_set_extra_storage_image(Backend& backend, void* pipeline, uint32_t slot,
                                     uint64_t image_view);
int32_t world_set_extra_storage_buffer(Backend& backend, void* pipeline, uint32_t slot,
                                      uint64_t buffer_handle, uint64_t size_bytes);
int32_t world_set_atlas_sampler(Backend& backend, void* pipeline, uint64_t image_view,
                                uint64_t sampler);
int32_t world_set_block_spec_atlas(Backend& backend, void* pipeline, uint64_t image_view,
                                    uint64_t sampler);
int32_t world_set_block_normal_atlas(Backend& backend, void* pipeline, uint64_t image_view,
                                      uint64_t sampler);
int32_t world_set_sky_atlas(Backend& backend, void* pipeline, uint64_t image_view,
                             uint64_t sampler);
int32_t world_init_bindless_fallback(Backend& backend, void* pipeline, uint64_t image_view,
                                      uint64_t sampler);
int32_t world_set_bindless_texture(Backend& backend, void* pipeline, uint32_t binding,
                                    uint32_t slot, uint64_t image_view, uint64_t sampler);
int32_t world_trace(Backend& backend, void* pipeline, uint64_t command_buffer, uint32_t width,
                     uint32_t height, uint64_t push_constants_ptr, uint32_t push_constants_size);

} // namespace caustica::renderer
