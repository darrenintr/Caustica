#include "backend_internal.hpp"

#include <array>
#include <cstring>
#include <vector>

namespace caustica::renderer {
namespace {

constexpr uint32_t RING = 6;

struct WorldPipeline {
    bool alive = false;
    ca_world_pipeline_desc config{};
    VkPipelineLayout pipeline_layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkShaderModule rgen_module = VK_NULL_HANDLE;
    std::vector<VkShaderModule> miss_modules;
    VkShaderModule chit_module = VK_NULL_HANDLE;
    VkShaderModule ahit_module = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptor_set_layout = VK_NULL_HANDLE;
    VkDescriptorPool descriptor_pool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> descriptor_sets;
    VkDescriptorSetLayout bindless_layout = VK_NULL_HANDLE;
    VkDescriptorPool bindless_pool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> bindless_sets;
    std::vector<uint64_t> bindless_views;
    std::vector<uint64_t> bindless_samplers;
    int current_set = 0;
    VkImageView storage_image_view = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<std::pair<uint64_t, bool>> extra_storage_views;
    std::vector<std::pair<uint64_t, uint64_t>> extra_storage_buffers;
    std::vector<uint64_t> sampler_views;
    std::vector<uint64_t> samplers;
    VkBuffer sbt = VK_NULL_HANDLE;
    VmaAllocation sbt_allocation = VK_NULL_HANDLE;
    void* sbt_mapped = nullptr;
    VkDeviceAddress sbt_device_address = 0;
    VkDeviceSize sbt_stride = 0;
    int miss_count = 0;
    int hit_group_count = 0;
    int push_constant_size = 0;
    int push_constant_stages = 0;
    bool bindless_initialized = false;
    uint64_t generation = 0;
};

WorldPipeline* as_pipeline(ca_pipeline_t handle) {
    return reinterpret_cast<WorldPipeline*>(static_cast<uintptr_t>(handle));
}

ca_pipeline_t make_handle(WorldPipeline* pipeline) {
    return static_cast<ca_pipeline_t>(reinterpret_cast<uintptr_t>(pipeline));
}

uint32_t align_up(uint32_t value, uint32_t alignment) {
    return (value + alignment - 1U) & ~(alignment - 1U);
}

} // namespace

ca_pipeline_t create_world_pipeline(Backend& backend, const ca_world_pipeline_desc& config) {
    if (!backend.attached || backend.device == VK_NULL_HANDLE || !caustica::renderer::ensure_vma(backend)) {
        return 0;
    }
    if ((backend.device_desc.driver_flags & CAUSTICA_DRIVER_RADV) != 0) {
        // Mesa 26.x RADV on Navi3x SIGSEGVs inside vkCreateRayTracingPipelinesKHR
        // even with the most stripped shader path. Defer to Java LWJGL for RT tracing
        // until upstream Mesa fixes the driver crash. Firefly/denoise/composite
        // still run natively through the C++ backend.
        fprintf(stderr, "[caustica_native] RADV detected: skipping native RT pipeline (driver crash);"
                " RT trace will run on Java LWJGL\n");
        return 0;
    }
    fprintf(stderr, "[caustica_native] create_world_pipeline: rgen=%u miss=%u chit=%u ahit=%u\n",
            config.rgen_shader_id, config.miss_shader_count, config.chit_shader_id, config.ahit_shader_id);
    fprintf(stderr, "[caustica_native] create_world_pipeline: shaders in map = %zu\n",
            backend.shaders.size());
    for (const auto& kv : backend.shaders) {
        fprintf(stderr, "[caustica_native]   shader_id=%u words=%zu\n",
                kv.first, kv.second.words.size());
    }
    auto& shaders = backend.shaders;
    auto rgen_it = shaders.find(config.rgen_shader_id);
    if (rgen_it == shaders.end() || rgen_it->second.words.empty()) {
        return 0;
    }
    std::vector<std::vector<uint32_t>> miss_spirv;
    miss_spirv.reserve(config.miss_shader_count);
    for (uint32_t i = 0; i < config.miss_shader_count; ++i) {
        const uint32_t id = config.miss_shader_ids[i];
        auto it = shaders.find(id);
        if (it == shaders.end() || it->second.words.empty()) {
            return 0;
        }
        miss_spirv.push_back(it->second.words);
    }
    auto chit_it = shaders.find(config.chit_shader_id);
    if (chit_it == shaders.end() || chit_it->second.words.empty()) {
        return 0;
    }
    VkShaderModule ahit_module = VK_NULL_HANDLE;
    if (config.ahit_shader_id != 0) {
        auto ahit_it = shaders.find(config.ahit_shader_id);
        if (ahit_it == shaders.end() || ahit_it->second.words.empty()) {
            return 0;
        }
    }
    auto pipeline = std::make_unique<WorldPipeline>();
    pipeline->config = config;
    pipeline->generation = backend.generation;
    pipeline->push_constant_size = config.push_constant_size;
    pipeline->miss_count = static_cast<int>(config.miss_shader_count);
    pipeline->hit_group_count = 4 * 2 + 4 * 2; // TERRAIN_BUCKETS * 2 (radiance + shadow) + ENTITY buckets
    pipeline->push_constant_stages = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
            | VK_SHADER_STAGE_MISS_BIT_KHR;
    if (config.ahit_shader_id != 0) {
        pipeline->push_constant_stages |= VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    }

    auto load_module = [&](const std::vector<uint32_t>& words) {
        VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        info.codeSize = words.size() * sizeof(uint32_t);
        info.pCode = words.data();
        VkShaderModule module = VK_NULL_HANDLE;
        if (vkCreateShaderModule(backend.device, &info, nullptr, &module) != VK_SUCCESS) {
            return VkShaderModule{VK_NULL_HANDLE};
        }
        return module;
    };

    pipeline->rgen_module = load_module(rgen_it->second.words);
    pipeline->miss_modules.reserve(miss_spirv.size());
    for (const auto& words : miss_spirv) {
        pipeline->miss_modules.push_back(load_module(words));
    }
    pipeline->chit_module = load_module(chit_it->second.words);
    if (config.ahit_shader_id != 0) {
        auto ahit_it = shaders.find(config.ahit_shader_id);
        pipeline->ahit_module = load_module(ahit_it->second.words);
    }
    fprintf(stderr, "[caustica_native] create_world_pipeline: rgen_module=%p miss_modules=%zu chit=%p ahit=%p\n",
            reinterpret_cast<void*>(pipeline->rgen_module), pipeline->miss_modules.size(),
            reinterpret_cast<void*>(pipeline->chit_module), reinterpret_cast<void*>(pipeline->ahit_module));
    if (pipeline->rgen_module == VK_NULL_HANDLE || pipeline->chit_module == VK_NULL_HANDLE) {
        return 0;
    }
    for (auto m : pipeline->miss_modules) {
        if (m == VK_NULL_HANDLE) {
            return 0;
        }
    }

    const uint32_t material_base = 3U + config.extra_storage_count;
    const uint32_t material_count = (config.with_block_material_atlas ? 2U : 0U)
            + (config.with_sky_atlas ? 1U : 0U);

    VkDescriptorSetLayoutCreateInfo layout_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    std::vector<VkDescriptorSetLayoutBinding> bindings;
    bindings.reserve(3 + config.extra_storage_count + material_count);
    VkDescriptorSetLayoutBinding tl{};
    tl.binding = 0;
    tl.descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
    tl.descriptorCount = 1;
    tl.stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    bindings.push_back(tl);
    VkDescriptorSetLayoutBinding out{};
    out.binding = 1;
    out.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    out.descriptorCount = 1;
    out.stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    bindings.push_back(out);
    VkDescriptorSetLayoutBinding atlas{};
    if (config.with_atlas_sampler) {
        atlas.binding = 2;
        atlas.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        atlas.descriptorCount = 1;
        atlas.stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
        if (config.ahit_shader_id != 0) {
            atlas.stageFlags |= VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
        }
        bindings.push_back(atlas);
    }
    for (uint32_t e = 0; e < config.extra_storage_count; ++e) {
        VkDescriptorSetLayoutBinding b{};
        b.binding = 3 + e;
        b.descriptorCount = 1;
        b.stageFlags = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
        b.descriptorType = (config.extra_storage_buffer_slots & (1U << e)) != 0
                ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings.push_back(b);
    }
    if (config.with_block_material_atlas) {
        VkDescriptorSetLayoutBinding spec{};
        spec.binding = material_base;
        spec.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        spec.descriptorCount = 1;
        spec.stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
        bindings.push_back(spec);
        VkDescriptorSetLayoutBinding normal{};
        normal.binding = material_base + 1;
        normal.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        normal.descriptorCount = 1;
        normal.stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
        bindings.push_back(normal);
    }
    if (config.with_sky_atlas) {
        VkDescriptorSetLayoutBinding sky{};
        sky.binding = material_base + material_count - 1;
        sky.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        sky.descriptorCount = 1;
        sky.stageFlags = VK_SHADER_STAGE_MISS_BIT_KHR;
        bindings.push_back(sky);
    }
    layout_info.bindingCount = static_cast<uint32_t>(bindings.size());
    layout_info.pBindings = bindings.data();
    if (vkCreateDescriptorSetLayout(backend.device, &layout_info, nullptr,
                                     &pipeline->descriptor_set_layout) != VK_SUCCESS) {
        return 0;
    }

    pipeline->extra_storage_views.assign(config.extra_storage_count, {0, false});
    pipeline->extra_storage_buffers.assign(config.extra_storage_count, {0, 0});
    pipeline->sampler_views.assign(bindings.size(), 0);
    pipeline->samplers.assign(bindings.size(), 0);

    uint32_t pool_combined = (config.with_atlas_sampler ? 1U : 0U)
            + (config.with_block_material_atlas ? 2U : 0U)
            + (config.with_sky_atlas ? 1U : 0U);
    uint32_t extra_image = 0;
    uint32_t extra_buffer = 0;
    for (uint32_t e = 0; e < config.extra_storage_count; ++e) {
        if ((config.extra_storage_buffer_slots & (1U << e)) != 0) {
            extra_buffer += 1;
        } else {
            extra_image += 1;
        }
    }
    uint32_t pool_size_count = 2 + (pool_combined > 0 ? 1U : 0U) + (extra_buffer > 0 ? 1U : 0U);
    std::vector<VkDescriptorPoolSize> pool_sizes(pool_size_count);
    uint32_t idx = 0;
    pool_sizes[idx++] = {VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, RING};
    pool_sizes[idx++] = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RING * (1 + extra_image)};
    if (pool_combined > 0) {
        pool_sizes[idx++] = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, RING * pool_combined};
    }
    if (extra_buffer > 0) {
        pool_sizes[idx++] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, RING * extra_buffer};
    }
    VkDescriptorPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool_info.maxSets = RING;
    pool_info.poolSizeCount = static_cast<uint32_t>(pool_sizes.size());
    pool_info.pPoolSizes = pool_sizes.data();
    if (vkCreateDescriptorPool(backend.device, &pool_info, nullptr,
                                &pipeline->descriptor_pool) != VK_SUCCESS) {
        return 0;
    }
    std::vector<VkDescriptorSetLayout> layouts(RING, pipeline->descriptor_set_layout);
    VkDescriptorSetAllocateInfo allocate_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocate_info.descriptorPool = pipeline->descriptor_pool;
    allocate_info.descriptorSetCount = RING;
    allocate_info.pSetLayouts = layouts.data();
    pipeline->descriptor_sets.resize(RING);
    if (vkAllocateDescriptorSets(backend.device, &allocate_info, pipeline->descriptor_sets.data())
            != VK_SUCCESS) {
        return 0;
    }

    if (config.bindless_capacity > 0) {
        std::vector<VkDescriptorSetLayoutBinding> bl_bindings(3);
        for (uint32_t b = 0; b < 3; ++b) {
            bl_bindings[b].binding = b;
            bl_bindings[b].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bl_bindings[b].descriptorCount = config.bindless_capacity;
            bl_bindings[b].stageFlags = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
            if (config.ahit_shader_id != 0) {
                bl_bindings[b].stageFlags |= VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
            }
        }
        VkDescriptorSetLayoutCreateInfo bl_layout{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        bl_layout.bindingCount = 3;
        bl_layout.pBindings = bl_bindings.data();
        if (vkCreateDescriptorSetLayout(backend.device, &bl_layout, nullptr,
                                        &pipeline->bindless_layout) != VK_SUCCESS) {
            return 0;
        }
        VkDescriptorPoolSize bl_pool{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                     3 * config.bindless_capacity * RING};
        VkDescriptorPoolCreateInfo bl_pool_info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        bl_pool_info.maxSets = RING;
        bl_pool_info.poolSizeCount = 1;
        bl_pool_info.pPoolSizes = &bl_pool;
        if (vkCreateDescriptorPool(backend.device, &bl_pool_info, nullptr,
                                    &pipeline->bindless_pool) != VK_SUCCESS) {
            return 0;
        }
        std::vector<VkDescriptorSetLayout> bl_layouts(RING, pipeline->bindless_layout);
        VkDescriptorSetAllocateInfo bl_allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        bl_allocate.descriptorPool = pipeline->bindless_pool;
        bl_allocate.descriptorSetCount = RING;
        bl_allocate.pSetLayouts = bl_layouts.data();
        pipeline->bindless_sets.resize(RING);
        if (vkAllocateDescriptorSets(backend.device, &bl_allocate, pipeline->bindless_sets.data())
                != VK_SUCCESS) {
            return 0;
        }
        pipeline->bindless_views.assign(3 * config.bindless_capacity, 0);
        pipeline->bindless_samplers.assign(3 * config.bindless_capacity, 0);
    }

    VkPushConstantRange push_range{};
    push_range.stageFlags = static_cast<VkShaderStageFlags>(pipeline->push_constant_stages);
    push_range.offset = 0;
    push_range.size = config.push_constant_size;

    VkPipelineLayoutCreateInfo pipeline_layout_info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    if (config.bindless_capacity > 0) {
        VkDescriptorSetLayout set_layouts[2] = {pipeline->descriptor_set_layout, pipeline->bindless_layout};
        pipeline_layout_info.setLayoutCount = 2;
        pipeline_layout_info.pSetLayouts = set_layouts;
    } else {
        pipeline_layout_info.setLayoutCount = 1;
        pipeline_layout_info.pSetLayouts = &pipeline->descriptor_set_layout;
    }
    if (config.push_constant_size > 0) {
        pipeline_layout_info.pushConstantRangeCount = 1;
        pipeline_layout_info.pPushConstantRanges = &push_range;
    }
    if (vkCreatePipelineLayout(backend.device, &pipeline_layout_info, nullptr,
                                &pipeline->pipeline_layout) != VK_SUCCESS) {
        return 0;
    }

    const uint32_t group_count = 1 + static_cast<uint32_t>(miss_spirv.size())
            + static_cast<uint32_t>(pipeline->hit_group_count);
    const uint32_t stage_count = 2 + static_cast<uint32_t>(miss_spirv.size())
            + (config.ahit_shader_id != 0 ? 1U : 0U);
    std::vector<VkPipelineShaderStageCreateInfo> stages(stage_count);
    VkPipelineShaderStageCreateInfo stage_template{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    const char* entry_name = "main";
    stages[0] = stage_template;
    stages[0].stage = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    stages[0].module = pipeline->rgen_module;
    stages[0].pName = entry_name;
    for (uint32_t m = 0; m < miss_spirv.size(); ++m) {
        stages[1 + m] = stage_template;
        stages[1 + m].stage = VK_SHADER_STAGE_MISS_BIT_KHR;
        stages[1 + m].module = pipeline->miss_modules[m];
        stages[1 + m].pName = entry_name;
    }
    const uint32_t chit_stage = 1 + static_cast<uint32_t>(miss_spirv.size());
    stages[chit_stage] = stage_template;
    stages[chit_stage].stage = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
    stages[chit_stage].module = pipeline->chit_module;
    stages[chit_stage].pName = entry_name;
    uint32_t ahit_stage = 0;
    bool has_ahit = config.ahit_shader_id != 0;
    if (has_ahit) {
        ahit_stage = chit_stage + 1;
        stages[ahit_stage] = stage_template;
        stages[ahit_stage].stage = VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
        stages[ahit_stage].module = pipeline->ahit_module;
        stages[ahit_stage].pName = entry_name;
    }

    std::vector<VkRayTracingShaderGroupCreateInfoKHR> groups(group_count);
    groups[0].sType = VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR;
    groups[0].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR;
    groups[0].generalShader = 0;
    groups[0].closestHitShader = VK_SHADER_UNUSED_KHR;
    groups[0].anyHitShader = VK_SHADER_UNUSED_KHR;
    groups[0].intersectionShader = VK_SHADER_UNUSED_KHR;
    for (uint32_t m = 0; m < miss_spirv.size(); ++m) {
        groups[1 + m].sType = VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR;
        groups[1 + m].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR;
        groups[1 + m].generalShader = 1 + m;
        groups[1 + m].closestHitShader = VK_SHADER_UNUSED_KHR;
        groups[1 + m].anyHitShader = VK_SHADER_UNUSED_KHR;
        groups[1 + m].intersectionShader = VK_SHADER_UNUSED_KHR;
    }
    const uint32_t hit_group_idx = 1 + static_cast<uint32_t>(miss_spirv.size());
    for (uint32_t h = 0; h < static_cast<uint32_t>(pipeline->hit_group_count); ++h) {
        groups[hit_group_idx + h].sType = VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR;
        groups[hit_group_idx + h].type = VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR;
        groups[hit_group_idx + h].closestHitShader = chit_stage;
        groups[hit_group_idx + h].anyHitShader = has_ahit ? ahit_stage : VK_SHADER_UNUSED_KHR;
        groups[hit_group_idx + h].intersectionShader = VK_SHADER_UNUSED_KHR;
    }

    VkRayTracingPipelineCreateInfoKHR rt_info{VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR};
    rt_info.flags = (config.with_opacity_micromap != 0)
            ? VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT : 0;
    rt_info.stageCount = static_cast<uint32_t>(stages.size());
    rt_info.pStages = stages.data();
    rt_info.groupCount = group_count;
    rt_info.pGroups = groups.data();
    rt_info.maxPipelineRayRecursionDepth = 1;
    rt_info.layout = pipeline->pipeline_layout;
    if (backend.pfnCreateRayTracingPipelinesKHR(backend.device, VK_NULL_HANDLE, VK_NULL_HANDLE, 1,
                                               &rt_info, nullptr, &pipeline->pipeline) != VK_SUCCESS) {
        fprintf(stderr, "[caustica_native] create_world_pipeline: vkCreateRayTracingPipelinesKHR FAILED\n");
        return 0;
    }
    fprintf(stderr, "[caustica_native] create_world_pipeline: pipeline OK\n");
    for (auto& stage : stages) {
        (void) stage;
    }
    if (pipeline->rgen_module != VK_NULL_HANDLE) {
        vkDestroyShaderModule(backend.device, pipeline->rgen_module, nullptr);
        pipeline->rgen_module = VK_NULL_HANDLE;
    }
    for (auto& m : pipeline->miss_modules) {
        if (m != VK_NULL_HANDLE) {
            vkDestroyShaderModule(backend.device, m, nullptr);
        }
    }
    pipeline->miss_modules.clear();
    if (pipeline->chit_module != VK_NULL_HANDLE) {
        vkDestroyShaderModule(backend.device, pipeline->chit_module, nullptr);
        pipeline->chit_module = VK_NULL_HANDLE;
    }
    if (pipeline->ahit_module != VK_NULL_HANDLE) {
        vkDestroyShaderModule(backend.device, pipeline->ahit_module, nullptr);
        pipeline->ahit_module = VK_NULL_HANDLE;
    }

    const uint32_t handle_size = backend.shader_group_handle_size;
    const uint32_t stride = align_up(handle_size, std::max(backend.shader_group_base_alignment,
                                                          backend.shader_group_handle_alignment));
    if (stride > static_cast<uint32_t>(backend.max_shader_group_stride)) {
        return 0;
    }
    pipeline->sbt_stride = stride;
    std::vector<uint8_t> sbt_data(group_count * stride);
    std::vector<uint8_t> handles(group_count * handle_size);
    if (backend.pfnGetRayTracingShaderGroupHandlesKHR(backend.device, pipeline->pipeline, 0,
                                                    group_count, handles.size(),
                                                    handles.data()) != VK_SUCCESS) {
        return 0;
    }
    for (uint32_t g = 0; g < group_count; ++g) {
        std::memcpy(sbt_data.data() + g * stride, handles.data() + g * handle_size, handle_size);
    }
    VkBufferCreateInfo buf_info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    buf_info.size = sbt_data.size();
    buf_info.usage = VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;
    buf_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo alloc_info{};
    alloc_info.usage = VMA_MEMORY_USAGE_AUTO;
    alloc_info.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
            | VMA_ALLOCATION_CREATE_MAPPED_BIT;
    VmaAllocationInfo allocation_info{};
    if (vmaCreateBufferWithAlignment(backend.vma, &buf_info, &alloc_info, /*minAlignment=*/256,
                                    &pipeline->sbt, &pipeline->sbt_allocation,
                                    &allocation_info) != VK_SUCCESS) {
        return 0;
    }
    pipeline->sbt_mapped = allocation_info.pMappedData;
    std::memcpy(pipeline->sbt_mapped, sbt_data.data(), sbt_data.size());
    vmaFlushAllocation(backend.vma, pipeline->sbt_allocation, 0, sbt_data.size());
    VkBufferDeviceAddressInfo addr_info{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};
    addr_info.buffer = pipeline->sbt;
    pipeline->sbt_device_address = vkGetBufferDeviceAddress(backend.device, &addr_info);

    pipeline->alive = true;
    return make_handle(pipeline.release());
}

void destroy_world_pipeline(Backend& backend, WorldPipeline* pipeline) {
    if (pipeline == nullptr) {
        return;
    }
    if (pipeline->pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(backend.device, pipeline->pipeline, nullptr);
    }
    if (pipeline->pipeline_layout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(backend.device, pipeline->pipeline_layout, nullptr);
    }
    if (pipeline->sbt != VK_NULL_HANDLE && backend.vma != VK_NULL_HANDLE
            && pipeline->sbt_allocation != VK_NULL_HANDLE) {
        vmaDestroyBuffer(backend.vma, pipeline->sbt, pipeline->sbt_allocation);
    }
    if (pipeline->descriptor_pool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(backend.device, pipeline->descriptor_pool, nullptr);
    }
    if (pipeline->descriptor_set_layout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(backend.device, pipeline->descriptor_set_layout, nullptr);
    }
    if (pipeline->bindless_pool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(backend.device, pipeline->bindless_pool, nullptr);
    }
    if (pipeline->bindless_layout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(backend.device, pipeline->bindless_layout, nullptr);
    }
    delete pipeline;
}

int32_t world_set_tlas(Backend& backend, WorldPipeline& pipeline, uint64_t tlas_handle) {
    if (pipeline.descriptor_sets.empty()) {
        return CAUSTICA_STATUS_DECLINED;
    }
    pipeline.current_set = (pipeline.current_set + 1) % RING;
    VkWriteDescriptorSetAccelerationStructureKHR as_write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR};
    as_write.accelerationStructureCount = 1;
    as_write.pAccelerationStructures = reinterpret_cast<const VkAccelerationStructureKHR*>(&tlas_handle);
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.pNext = &as_write;
    write.dstSet = pipeline.descriptor_sets[pipeline.current_set];
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
    vkUpdateDescriptorSets(backend.device, 1, &write, 0, nullptr);
    return CAUSTICA_STATUS_OK;
}

