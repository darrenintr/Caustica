#include "backend_internal.hpp"

#include <cstring>
#include <functional>
#include <vector>

namespace caustica::renderer {

namespace {

VkResult to_vk_result(int32_t status) {
    if (status == CAUSTICA_STATUS_OK) {
        return VK_SUCCESS;
    }
    if (status == CAUSTICA_STATUS_DEVICE_LOST) {
        return VK_ERROR_DEVICE_LOST;
    }
    return VK_ERROR_INITIALIZATION_FAILED;
}

bool format_supports(VkPhysicalDevice physical, VkFormat format, VkImageUsageFlags usage) {
    VkFormatProperties props{};
    vkGetPhysicalDeviceFormatProperties(physical, format, &props);
    VkFormatFeatureFlags required = 0;
    if (usage & VK_IMAGE_USAGE_STORAGE_BIT) {
        required |= VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    }
    if (usage & VK_IMAGE_USAGE_SAMPLED_BIT) {
        required |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    }
    if (usage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) {
        required |= VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
    }
    if (usage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) {
        required |= VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    }
    if (usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) {
        required |= VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
    }
    return (props.optimalTilingFeatures & required) == required;
}

void initialize_buffer_handle(ca_buffer_handle& out, const NativeBuffer& buffer) {
    std::memset(&out, 0, sizeof(out));
    out.size = sizeof(out);
    out.abi = CAUSTICA_BACKEND_ABI_V2;
    out.buffer = reinterpret_cast<uint64_t>(buffer.buffer);
    out.allocation = reinterpret_cast<uint64_t>(buffer.allocation);
    out.device_address = buffer.device_address;
    out.mapped_ptr = reinterpret_cast<uint64_t>(buffer.mapped);
    out.size_bytes = buffer.size;
    out.usage = buffer.usage;
    out.flags = buffer.flags;
    out.memory_type_index = buffer.memory_type;
    out.generation = 0;
}

void initialize_image_handle(ca_image_handle& out, const NativeImage& image) {
    std::memset(&out, 0, sizeof(out));
    out.size = sizeof(out);
    out.abi = CAUSTICA_BACKEND_ABI_V2;
    out.image = reinterpret_cast<uint64_t>(image.image);
    out.allocation = reinterpret_cast<uint64_t>(image.allocation);
    out.view = reinterpret_cast<uint64_t>(image.view);
    out.width = image.width;
    out.height = image.height;
    out.format = image.format;
    out.memory_type_index = image.memory_type;
    out.generation = 0;
}

uint32_t select_memory_type(const VmaAllocator& vma, uint32_t type_bits, VmaMemoryUsage usage) {
    VmaAllocationCreateInfo info{};
    info.usage = usage;
    VmaAllocationInfo allocation_info{};
    for (uint32_t i = 0; i < 32; ++i) {
        if ((type_bits & (1u << i)) != 0) {
            return i;
        }
    }
    return UINT32_MAX;
}

} // namespace

bool ensure_vma(Backend& backend) {
    if (backend.vma != VK_NULL_HANDLE) {
        return true;
    }
    VmaAllocatorCreateInfo info{};
    info.flags = VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
    info.physicalDevice = backend.physical_device;
    info.device = backend.device;
    info.instance = backend.instance;
    info.vulkanApiVersion = VK_API_VERSION_1_2;
    if (vmaCreateAllocator(&info, &backend.vma) != VK_SUCCESS) {
        return false;
    }
    return true;
}

bool ensure_command_pool(Backend& backend) {
    if (backend.command_pool != VK_NULL_HANDLE) {
        return true;
    }
    VkCommandPoolCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    info.queueFamilyIndex = backend.device_desc.graphics_family;
    return vkCreateCommandPool(backend.device, &info, nullptr, &backend.command_pool) == VK_SUCCESS;
}

int32_t submit_one_shot(Backend& backend, const std::function<void(VkCommandBuffer)>& record,
                        VkFence* out_fence) {
    if (!ensure_command_pool(backend)) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    VkCommandBufferAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool = backend.command_pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(backend.device, &ai, &cmd) != VK_SUCCESS) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &bi) != VK_SUCCESS) {
        vkFreeCommandBuffers(backend.device, backend.command_pool, 1, &cmd);
        return CAUSTICA_STATUS_RETRYABLE_PRE_COMMIT;
    }
    record(cmd);
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
        vkFreeCommandBuffers(backend.device, backend.command_pool, 1, &cmd);
        return CAUSTICA_STATUS_RETRYABLE_PRE_COMMIT;
    }
    VkFenceCreateInfo fci{};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkFence fence = VK_NULL_HANDLE;
    if (vkCreateFence(backend.device, &fci, nullptr, &fence) != VK_SUCCESS) {
        vkFreeCommandBuffers(backend.device, backend.command_pool, 1, &cmd);
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    VkResult rc = vkQueueSubmit(backend.graphics_queue, 1, &si, fence);
    if (rc != VK_SUCCESS) {
        vkDestroyFence(backend.device, fence, nullptr);
        vkFreeCommandBuffers(backend.device, backend.command_pool, 1, &cmd);
        return rc == VK_ERROR_DEVICE_LOST ? CAUSTICA_STATUS_DEVICE_LOST
                                          : CAUSTICA_STATUS_RETRYABLE_PRE_COMMIT;
    }
    vkQueueWaitIdle(backend.graphics_queue);
    vkDestroyFence(backend.device, fence, nullptr);
    vkFreeCommandBuffers(backend.device, backend.command_pool, 1, &cmd);
    if (out_fence != nullptr) {
        *out_fence = VK_NULL_HANDLE;
    }
    return CAUSTICA_STATUS_OK;
}

