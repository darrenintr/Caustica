#include "backend_internal.hpp"

#include <array>

namespace caustica::renderer {
namespace {

bool valid_image(const ca_image_ref& image, const Backend& backend) {
    return image.size == sizeof(ca_image_ref) && image.abi == CAUSTICA_BACKEND_ABI_V1
            && image.image != 0 && image.view != 0 && image.width > 0 && image.height > 0
            && image.owner != CAUSTICA_OWNER_NATIVE
            && image.generation == backend.generation;
}

int32_t vk_status(VkResult result) {
    if (result == VK_SUCCESS) {
        return CAUSTICA_STATUS_OK;
    }
    return result == VK_ERROR_DEVICE_LOST ? CAUSTICA_STATUS_DEVICE_LOST
                                          : CAUSTICA_STATUS_RETRYABLE_PRE_COMMIT;
}

} // namespace

void destroy_firefly(Backend& backend) {
    auto& pass = backend.firefly;
    if (backend.device != VK_NULL_HANDLE) {
        if (pass.pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(backend.device, pass.pipeline, nullptr);
        }
        if (pass.pipeline_layout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(backend.device, pass.pipeline_layout, nullptr);
        }
        if (pass.descriptor_pool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(backend.device, pass.descriptor_pool, nullptr);
        }
        if (pass.descriptor_set_layout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(backend.device, pass.descriptor_set_layout, nullptr);
        }
    }
    pass = {};
}

int32_t ensure_firefly_prepared(Backend& backend, const ca_firefly_desc& desc) {
    if (!backend.attached || backend.reloading || backend.device == VK_NULL_HANDLE) {
        backend.status = "native firefly declined: backend unavailable";
        return CAUSTICA_STATUS_DECLINED;
    }
    if (!valid_image(desc.input, backend) || !valid_image(desc.output, backend)
            || desc.input.width != desc.output.width || desc.input.height != desc.output.height
            || desc.input.format != VK_FORMAT_B10G11R11_UFLOAT_PACK32
            || desc.output.format != VK_FORMAT_B10G11R11_UFLOAT_PACK32
            || desc.input.layout != VK_IMAGE_LAYOUT_GENERAL
            || desc.output.layout != VK_IMAGE_LAYOUT_GENERAL) {
        backend.status = "native firefly declined: image contract mismatch";
        return CAUSTICA_STATUS_DECLINED;
    }
    const auto shader = backend.shaders.find(CAUSTICA_SHADER_FIREFLY_KILL);
    if (shader == backend.shaders.end() || shader->second.words.empty()) {
        backend.status = "native firefly declined: shader missing";
        return CAUSTICA_STATUS_DECLINED;
    }

    auto& pass = backend.firefly;
    const auto input_view = reinterpret_cast<VkImageView>(desc.input.view);
    const auto output_view = reinterpret_cast<VkImageView>(desc.output.view);
    const bool recreate = pass.pipeline == VK_NULL_HANDLE || pass.width != desc.input.width
            || pass.height != desc.input.height || pass.generation != backend.generation;
    if (recreate) {
        destroy_firefly(backend);

        std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
        for (uint32_t i = 0; i < bindings.size(); ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo layout_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        layout_info.bindingCount = static_cast<uint32_t>(bindings.size());
        layout_info.pBindings = bindings.data();
        VkResult rc = vkCreateDescriptorSetLayout(backend.device, &layout_info, nullptr,
                                                   &pass.descriptor_set_layout);
        if (rc != VK_SUCCESS) {
            backend.status = "native firefly: vkCreateDescriptorSetLayout failed";
            destroy_firefly(backend);
            return vk_status(rc);
        }

        VkDescriptorPoolSize pool_size{};
        pool_size.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        pool_size.descriptorCount = 2;
        VkDescriptorPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool_info.maxSets = 1;
        pool_info.poolSizeCount = 1;
        pool_info.pPoolSizes = &pool_size;
        rc = vkCreateDescriptorPool(backend.device, &pool_info, nullptr, &pass.descriptor_pool);
        if (rc != VK_SUCCESS) {
            backend.status = "native firefly: vkCreateDescriptorPool failed";
            destroy_firefly(backend);
            return vk_status(rc);
        }

        VkDescriptorSetAllocateInfo allocate_info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        allocate_info.descriptorPool = pass.descriptor_pool;
        allocate_info.descriptorSetCount = 1;
        allocate_info.pSetLayouts = &pass.descriptor_set_layout;
        rc = vkAllocateDescriptorSets(backend.device, &allocate_info, &pass.descriptor_set);
        if (rc != VK_SUCCESS) {
            backend.status = "native firefly: vkAllocateDescriptorSets failed";
            destroy_firefly(backend);
            return vk_status(rc);
        }

        VkPipelineLayoutCreateInfo pipeline_layout_info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pipeline_layout_info.setLayoutCount = 1;
        pipeline_layout_info.pSetLayouts = &pass.descriptor_set_layout;
        rc = vkCreatePipelineLayout(backend.device, &pipeline_layout_info, nullptr,
                                    &pass.pipeline_layout);
        if (rc != VK_SUCCESS) {
            backend.status = "native firefly: vkCreatePipelineLayout failed";
            destroy_firefly(backend);
            return vk_status(rc);
        }

        VkShaderModule shader_module = VK_NULL_HANDLE;
        VkShaderModuleCreateInfo shader_info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        shader_info.codeSize = shader->second.words.size() * sizeof(uint32_t);
        shader_info.pCode = shader->second.words.data();
        rc = vkCreateShaderModule(backend.device, &shader_info, nullptr, &shader_module);
        if (rc != VK_SUCCESS) {
            backend.status = "native firefly: vkCreateShaderModule failed";
            destroy_firefly(backend);
            return vk_status(rc);
        }

        VkComputePipelineCreateInfo pipeline_info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pipeline_info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeline_info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeline_info.stage.module = shader_module;
        pipeline_info.stage.pName = "main";
        pipeline_info.layout = pass.pipeline_layout;
        rc = vkCreateComputePipelines(backend.device, VK_NULL_HANDLE, 1, &pipeline_info, nullptr,
                                      &pass.pipeline);
        vkDestroyShaderModule(backend.device, shader_module, nullptr);
        if (rc != VK_SUCCESS) {
            backend.status = "native firefly: vkCreateComputePipelines failed";
            destroy_firefly(backend);
            return vk_status(rc);
        }
        pass.width = desc.input.width;
        pass.height = desc.input.height;
        pass.generation = backend.generation;
    }

    if (pass.input_view != input_view || pass.output_view != output_view) {
        std::array<VkDescriptorImageInfo, 2> image_info{};
        image_info[0].imageView = input_view;
        image_info[0].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        image_info[1].imageView = output_view;
        image_info[1].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        std::array<VkWriteDescriptorSet, 2> writes{};
        for (uint32_t i = 0; i < writes.size(); ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = pass.descriptor_set;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[i].pImageInfo = &image_info[i];
        }
        vkUpdateDescriptorSets(backend.device, static_cast<uint32_t>(writes.size()), writes.data(), 0,
                               nullptr);
        pass.input_view = input_view;
        pass.output_view = output_view;
    }

    pass.prepared = true;
    backend.status = "native firefly prepared";
    return CAUSTICA_STATUS_OK;
}

ca_frame_result record_firefly(Backend& backend, const ca_firefly_desc& desc) {
    auto& pass = backend.firefly;
    if (!pass.prepared || desc.command_buffer == 0 || desc.input.width != pass.width
            || desc.input.height != pass.height || desc.input.generation != pass.generation
            || reinterpret_cast<VkImageView>(desc.input.view) != pass.input_view
            || reinterpret_cast<VkImageView>(desc.output.view) != pass.output_view) {
        backend.status = "native firefly record declined: prepare contract changed";
        return result(CAUSTICA_STATUS_DECLINED, CAUSTICA_COMMIT_NONE);
    }

    const auto command_buffer = reinterpret_cast<VkCommandBuffer>(desc.command_buffer);
    vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pass.pipeline);
    vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pass.pipeline_layout,
                            0, 1, &pass.descriptor_set, 0, nullptr);
    vkCmdDispatch(command_buffer, (pass.width + 7) / 8, (pass.height + 7) / 8, 1);
    pass.prepared = false;
    ++backend.firefly_dispatch_count;
    backend.status = "native firefly recorded";
    return result(CAUSTICA_STATUS_OK, CAUSTICA_COMMIT_RECORDED, desc.frame_id);
}

} // namespace caustica::renderer