int32_t world_set_storage_image(Backend& backend, WorldPipeline& pipeline, uint64_t image_view) {
    if (pipeline.descriptor_sets.empty()) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const VkImageView view = reinterpret_cast<VkImageView>(image_view);
    if (pipeline.storage_image_view == view) {
        return CAUSTICA_STATUS_OK;
    }
    VkDescriptorImageInfo img_info{};
    img_info.imageView = view;
    img_info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    std::vector<VkWriteDescriptorSet> writes(RING);
    for (uint32_t i = 0; i < RING; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = pipeline.descriptor_sets[i];
        writes[i].dstBinding = 1;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = &img_info;
    }
    vkUpdateDescriptorSets(backend.device, RING, writes.data(), 0, nullptr);
    pipeline.storage_image_view = view;
    return CAUSTICA_STATUS_OK;
}

int32_t world_set_extra_storage_image(Backend& backend, WorldPipeline& pipeline, uint32_t slot,
                                     uint64_t image_view) {
    if (slot >= pipeline.config.extra_storage_count) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if ((pipeline.config.extra_storage_buffer_slots & (1U << slot)) != 0) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const VkImageView view = reinterpret_cast<VkImageView>(image_view);
    if (pipeline.extra_storage_views[slot].first == reinterpret_cast<uint64_t>(view)
            && !pipeline.extra_storage_views[slot].second) {
        return CAUSTICA_STATUS_OK;
    }
    VkDescriptorImageInfo img_info{};
    img_info.imageView = view;
    img_info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    std::vector<VkWriteDescriptorSet> writes(RING);
    for (uint32_t i = 0; i < RING; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = pipeline.descriptor_sets[i];
        writes[i].dstBinding = 3 + slot;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = &img_info;
    }
    vkUpdateDescriptorSets(backend.device, RING, writes.data(), 0, nullptr);
    pipeline.extra_storage_views[slot] = {reinterpret_cast<uint64_t>(view), false};
    return CAUSTICA_STATUS_OK;
}