void release_resource(Backend& backend, uint64_t handle) {
    auto buffer_it = backend.buffers.find(handle);
    if (buffer_it != backend.buffers.end()) {
        if (buffer_it->second->buffer != VK_NULL_HANDLE && backend.vma != VK_NULL_HANDLE) {
            vmaDestroyBuffer(backend.vma, buffer_it->second->buffer, buffer_it->second->allocation);
        }
        backend.buffers.erase(buffer_it);
        return;
    }
    auto image_it = backend.images.find(handle);
    if (image_it != backend.images.end()) {
        if (image_it->second->view != VK_NULL_HANDLE) {
            vkDestroyImageView(backend.device, image_it->second->view, nullptr);
        }
        if (image_it->second->image != VK_NULL_HANDLE && backend.vma != VK_NULL_HANDLE) {
            vmaDestroyImage(backend.vma, image_it->second->image, image_it->second->allocation);
        }
        backend.images.erase(image_it);
    }
}

int32_t create_buffer(ca_backend_t handle, uint64_t size_bytes, uint32_t usage, uint32_t flags,
                        uint64_t address_alignment, uint64_t, ca_buffer_handle* out,
                        uint64_t* out_resource_id) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || out == nullptr || backend->device == VK_NULL_HANDLE
            || !ensure_vma(*backend)) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    auto buffer = std::make_unique<NativeBuffer>();
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size_bytes;
    info.usage = usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo vma_info{};
    vma_info.usage = (flags & CAUSTICA_BUFFER_FLAG_HOST_VISIBLE)
            ? VMA_MEMORY_USAGE_AUTO
            : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (flags & CAUSTICA_BUFFER_FLAG_HOST_VISIBLE) {
        vma_info.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                | VMA_ALLOCATION_CREATE_MAPPED_BIT;
    }
    VkBufferCreateInfo ci = info;
    VmaAllocationInfo allocation_info{};
    VkResult rc = address_alignment == 0
            ? vmaCreateBuffer(backend->vma, &ci, &vma_info, &buffer->buffer, &buffer->allocation,
                              &allocation_info)
            : vmaCreateBufferWithAlignment(backend->vma, &ci, &vma_info, address_alignment,
                                           &buffer->buffer, &buffer->allocation, &allocation_info);
    if (rc != VK_SUCCESS) {
        return rc == VK_ERROR_DEVICE_LOST ? CAUSTICA_STATUS_DEVICE_LOST
                                          : CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    VkBufferDeviceAddressInfo bdai{};
    bdai.sType = VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO;
    bdai.buffer = buffer->buffer;
    buffer->device_address = vkGetBufferDeviceAddress(backend->device, &bdai);
    buffer->size = size_bytes;
    buffer->usage = info.usage;
    buffer->flags = flags;
    buffer->mapped = (flags & CAUSTICA_BUFFER_FLAG_HOST_VISIBLE) ? allocation_info.pMappedData
                                                                 : nullptr;
    buffer->memory_type = select_memory_type(backend->vma, allocation_info.memoryType,
                                             vma_info.usage);
    const uint64_t resource_id = backend->next_resource_id++;
    initialize_buffer_handle(*out, *buffer);
    backend->buffers.emplace(resource_id, std::move(buffer));
    if (out_resource_id != nullptr) {
        *out_resource_id = resource_id;
    }
    return CAUSTICA_STATUS_OK;
}

