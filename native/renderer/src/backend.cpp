#include "backend_internal.hpp"

#include <cstring>
#include <new>

namespace caustica::renderer {

Backend* from_handle(ca_backend_t handle) {
    return reinterpret_cast<Backend*>(static_cast<uintptr_t>(handle));
}

ca_frame_result result(int32_t status, uint32_t commit_state, uint64_t token,
                       uint64_t diagnostic) {
    ca_frame_result out{};
    out.status = status;
    out.commit_state = commit_state;
    out.frame_token = token;
    out.diagnostic_code = diagnostic;
    return out;
}

} // namespace caustica::renderer

static_assert(sizeof(ca_device_desc) == 88);
static_assert(offsetof(ca_device_desc, device) == 24);
static_assert(offsetof(ca_device_desc, generation) == 80);
static_assert(sizeof(ca_image_ref) == 56);
static_assert(offsetof(ca_image_ref, generation) == 48);
static_assert(sizeof(ca_firefly_desc) == 136);
static_assert(offsetof(ca_firefly_desc, input) == 24);
static_assert(sizeof(ca_frame_result) == 40);

extern "C" {

uint32_t ca_get_abi_version(void) {
    return CAUSTICA_BACKEND_ABI_V2;
}

ca_backend_t ca_create_backend(void) {
    auto* backend = new (std::nothrow) caustica::renderer::Backend();
    return static_cast<ca_backend_t>(reinterpret_cast<uintptr_t>(backend));
}

void ca_destroy_backend(ca_backend_t handle) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr) {
        return;
    }
    {
        std::lock_guard lock(backend->mutex);
        caustica::renderer::destroy_firefly(*backend);
        backend->attached = false;
        backend->device = VK_NULL_HANDLE;
    }
    delete backend;
}

int32_t ca_attach_device(ca_backend_t handle, const ca_device_desc* desc) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr || desc == nullptr || desc->size != sizeof(ca_device_desc)
            || desc->abi != CAUSTICA_BACKEND_ABI_V1 || desc->instance == 0
            || desc->physical_device == 0 || desc->device == 0 || desc->graphics_queue == 0) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(backend->mutex);
    if (backend->attached && backend->device != reinterpret_cast<VkDevice>(desc->device)) {
        caustica::renderer::destroy_firefly(*backend);
    }
    backend->device_desc = *desc;
    backend->instance = reinterpret_cast<VkInstance>(desc->instance);
    backend->physical_device = reinterpret_cast<VkPhysicalDevice>(desc->physical_device);
    backend->device = reinterpret_cast<VkDevice>(desc->device);
    backend->graphics_queue = reinterpret_cast<VkQueue>(desc->graphics_queue);
    backend->compute_queue = reinterpret_cast<VkQueue>(desc->compute_queue);
    backend->generation = desc->generation;
    backend->attached = true;
    backend->reloading = false;
    backend->status = "device attached";
    if (backend->physical_device != VK_NULL_HANDLE) {
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rt_props{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR};
        VkPhysicalDeviceProperties2 props2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        props2.pNext = &rt_props;
        vkGetPhysicalDeviceProperties2(backend->physical_device, &props2);
        if (rt_props.shaderGroupHandleSize != 0 && rt_props.shaderGroupBaseAlignment != 0) {
            backend->shader_group_handle_size = rt_props.shaderGroupHandleSize;
            backend->shader_group_base_alignment = rt_props.shaderGroupBaseAlignment;
            backend->shader_group_handle_alignment = rt_props.shaderGroupHandleAlignment;
            backend->max_shader_group_stride = rt_props.maxShaderGroupStride;
        }
    }
    // Resolve RT pipeline + AS function pointers from the device.
    auto get_proc = [](VkDevice device, const char* name) {
        return vkGetDeviceProcAddr(device, name);
    };
    backend->pfnCreateRayTracingPipelinesKHR = reinterpret_cast<PFN_vkCreateRayTracingPipelinesKHR>(
            get_proc(backend->device, "vkCreateRayTracingPipelinesKHR"));
    backend->pfnGetRayTracingShaderGroupHandlesKHR = reinterpret_cast<PFN_vkGetRayTracingShaderGroupHandlesKHR>(
            get_proc(backend->device, "vkGetRayTracingShaderGroupHandlesKHR"));
    backend->pfnCmdTraceRaysKHR = reinterpret_cast<PFN_vkCmdTraceRaysKHR>(
            get_proc(backend->device, "vkCmdTraceRaysKHR"));
    backend->pfnDestroyAccelerationStructureKHR = reinterpret_cast<PFN_vkDestroyAccelerationStructureKHR>(
            get_proc(backend->device, "vkDestroyAccelerationStructureKHR"));
    backend->pfnGetAccelerationStructureBuildSizesKHR = reinterpret_cast<PFN_vkGetAccelerationStructureBuildSizesKHR>(
            get_proc(backend->device, "vkGetAccelerationStructureBuildSizesKHR"));
    backend->pfnCreateAccelerationStructureKHR = reinterpret_cast<PFN_vkCreateAccelerationStructureKHR>(
            get_proc(backend->device, "vkCreateAccelerationStructureKHR"));
    backend->pfnGetAccelerationStructureDeviceAddressKHR = reinterpret_cast<PFN_vkGetAccelerationStructureDeviceAddressKHR>(
            get_proc(backend->device, "vkGetAccelerationStructureDeviceAddressKHR"));
    return CAUSTICA_STATUS_OK;
}