int32_t world_set_extra_storage_buffer(Backend& backend, WorldPipeline& pipeline, uint32_t slot,
                                      uint64_t buffer_handle, uint64_t size_bytes) {
    if (slot >= pipeline.config.extra_storage_count) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if ((pipeline.config.extra_storage_buffer_slots & (1U << slot)) == 0) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if (pipeline.extra_storage_buffers[slot].first == buffer_handle
            && pipeline.extra_storage_buffers[slot].second == size_bytes) {
        return CAUSTICA_STATUS_OK;
    }
    VkDescriptorBufferInfo buf_info{};
    buf_info.buffer = reinterpret_cast<VkBuffer>(buffer_handle);
    buf_info.offset = 0;
    buf_info.range = size_bytes;
    std::vector<VkWriteDescriptorSet> writes(RING);
    for (uint32_t i = 0; i < RING; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = pipeline.descriptor_sets[i];
        writes[i].dstBinding = 3 + slot;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &buf_info;
    }
    vkUpdateDescriptorSets(backend.device, RING, writes.data(), 0, nullptr);
    pipeline.extra_storage_buffers[slot] = {buffer_handle, size_bytes};
    return CAUSTICA_STATUS_OK;
}

int32_t write_combined(Backend& backend, WorldPipeline& pipeline, uint32_t binding,
                       uint64_t image_view, uint64_t sampler) {
    if (pipeline.descriptor_sets.empty()) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if (binding == 0 || binding >= pipeline.sampler_views.size()) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const VkImageView view = reinterpret_cast<VkImageView>(image_view);
    const VkSampler smp = reinterpret_cast<VkSampler>(sampler);
    if (pipeline.sampler_views[binding] == reinterpret_cast<uint64_t>(view)
            && pipeline.samplers[binding] == reinterpret_cast<uint64_t>(smp)) {
        return CAUSTICA_STATUS_OK;
    }
    VkDescriptorImageInfo img_info{};
    img_info.sampler = smp;
    img_info.imageView = view;
    img_info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    std::vector<VkWriteDescriptorSet> writes(RING);
    for (uint32_t i = 0; i < RING; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = pipeline.descriptor_sets[i];
        writes[i].dstBinding = binding;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[i].pImageInfo = &img_info;
    }
    vkUpdateDescriptorSets(backend.device, RING, writes.data(), 0, nullptr);
    pipeline.sampler_views[binding] = reinterpret_cast<uint64_t>(view);
    pipeline.samplers[binding] = reinterpret_cast<uint64_t>(smp);
    return CAUSTICA_STATUS_OK;
}