int32_t create_storage_image(ca_backend_t handle, uint32_t width, uint32_t height,
                               uint32_t format, uint32_t extra_usage, uint64_t,
                               ca_image_handle* out) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || out == nullptr || backend->device == VK_NULL_HANDLE
            || !ensure_vma(*backend)) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    const VkFormat vk_format = static_cast<VkFormat>(format);
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | extra_usage;
    if (!format_supports(backend->physical_device, vk_format, usage)) {
        return CAUSTICA_STATUS_DECLINED;
    }
    auto image = std::make_unique<NativeImage>();
    VkImageCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = vk_format;
    ici.extent = {width, height, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = usage;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VmaAllocationCreateInfo vma_info{};
    vma_info.usage = VMA_MEMORY_USAGE_AUTO;
    VmaAllocationInfo allocation_info{};
    if (vmaCreateImage(backend->vma, &ici, &vma_info, &image->image, &image->allocation,
                       &allocation_info) != VK_SUCCESS) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    VkImageViewCreateInfo vci{};
    vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vci.image = image->image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = vk_format;
    vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount = 1;
    vci.subresourceRange.layerCount = 1;
    if (vkCreateImageView(backend->device, &vci, nullptr, &image->view) != VK_SUCCESS) {
        vmaDestroyImage(backend->vma, image->image, image->allocation);
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    image->width = width;
    image->height = height;
    image->format = vk_format;
    image->memory_type = allocation_info.memoryType;
    const int32_t transition_rc = submit_one_shot(*backend, [&image](VkCommandBuffer cmd) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image->image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &barrier);
    }, nullptr);
    if (transition_rc != CAUSTICA_STATUS_OK) {
        vkDestroyImageView(backend->device, image->view, nullptr);
        vmaDestroyImage(backend->vma, image->image, image->allocation);
        return transition_rc;
    }
    const uint64_t resource_id = backend->next_resource_id++;
    initialize_image_handle(*out, *image);
    backend->images.emplace(resource_id, std::move(image));
    return CAUSTICA_STATUS_OK;
}

int32_t create_transient_msaa_image(ca_backend_t handle, uint32_t width, uint32_t height,
                                     uint32_t format, uint32_t samples, uint64_t,
                                     ca_image_handle* out) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || out == nullptr || backend->device == VK_NULL_HANDLE
            || !ensure_vma(*backend)) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    const VkFormat vk_format = static_cast<VkFormat>(format);
    auto image = std::make_unique<NativeImage>();
    VkImageCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = vk_format;
    ici.extent = {width, height, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = static_cast<VkSampleCountFlagBits>(samples);
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VmaAllocationCreateInfo vma_info{};
    vma_info.usage = VMA_MEMORY_USAGE_AUTO;
    VmaAllocationInfo allocation_info{};
    if (vmaCreateImage(backend->vma, &ici, &vma_info, &image->image, &image->allocation,
                       &allocation_info) != VK_SUCCESS) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    VkImageViewCreateInfo vci{};
    vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vci.image = image->image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = vk_format;
    vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount = 1;
    vci.subresourceRange.layerCount = 1;
    if (vkCreateImageView(backend->device, &vci, nullptr, &image->view) != VK_SUCCESS) {
        vmaDestroyImage(backend->vma, image->image, image->allocation);
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    image->width = width;
    image->height = height;
    image->format = vk_format;
    image->memory_type = allocation_info.memoryType;
    const int32_t transition_rc = submit_one_shot(*backend, [&image](VkCommandBuffer cmd) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image->image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &barrier);
    }, nullptr);
    if (transition_rc != CAUSTICA_STATUS_OK) {
        vkDestroyImageView(backend->device, image->view, nullptr);
        vmaDestroyImage(backend->vma, image->image, image->allocation);
        return transition_rc;
    }
    const uint64_t resource_id = backend->next_resource_id++;
    initialize_image_handle(*out, *image);
    backend->images.emplace(resource_id, std::move(image));
    return CAUSTICA_STATUS_OK;
}