void ca_detach_device(ca_backend_t handle) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr) {
        return;
    }
    std::lock_guard lock(backend->mutex);
    caustica::renderer::destroy_firefly(*backend);
    backend->attached = false;
    backend->instance = VK_NULL_HANDLE;
    backend->physical_device = VK_NULL_HANDLE;
    backend->device = VK_NULL_HANDLE;
    backend->graphics_queue = VK_NULL_HANDLE;
    backend->compute_queue = VK_NULL_HANDLE;
    backend->status = "device detached";
}

namespace caustica::renderer {

bool query_shader_group_layout(Backend& backend) {
    if (backend.physical_device == VK_NULL_HANDLE) {
        return false;
    }
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR rt_props{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR};
    VkPhysicalDeviceProperties2 props2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    props2.pNext = &rt_props;
    vkGetPhysicalDeviceProperties2(backend.physical_device, &props2);
    if (rt_props.shaderGroupHandleSize == 0 || rt_props.shaderGroupBaseAlignment == 0) {
        return false;
    }
    backend.shader_group_handle_size = rt_props.shaderGroupHandleSize;
    backend.shader_group_base_alignment = rt_props.shaderGroupBaseAlignment;
    backend.shader_group_handle_alignment = rt_props.shaderGroupHandleAlignment;
    backend.max_shader_group_stride = rt_props.maxShaderGroupStride;
    return true;
}

} // namespace caustica::renderer

int32_t ca_register_spirv(ca_backend_t handle, uint32_t shader_id, const void* bytes,
                          size_t byte_count, uint64_t content_hash) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr || bytes == nullptr || byte_count == 0 || (byte_count % 4) != 0) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    const auto* words = static_cast<const uint32_t*>(bytes);
    if (words[0] != 0x07230203u) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(backend->mutex);
    caustica::renderer::ShaderBlob blob;
    blob.words.assign(words, words + byte_count / sizeof(uint32_t));
    blob.content_hash = content_hash;
    backend->shaders[shader_id] = std::move(blob);
    if (shader_id == CAUSTICA_SHADER_FIREFLY_KILL) {
        caustica::renderer::destroy_firefly(*backend);
    }
    backend->status = "SPIR-V registered";
    return CAUSTICA_STATUS_OK;
}

ca_frame_result ca_prepare_firefly(ca_backend_t handle, const ca_firefly_desc* desc) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr || desc == nullptr || desc->size != sizeof(ca_firefly_desc)
            || desc->abi != CAUSTICA_BACKEND_ABI_V1) {
        return caustica::renderer::result(CAUSTICA_STATUS_FATAL_PRE_COMMIT,
                                          CAUSTICA_COMMIT_NONE);
    }
    std::lock_guard lock(backend->mutex);
    const int32_t status = caustica::renderer::ensure_firefly_prepared(*backend, *desc);
    const uint64_t token = status == CAUSTICA_STATUS_OK ? backend->next_frame_token++ : 0;
    return caustica::renderer::result(status,
                                      status == CAUSTICA_STATUS_OK ? CAUSTICA_COMMIT_PREPARED
                                                                   : CAUSTICA_COMMIT_NONE,
                                      token);
}

ca_frame_result ca_record_firefly(ca_backend_t handle, const ca_firefly_desc* desc) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr || desc == nullptr || desc->size != sizeof(ca_firefly_desc)
            || desc->abi != CAUSTICA_BACKEND_ABI_V1) {
        return caustica::renderer::result(CAUSTICA_STATUS_FATAL_PRE_COMMIT,
                                          CAUSTICA_COMMIT_NONE);
    }
    std::lock_guard lock(backend->mutex);
    return caustica::renderer::record_firefly(*backend, *desc);
}

void ca_resize(ca_backend_t handle, uint32_t, uint32_t, uint64_t generation) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr) {
        return;
    }
    std::lock_guard lock(backend->mutex);
    caustica::renderer::destroy_firefly(*backend);
    backend->generation = generation;
    backend->status = "resized";
}

void ca_begin_reload(ca_backend_t handle, uint64_t generation) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr) {
        return;
    }
    std::lock_guard lock(backend->mutex);
    caustica::renderer::destroy_firefly(*backend);
    backend->reloading = true;
    backend->generation = generation;
    backend->status = "reload begun";
}

void ca_end_reload(ca_backend_t handle, uint64_t generation) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr) {
        return;
    }
    std::lock_guard lock(backend->mutex);
    backend->reloading = false;
    backend->generation = generation;
    backend->status = "reload complete";
}

void ca_invalidate_history(ca_backend_t) {
}

uint64_t ca_native_firefly_dispatch_count(ca_backend_t handle) {
    auto* backend = caustica::renderer::from_handle(handle);
    if (backend == nullptr) {
        return 0;
    }
    std::lock_guard lock(backend->mutex);
    return backend->firefly_dispatch_count;
}

const char* ca_get_status(ca_backend_t handle) {
    auto* backend = caustica::renderer::from_handle(handle);
    return backend != nullptr ? backend->status.c_str() : "invalid backend";
}

} // extern "C"