int32_t world_set_atlas_sampler(Backend& backend, WorldPipeline& pipeline, uint64_t view,
                                uint64_t sampler) {
    if (!pipeline.config.with_atlas_sampler) {
        return CAUSTICA_STATUS_DECLINED;
    }
    return write_combined(backend, pipeline, 2, view, sampler);
}

int32_t world_set_block_spec_atlas(Backend& backend, WorldPipeline& pipeline, uint64_t view,
                                    uint64_t sampler) {
    if (!pipeline.config.with_block_material_atlas) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const uint32_t binding = 3 + pipeline.config.extra_storage_count;
    return write_combined(backend, pipeline, binding, view, sampler);
}

int32_t world_set_block_normal_atlas(Backend& backend, WorldPipeline& pipeline, uint64_t view,
                                      uint64_t sampler) {
    if (!pipeline.config.with_block_material_atlas) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const uint32_t binding = 3 + pipeline.config.extra_storage_count + 1;
    return write_combined(backend, pipeline, binding, view, sampler);
}

int32_t world_set_sky_atlas(Backend& backend, WorldPipeline& pipeline, uint64_t view,
                             uint64_t sampler) {
    if (!pipeline.config.with_sky_atlas) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const uint32_t binding = 3 + pipeline.config.extra_storage_count
            + (pipeline.config.with_block_material_atlas ? 2U : 0U);
    return write_combined(backend, pipeline, binding, view, sampler);
}