int32_t upload_device_local(ca_backend_t handle, uint64_t size_bytes, uint32_t usage,
                             uint64_t host_ptr, uint64_t host_bytes, uint64_t address_alignment,
                             uint64_t label_hash, ca_buffer_handle* out) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || out == nullptr || host_ptr == 0 || host_bytes == 0
            || host_bytes > size_bytes) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    uint64_t device_id = 0;
    const int32_t device_rc = create_buffer(handle, size_bytes, usage, 0, address_alignment,
                                            label_hash, out, &device_id);
    if (device_rc != CAUSTICA_STATUS_OK) {
        return device_rc;
    }
    ca_buffer_handle staging_desc{};
    uint64_t staging_id = 0;
    const int32_t staging_rc = create_buffer(handle, host_bytes,
                                             VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                             CAUSTICA_BUFFER_FLAG_HOST_VISIBLE, 0, label_hash,
                                             &staging_desc, &staging_id);
    if (staging_rc != CAUSTICA_STATUS_OK) {
        release_resource(*backend, device_id);
        std::memset(out, 0, sizeof(*out));
        return staging_rc;
    }
    std::memcpy(reinterpret_cast<void*>(staging_desc.mapped_ptr),
                reinterpret_cast<void*>(host_ptr), static_cast<size_t>(host_bytes));
    auto* buffer = reinterpret_cast<VkBuffer>(out->buffer);
    auto* staging = reinterpret_cast<VkBuffer>(staging_desc.buffer);
    const int32_t copy_rc = submit_one_shot(*backend, [buffer, staging, host_bytes](VkCommandBuffer cmd) {
        VkBufferCopy region{};
        region.srcOffset = 0;
        region.dstOffset = 0;
        region.size = host_bytes;
        vkCmdCopyBuffer(cmd, staging, buffer, 1, &region);
        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT
                | 0x200000; // VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = buffer;
        barrier.offset = 0;
        barrier.size = host_bytes;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                                     | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                     | VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 1, &barrier, 0, nullptr);
    }, nullptr);
    release_resource(*backend, staging_id);
    if (copy_rc != CAUSTICA_STATUS_OK) {
        release_resource(*backend, device_id);
        std::memset(out, 0, sizeof(*out));
        return copy_rc;
    }
    return CAUSTICA_STATUS_OK;
}

int32_t release_resource_exported(ca_backend_t handle, uint64_t handle_value) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || handle_value == 0) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    release_resource(*backend, handle_value);
    return CAUSTICA_STATUS_OK;
}

int32_t flush_buffer_range_exported(ca_backend_t handle, uint64_t buffer_handle, uint64_t offset,
                                    uint64_t length) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || backend->vma == VK_NULL_HANDLE) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    auto it = backend->buffers.find(buffer_handle);
    if (it == backend->buffers.end() || it->second->mapped == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    vmaFlushAllocation(backend->vma, it->second->allocation, offset, length);
    return CAUSTICA_STATUS_OK;
}