int32_t world_init_bindless_fallback(Backend& backend, WorldPipeline& pipeline, uint64_t view,
                                      uint64_t sampler) {
    if (pipeline.bindless_sets.empty()) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if (pipeline.bindless_views.empty()) {
        return CAUSTICA_STATUS_OK;
    }
    const VkImageView v = reinterpret_cast<VkImageView>(view);
    const VkSampler s = reinterpret_cast<VkSampler>(sampler);
    std::fill(pipeline.bindless_views.begin(), pipeline.bindless_views.end(), reinterpret_cast<uint64_t>(v));
    std::fill(pipeline.bindless_samplers.begin(), pipeline.bindless_samplers.end(), reinterpret_cast<uint64_t>(s));
    for (uint32_t binding = 0; binding < 3; ++binding) {
        VkDescriptorImageInfo img_info{};
        img_info.sampler = s;
        img_info.imageView = v;
        img_info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet = pipeline.bindless_sets[pipeline.current_set];
        write.dstBinding = binding;
        write.descriptorCount = pipeline.config.bindless_capacity;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &img_info;
        vkUpdateDescriptorSets(backend.device, 1, &write, 0, nullptr);
    }
    pipeline.bindless_initialized = true;
    return CAUSTICA_STATUS_OK;
}

int32_t world_set_bindless_texture(Backend& backend, WorldPipeline& pipeline, uint32_t binding,
                                    uint32_t slot, uint64_t image_view, uint64_t sampler) {
    if (binding >= 3 || slot >= pipeline.config.bindless_capacity
            || pipeline.bindless_sets.empty()) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if (!pipeline.bindless_initialized) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const uint32_t index = binding * pipeline.config.bindless_capacity + slot;
    const VkImageView view = reinterpret_cast<VkImageView>(image_view);
    const VkSampler smp = reinterpret_cast<VkSampler>(sampler);
    pipeline.bindless_views[index] = reinterpret_cast<uint64_t>(view);
    pipeline.bindless_samplers[index] = reinterpret_cast<uint64_t>(smp);
    VkDescriptorImageInfo img_info{};
    img_info.sampler = smp;
    img_info.imageView = view;
    img_info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = pipeline.bindless_sets[pipeline.current_set];
    write.dstBinding = binding;
    write.dstArrayElement = slot;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &img_info;
    vkUpdateDescriptorSets(backend.device, 1, &write, 0, nullptr);
    return CAUSTICA_STATUS_OK;
}

int32_t world_trace(Backend& backend, WorldPipeline& pipeline, uint64_t command_buffer,
                     uint32_t width, uint32_t height, uint64_t push_constants_ptr,
                     uint32_t push_constants_size) {
    if (!pipeline.alive) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    const VkCommandBuffer cmd = reinterpret_cast<VkCommandBuffer>(command_buffer);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipeline);
    if (!pipeline.bindless_sets.empty()) {
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipeline_layout,
                                0, 1, &pipeline.descriptor_sets[pipeline.current_set], 0, nullptr);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipeline_layout,
                                1, 1, &pipeline.bindless_sets[pipeline.current_set], 0, nullptr);
    } else {
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline.pipeline_layout,
                                0, 1, &pipeline.descriptor_sets[pipeline.current_set], 0, nullptr);
    }
    if (push_constants_size > 0 && push_constants_ptr != 0) {
        vkCmdPushConstants(cmd, pipeline.pipeline_layout,
                            static_cast<VkShaderStageFlags>(pipeline.push_constant_stages), 0,
                            push_constants_size,
                            reinterpret_cast<const void*>(push_constants_ptr));
    }
    VkMemoryBarrier mem[2]{};
    mem[0].sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mem[0].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    mem[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | 0x200000;
    mem[1].sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mem[1].srcAccessMask = 0x200000;
    mem[1].dstAccessMask = 0x200000 | VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT | 0x400000, 0x400000, 0, 2, mem, 0, nullptr,
                         0, nullptr);
    VkStridedDeviceAddressRegionKHR raygen{};
    raygen.deviceAddress = pipeline.sbt_device_address;
    raygen.stride = pipeline.sbt_stride;
    raygen.size = pipeline.sbt_stride;
    VkStridedDeviceAddressRegionKHR miss_region{};
    miss_region.deviceAddress = pipeline.sbt_device_address + pipeline.sbt_stride;
    miss_region.stride = pipeline.sbt_stride;
    miss_region.size = static_cast<VkDeviceSize>(pipeline.miss_count) * pipeline.sbt_stride;
    VkStridedDeviceAddressRegionKHR hit_region{};
    hit_region.deviceAddress = pipeline.sbt_device_address
            + static_cast<VkDeviceSize>(1 + pipeline.miss_count) * pipeline.sbt_stride;
    hit_region.stride = pipeline.sbt_stride;
    hit_region.size = static_cast<VkDeviceSize>(pipeline.hit_group_count) * pipeline.sbt_stride;
    VkStridedDeviceAddressRegionKHR callable_region{};
    backend.pfnCmdTraceRaysKHR(cmd, &raygen, &miss_region, &hit_region, &callable_region, width,
                                 height, 1);
    return CAUSTICA_STATUS_OK;
}

} // namespace caustica::renderer