int32_t invalidate_buffer_range_exported(ca_backend_t handle, uint64_t buffer_handle, uint64_t offset,
                                         uint64_t length) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || backend->vma == VK_NULL_HANDLE) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    auto it = backend->buffers.find(buffer_handle);
    if (it == backend->buffers.end() || it->second->mapped == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    vmaInvalidateAllocation(backend->vma, it->second->allocation, offset, length);
    return CAUSTICA_STATUS_OK;
}

int32_t memory_type_of_exported(ca_backend_t handle, uint64_t buffer_handle, uint32_t* out) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || out == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    auto it = backend->buffers.find(buffer_handle);
    if (it == backend->buffers.end()) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    *out = it->second->memory_type;
    return CAUSTICA_STATUS_OK;
}

int32_t lookup_buffer(ca_backend_t handle, uint64_t buffer_handle, ca_buffer_handle* out) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || out == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    auto it = backend->buffers.find(buffer_handle);
    if (it == backend->buffers.end()) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    initialize_buffer_handle(*out, *it->second);
    return CAUSTICA_STATUS_OK;
}

int32_t lookup_image(ca_backend_t handle, uint64_t image_handle, ca_image_handle* out) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || out == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    auto it = backend->images.find(image_handle);
    if (it == backend->images.end()) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    initialize_image_handle(*out, *it->second);
    return CAUSTICA_STATUS_OK;
}

} // namespace caustica::renderer

extern "C" {

int32_t ca_backend_create_buffer(ca_backend_t backend, uint64_t size_bytes, uint32_t usage,
                                  uint32_t flags, uint64_t address_alignment, uint64_t label_hash,
                                  ca_buffer_handle* out) {
    return caustica::renderer::create_buffer(backend, size_bytes, usage, flags, address_alignment,
                                             label_hash, out, nullptr);
}

int32_t ca_backend_create_storage_image(ca_backend_t backend, uint32_t width, uint32_t height,
                                         uint32_t format, uint32_t extra_usage, uint64_t label_hash,
                                         ca_image_handle* out) {
    return caustica::renderer::create_storage_image(backend, width, height, format, extra_usage,
                                                    label_hash, out);
}

int32_t ca_backend_create_transient_msaa_image(ca_backend_t backend, uint32_t width, uint32_t height,
                                                uint32_t format, uint32_t samples,
                                                uint64_t label_hash, ca_image_handle* out) {
    return caustica::renderer::create_transient_msaa_image(backend, width, height, format, samples,
                                                           label_hash, out);
}

int32_t ca_backend_upload_device_local(ca_backend_t backend, uint64_t size_bytes, uint32_t usage,
                                       uint64_t host_ptr, uint64_t host_bytes,
                                       uint64_t address_alignment, uint64_t label_hash,
                                       ca_buffer_handle* out) {
    return caustica::renderer::upload_device_local(backend, size_bytes, usage, host_ptr, host_bytes,
                                                  address_alignment, label_hash, out);
}

int32_t ca_backend_release_resource(ca_backend_t backend, uint64_t handle) {
    return caustica::renderer::release_resource_exported(backend, handle);
}

int32_t ca_backend_flush_buffer_range(ca_backend_t backend, uint64_t buffer_handle, uint64_t offset,
                                       uint64_t length) {
    return caustica::renderer::flush_buffer_range_exported(backend, buffer_handle, offset, length);
}

int32_t ca_backend_invalidate_buffer_range(ca_backend_t backend, uint64_t buffer_handle, uint64_t offset,
                                           uint64_t length) {
    return caustica::renderer::invalidate_buffer_range_exported(backend, buffer_handle, offset, length);
}

int32_t ca_backend_memory_type_of(ca_backend_t backend, uint64_t buffer_handle, uint32_t* out) {
    return caustica::renderer::memory_type_of_exported(backend, buffer_handle, out);
}

int32_t ca_backend_lookup_buffer(ca_backend_t backend, uint64_t buffer_handle, ca_buffer_handle* out) {
    return caustica::renderer::lookup_buffer(backend, buffer_handle, out);
}

int32_t ca_backend_lookup_image(ca_backend_t backend, uint64_t image_handle, ca_image_handle* out) {
    return caustica::renderer::lookup_image(backend, image_handle, out);
}

} // extern "C"