extern "C" {

ca_pipeline_t ca_backend_create_world_pipeline(ca_backend_t backend,
                                                 const ca_world_pipeline_desc* desc) {
    auto* state = caustica::renderer::from_handle(backend);
    if (state == nullptr || desc == nullptr) {
        return 0;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::create_world_pipeline(*state, *desc);
}

void ca_backend_destroy_world_pipeline(ca_backend_t backend, ca_pipeline_t pipeline) {
    auto* state = caustica::renderer::from_handle(backend);
    if (state == nullptr) {
        return;
    }
    std::lock_guard lock(state->mutex);
    caustica::renderer::destroy_world_pipeline(*state, caustica::renderer::as_pipeline(pipeline));
}

int32_t ca_backend_world_set_tlas(ca_backend_t backend, ca_pipeline_t pipeline,
                                  uint64_t tlas_handle) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_tlas(*state, *pipe, tlas_handle);
}

int32_t ca_backend_world_set_storage_image(ca_backend_t backend, ca_pipeline_t pipeline,
                                            uint64_t image_view) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_storage_image(*state, *pipe, image_view);
}

int32_t ca_backend_world_set_extra_storage_image(ca_backend_t backend, ca_pipeline_t pipeline,
                                                 uint32_t slot, uint64_t image_view) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_extra_storage_image(*state, *pipe, slot, image_view);
}

int32_t ca_backend_world_set_extra_storage_buffer(ca_backend_t backend, ca_pipeline_t pipeline,
                                                  uint32_t slot, uint64_t buffer_handle,
                                                  uint64_t size_bytes) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_extra_storage_buffer(*state, *pipe, slot, buffer_handle,
                                                            size_bytes);
}

int32_t ca_backend_world_set_atlas_sampler(ca_backend_t backend, ca_pipeline_t pipeline,
                                           uint64_t image_view, uint64_t sampler) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_atlas_sampler(*state, *pipe, image_view, sampler);
}

int32_t ca_backend_world_set_block_spec_atlas(ca_backend_t backend, ca_pipeline_t pipeline,
                                                uint64_t image_view, uint64_t sampler) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_block_spec_atlas(*state, *pipe, image_view, sampler);
}

int32_t ca_backend_world_set_block_normal_atlas(ca_backend_t backend, ca_pipeline_t pipeline,
                                                  uint64_t image_view, uint64_t sampler) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_block_normal_atlas(*state, *pipe, image_view, sampler);
}

int32_t ca_backend_world_set_sky_atlas(ca_backend_t backend, ca_pipeline_t pipeline,
                                       uint64_t image_view, uint64_t sampler) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_sky_atlas(*state, *pipe, image_view, sampler);
}

int32_t ca_backend_world_init_bindless_fallback(ca_backend_t backend, ca_pipeline_t pipeline,
                                                 uint64_t image_view, uint64_t sampler) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_init_bindless_fallback(*state, *pipe, image_view, sampler);
}

int32_t ca_backend_world_set_bindless_texture(ca_backend_t backend, ca_pipeline_t pipeline,
                                                uint32_t binding, uint32_t slot,
                                                uint64_t image_view, uint64_t sampler) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_set_bindless_texture(*state, *pipe, binding, slot,
                                                            image_view, sampler);
}

int32_t ca_backend_world_trace(ca_backend_t backend, ca_pipeline_t pipeline,
                                 uint64_t command_buffer, uint32_t width, uint32_t height,
                                 uint64_t push_constants_ptr, uint32_t push_constants_size) {
    auto* state = caustica::renderer::from_handle(backend);
    auto* pipe = caustica::renderer::as_pipeline(pipeline);
    if (state == nullptr || pipe == nullptr) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::world_trace(*state, *pipe, command_buffer, width, height,
                                          push_constants_ptr, push_constants_size);
}

} // extern "C"
