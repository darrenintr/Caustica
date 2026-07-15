// Caustica NRD REBLUR Vulkan backend — adapted from NVIDIA Apache-2.0 vk_denoise_nrd / Radiance MCVR.
#include "caustica_nrd_shim.h"

#include <NRD.h>
#include <NRDDescs.h>
#include <NRDSettings.h>

#include <vulkan/vulkan.h>

#include <array>
#include <cassert>
#include <cstring>
#include <new>
#include <vector>

#ifndef VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR
#define VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR 0x00000001
#endif

#define NRD_ARRAYSIZE(x) (sizeof(x) / sizeof(*(x)))

static const VkFormat g_NRDFormatToVkFormat[] = {
    VK_FORMAT_R8_UNORM, VK_FORMAT_R8_SNORM, VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT,
    VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8_SINT,
    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SNORM, VK_FORMAT_A8B8G8R8_UINT_PACK32,
    VK_FORMAT_R8G8B8A8_SINT, VK_FORMAT_R8G8B8A8_SRGB,
    VK_FORMAT_R16_UNORM, VK_FORMAT_R16_SNORM, VK_FORMAT_R16_UINT, VK_FORMAT_R16_SINT, VK_FORMAT_R16_SFLOAT,
    VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16_SFLOAT,
    VK_FORMAT_R16G16B16A16_UNORM, VK_FORMAT_R16G16B16A16_SNORM, VK_FORMAT_R16G16B16A16_UINT,
    VK_FORMAT_R16G16B16A16_SINT, VK_FORMAT_R16G16B16A16_SFLOAT,
    VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT, VK_FORMAT_R32_SFLOAT,
    VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32_SFLOAT,
    VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32_SINT, VK_FORMAT_R32G32B32_SFLOAT,
    VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT, VK_FORMAT_R32G32B32A32_SFLOAT,
    VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_A2R10G10B10_UINT_PACK32,
    VK_FORMAT_B10G11R11_UFLOAT_PACK32, VK_FORMAT_E5B9G9R9_UFLOAT_PACK32,
};

static VkFormat nrdToVkFormat(nrd::Format f) {
    size_t i = size_t(f);
    if (i >= NRD_ARRAYSIZE(g_NRDFormatToVkFormat)) return VK_FORMAT_UNDEFINED;
    return g_NRDFormatToVkFormat[i];
}

static VkFilter nrdToVkFilter(nrd::Sampler s) {
    return s == nrd::Sampler::LINEAR_CLAMP ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
}

static uint16_t divUp(uint32_t a, uint16_t b) { return uint16_t((a + b - 1) / b); }

struct GpuTex {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t w = 0, h = 0;
};

struct NrdPipe {
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkDescriptorSetLayout resourceLayout = VK_NULL_HANDLE;
    uint32_t numBindings = 0;
};

struct CausticaNrd {
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    PFN_vkCmdPushDescriptorSetKHR cmdPushDescriptorSetKHR = nullptr;

    nrd::Instance* instance = nullptr;
    uint32_t width = 0, height = 0;

    std::vector<GpuTex> permanent;
    std::vector<GpuTex> transient;
    // User pool: sparse by ResourceType
    std::array<GpuTex, size_t(nrd::ResourceType::MAX_NUM)> user{};
    // Owned user outputs (allocated by us if needed) - we only store handles from Java
    bool userSet = false;

    std::vector<VkSampler> samplers;
    VkBuffer constantBuffer = VK_NULL_HANDLE;
    VkDeviceMemory constantMemory = VK_NULL_HANDLE;
    uint32_t constantSize = 0;

    VkDescriptorSetLayout samplerSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool samplerPool = VK_NULL_HANDLE;
    VkDescriptorSet samplerSet = VK_NULL_HANDLE;

    std::vector<NrdPipe> pipelines;
};

static uint32_t findMemType(VkPhysicalDevice phys, uint32_t bits, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(phys, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props)
            return i;
    }
    return 0;
}

static void destroyTex(VkDevice dev, GpuTex& t) {
    if (t.view) vkDestroyImageView(dev, t.view, nullptr);
    if (t.image) vkDestroyImage(dev, t.image, nullptr);
    if (t.memory) vkFreeMemory(dev, t.memory, nullptr);
    t = {};
}

static int createTex(CausticaNrd* c, GpuTex& t, uint32_t w, uint32_t h, VkFormat fmt) {
    destroyTex(c->device, t);
    t.w = w; t.h = h; t.format = fmt;
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = fmt;
    ici.extent = {w, h, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(c->device, &ici, nullptr, &t.image) != VK_SUCCESS) return -1;
    VkMemoryRequirements mr{};
    vkGetImageMemoryRequirements(c->device, t.image, &mr);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = mr.size;
    mai.memoryTypeIndex = findMemType(c->physical, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vkAllocateMemory(c->device, &mai, nullptr, &t.memory) != VK_SUCCESS) return -2;
    vkBindImageMemory(c->device, t.image, t.memory, 0);
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = t.image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = fmt;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(c->device, &vci, nullptr, &t.view) != VK_SUCCESS) return -3;
    return 0;
}

static void setUserTex(CausticaNrd* c, nrd::ResourceType type, VkImage img, VkImageView view) {
    size_t i = size_t(type);
    if (i >= c->user.size()) return;
    c->user[i].image = img;
    c->user[i].view = view;
    // memory not owned
}

static int createPipelines(CausticaNrd* c) {
    const nrd::InstanceDesc* iDesc = nrd::GetInstanceDesc(*c->instance);
    const nrd::LibraryDesc* lDesc = nrd::GetLibraryDesc();
    const uint32_t cbOff = lDesc->spirvBindingOffsets.constantBufferOffset;
    const uint32_t sampOff = lDesc->spirvBindingOffsets.samplerOffset;
    const uint32_t texOff = lDesc->spirvBindingOffsets.textureOffset;
    const uint32_t storOff = lDesc->spirvBindingOffsets.storageTextureAndBufferOffset;

    // Samplers
    for (uint32_t s = 0; s < iDesc->samplersNum; ++s) {
        VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        sci.magFilter = sci.minFilter = nrdToVkFilter(iDesc->samplers[s]);
        sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        VkSampler samp = VK_NULL_HANDLE;
        if (vkCreateSampler(c->device, &sci, nullptr, &samp) != VK_SUCCESS) return -10;
        c->samplers.push_back(samp);
    }

    // Constant buffer (host visible for simplicity - updated via vkCmdUpdateBuffer from GPU-visible)
    c->constantSize = iDesc->constantBufferMaxDataSize;
    {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = c->constantSize;
        bci.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(c->device, &bci, nullptr, &c->constantBuffer) != VK_SUCCESS) return -11;
        VkMemoryRequirements mr{};
        vkGetBufferMemoryRequirements(c->device, c->constantBuffer, &mr);
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = mr.size;
        mai.memoryTypeIndex = findMemType(c->physical, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vkAllocateMemory(c->device, &mai, nullptr, &c->constantMemory) != VK_SUCCESS) return -12;
        vkBindBufferMemory(c->device, c->constantBuffer, c->constantMemory, 0);
    }

    // Sampler + CB descriptor set (set 1)
    {
        std::vector<VkDescriptorSetLayoutBinding> binds(iDesc->samplersNum + 1);
        for (uint32_t s = 0; s < iDesc->samplersNum; ++s) {
            binds[s] = {sampOff + s, VK_DESCRIPTOR_TYPE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, &c->samplers[s]};
        }
        binds[iDesc->samplersNum] = {cbOff, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        lci.bindingCount = (uint32_t)binds.size();
        lci.pBindings = binds.data();
        if (vkCreateDescriptorSetLayout(c->device, &lci, nullptr, &c->samplerSetLayout) != VK_SUCCESS) return -13;

        VkDescriptorPoolSize ps[] = {
            {VK_DESCRIPTOR_TYPE_SAMPLER, iDesc->samplersNum},
            {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1},
        };
        VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pci.maxSets = 1;
        pci.poolSizeCount = 2;
        pci.pPoolSizes = ps;
        if (vkCreateDescriptorPool(c->device, &pci, nullptr, &c->samplerPool) != VK_SUCCESS) return -14;
        VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        dai.descriptorPool = c->samplerPool;
        dai.descriptorSetCount = 1;
        dai.pSetLayouts = &c->samplerSetLayout;
        if (vkAllocateDescriptorSets(c->device, &dai, &c->samplerSet) != VK_SUCCESS) return -15;

        VkDescriptorBufferInfo bi{};
        bi.buffer = c->constantBuffer;
        bi.offset = 0;
        bi.range = VK_WHOLE_SIZE;
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w.dstSet = c->samplerSet;
        w.dstBinding = cbOff;
        w.descriptorCount = 1;
        w.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        w.pBufferInfo = &bi;
        vkUpdateDescriptorSets(c->device, 1, &w, 0, nullptr);
    }

    // Pipelines
    c->pipelines.resize(iDesc->pipelinesNum);
    for (uint32_t p = 0; p < iDesc->pipelinesNum; ++p) {
        const nrd::PipelineDesc& pDesc = iDesc->pipelines[p];
        std::vector<VkDescriptorSetLayoutBinding> setBinds;
        for (uint32_t r = 0; r < pDesc.resourceRangesNum; ++r) {
            const nrd::ResourceRangeDesc& range = pDesc.resourceRanges[r];
            for (uint32_t b = 0; b < range.descriptorsNum; ++b) {
                VkDescriptorSetLayoutBinding binding{};
                binding.descriptorCount = 1;
                binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
                if (range.descriptorType == nrd::DescriptorType::TEXTURE) {
                    binding.descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                    binding.binding = texOff + iDesc->resourcesBaseRegisterIndex + b;
                } else {
                    binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                    binding.binding = storOff + iDesc->resourcesBaseRegisterIndex + b;
                }
                setBinds.push_back(binding);
            }
        }
        VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        setInfo.flags = VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
        setInfo.bindingCount = (uint32_t)setBinds.size();
        setInfo.pBindings = setBinds.data();
        NrdPipe& pipe = c->pipelines[p];
        pipe.numBindings = setInfo.bindingCount;
        if (vkCreateDescriptorSetLayout(c->device, &setInfo, nullptr, &pipe.resourceLayout) != VK_SUCCESS) return -20;

        VkDescriptorSetLayout layouts[2] = {pipe.resourceLayout, c->samplerSetLayout};
        // NRD: resources space 0, samplers space 1
        VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        plci.setLayoutCount = 2;
        plci.pSetLayouts = layouts;
        if (vkCreatePipelineLayout(c->device, &plci, nullptr, &pipe.layout) != VK_SUCCESS) return -21;

        if (pDesc.computeShaderSPIRV.size == 0 || !pDesc.computeShaderSPIRV.bytecode) return -22;
        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = pDesc.computeShaderSPIRV.size;
        smci.pCode = reinterpret_cast<const uint32_t*>(pDesc.computeShaderSPIRV.bytecode);
        VkShaderModule mod = VK_NULL_HANDLE;
        if (vkCreateShaderModule(c->device, &smci, nullptr, &mod) != VK_SUCCESS) return -23;

        VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = mod;
        stage.pName = "main";
        VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cpci.stage = stage;
        cpci.layout = pipe.layout;
        if (vkCreateComputePipelines(c->device, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipe.pipeline) != VK_SUCCESS) {
            vkDestroyShaderModule(c->device, mod, nullptr);
            return -24;
        }
        vkDestroyShaderModule(c->device, mod, nullptr);
    }
    return 0;
}

static int recreatePools(CausticaNrd* c, uint32_t w, uint32_t h) {
    for (auto& t : c->permanent) destroyTex(c->device, t);
    for (auto& t : c->transient) destroyTex(c->device, t);
    c->permanent.clear();
    c->transient.clear();
    c->width = w;
    c->height = h;
    const nrd::InstanceDesc* iDesc = nrd::GetInstanceDesc(*c->instance);
    for (uint32_t t = 0; t < iDesc->permanentPoolSize; ++t) {
        GpuTex tex{};
        uint32_t tw = divUp(w, iDesc->permanentPool[t].downsampleFactor);
        uint32_t th = divUp(h, iDesc->permanentPool[t].downsampleFactor);
        if (createTex(c, tex, tw, th, nrdToVkFormat(iDesc->permanentPool[t].format)) != 0) return -30;
        c->permanent.push_back(tex);
    }
    for (uint32_t t = 0; t < iDesc->transientPoolSize; ++t) {
        GpuTex tex{};
        uint32_t tw = divUp(w, iDesc->transientPool[t].downsampleFactor);
        uint32_t th = divUp(h, iDesc->transientPool[t].downsampleFactor);
        if (createTex(c, tex, tw, th, nrdToVkFormat(iDesc->transientPool[t].format)) != 0) return -31;
        c->transient.push_back(tex);
    }

    // Clear permanent pool (history buffers) to avoid uninitialized GPU memory artifacts.
    // NRD REBLUR reads previous-frame accumulators on first dispatch; without this clear,
    // left-half or checkerboard garbage pixels appear until history converges (30+ frames).
    if (!c->permanent.empty()) {
        VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpci.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        cpci.queueFamilyIndex = 0; // assume graphics queue 0 (Caustica main queue)
        VkCommandPool pool = VK_NULL_HANDLE;
        if (vkCreateCommandPool(c->device, &cpci, nullptr, &pool) != VK_SUCCESS) return -32;

        VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cbai.commandPool = pool;
        cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbai.commandBufferCount = 1;
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(c->device, &cbai, &cmd) != VK_SUCCESS) {
            vkDestroyCommandPool(c->device, pool, nullptr);
            return -33;
        }

        VkCommandBufferBeginInfo cbbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &cbbi);

        for (auto& tex : c->permanent) {
            VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            barrier.srcAccessMask = 0;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.image = tex.image;
            barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 0, 0, nullptr, 0, nullptr, 1, &barrier);

            VkClearColorValue clear{};
            VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdClearColorImage(cmd, tex.image, VK_IMAGE_LAYOUT_GENERAL, &clear, 1, &range);

            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 0, 0, nullptr, 0, nullptr, 1, &barrier);
        }

        vkEndCommandBuffer(cmd);

        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        VkQueue queue = VK_NULL_HANDLE;
        vkGetDeviceQueue(c->device, 0, 0, &queue);
        if (vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE) == VK_SUCCESS) {
            vkQueueWaitIdle(queue);
        }

        vkDestroyCommandPool(c->device, pool, nullptr);
    }

    return 0;
}

static void destroyAll(CausticaNrd* c) {
    if (!c) return;
    for (auto& p : c->pipelines) {
        if (p.pipeline) vkDestroyPipeline(c->device, p.pipeline, nullptr);
        if (p.layout) vkDestroyPipelineLayout(c->device, p.layout, nullptr);
        if (p.resourceLayout) vkDestroyDescriptorSetLayout(c->device, p.resourceLayout, nullptr);
    }
    c->pipelines.clear();
    if (c->samplerSetLayout) vkDestroyDescriptorSetLayout(c->device, c->samplerSetLayout, nullptr);
    if (c->samplerPool) vkDestroyDescriptorPool(c->device, c->samplerPool, nullptr);
    for (auto s : c->samplers) vkDestroySampler(c->device, s, nullptr);
    c->samplers.clear();
    if (c->constantBuffer) vkDestroyBuffer(c->device, c->constantBuffer, nullptr);
    if (c->constantMemory) vkFreeMemory(c->device, c->constantMemory, nullptr);
    for (auto& t : c->permanent) destroyTex(c->device, t);
    for (auto& t : c->transient) destroyTex(c->device, t);
    if (c->instance) {
        nrd::DestroyInstance(*c->instance);
        c->instance = nullptr;
    }
}

static GpuTex* resolveTex(CausticaNrd* c, const nrd::ResourceDesc& r) {
    if (r.type == nrd::ResourceType::TRANSIENT_POOL) {
        if (r.indexInPool >= c->transient.size()) return nullptr;
        return &c->transient[r.indexInPool];
    }
    if (r.type == nrd::ResourceType::PERMANENT_POOL) {
        if (r.indexInPool >= c->permanent.size()) return nullptr;
        return &c->permanent[r.indexInPool];
    }
    size_t i = size_t(r.type);
    if (i >= c->user.size() || c->user[i].view == VK_NULL_HANDLE) return nullptr;
    return &c->user[i];
}

static void dispatchOne(CausticaNrd* c, VkCommandBuffer cmd, const nrd::DispatchDesc& dDesc) {
    const nrd::LibraryDesc* lDesc = nrd::GetLibraryDesc();
    const nrd::InstanceDesc* iDesc = nrd::GetInstanceDesc(*c->instance);
    const nrd::PipelineDesc& pDesc = iDesc->pipelines[dDesc.pipelineIndex];
    const uint32_t texOff = lDesc->spirvBindingOffsets.textureOffset;
    const uint32_t storOff = lDesc->spirvBindingOffsets.storageTextureAndBufferOffset;
    NrdPipe& pipe = c->pipelines[dDesc.pipelineIndex];

    std::vector<VkWriteDescriptorSet> writes(pipe.numBindings);
    std::vector<VkDescriptorImageInfo> infos(pipe.numBindings);
    uint32_t nUp = 0;
    for (uint32_t r = 0; r < pDesc.resourceRangesNum; ++r) {
        const nrd::ResourceRangeDesc& range = pDesc.resourceRanges[r];
        const bool isStorage = range.descriptorType == nrd::DescriptorType::STORAGE_TEXTURE;
        uint32_t base = isStorage ? storOff : texOff;
        for (uint32_t d = 0; d < range.descriptorsNum; ++d) {
            const nrd::ResourceDesc& nr = dDesc.resources[nUp];
            GpuTex* tex = resolveTex(c, nr);
            if (!tex || !tex->view) {
                // skip invalid
            }
            writes[nUp] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[nUp].dstBinding = base + d;
            writes[nUp].descriptorCount = 1;
            writes[nUp].descriptorType = isStorage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            writes[nUp].pImageInfo = &infos[nUp];
            infos[nUp] = {VK_NULL_HANDLE, tex ? tex->view : VK_NULL_HANDLE, VK_IMAGE_LAYOUT_GENERAL};
            nUp++;
        }
    }

    if (pDesc.hasConstantData && !dDesc.constantBufferDataMatchesPreviousDispatch) {
        VkBufferMemoryBarrier bar{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        bar.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bar.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar.buffer = c->constantBuffer;
        bar.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 1, &bar, 0, nullptr);
        vkCmdUpdateBuffer(cmd, c->constantBuffer, 0, dDesc.constantBufferDataSize, dDesc.constantBufferData);
        bar.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 0, nullptr, 1, &bar, 0, nullptr);
    }

    // Global memory barrier for image R/W between NRD passes
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 1, &mb, 0, nullptr, 0, nullptr);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe.pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe.layout,
                            iDesc->constantBufferAndSamplersSpaceIndex, 1, &c->samplerSet, 0, nullptr);
    if (c->cmdPushDescriptorSetKHR && nUp > 0) {
        c->cmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe.layout,
                                   iDesc->resourcesSpaceIndex, nUp, writes.data());
    }
    vkCmdDispatch(cmd, dDesc.gridWidth, dDesc.gridHeight, 1);
}

extern "C" int caustica_nrd_probe(void) {
    return NRD_VERSION_MAJOR * 10000 + NRD_VERSION_MINOR * 100 + NRD_VERSION_BUILD;
}

extern "C" int caustica_nrd_create(
    uint64_t vk_device, uint64_t vk_physical, uint64_t get_device_proc_addr,
    uint32_t width, uint32_t height, void** out_ctx)
{
    if (!out_ctx || !vk_device || !vk_physical || !get_device_proc_addr || !width || !height) return -1;
    auto* c = new (std::nothrow) CausticaNrd();
    if (!c) return -2;
    c->device = (VkDevice)vk_device;
    c->physical = (VkPhysicalDevice)vk_physical;
    c->getDeviceProcAddr = (PFN_vkGetDeviceProcAddr)get_device_proc_addr;
    c->cmdPushDescriptorSetKHR = (PFN_vkCmdPushDescriptorSetKHR)
        c->getDeviceProcAddr(c->device, "vkCmdPushDescriptorSetKHR");
    if (!c->cmdPushDescriptorSetKHR) {
        delete c;
        return -3; // need push descriptors
    }

    nrd::DenoiserDesc denoisers[] = {
        {nrd::Identifier(nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR), nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR},
    };
    nrd::InstanceCreationDesc idesc{{}, denoisers, 1};
    if (nrd::CreateInstance(idesc, c->instance) != nrd::Result::SUCCESS) {
        delete c;
        return -4;
    }

    // OPTIMIZED FOR MID-RANGE GPUs (RX7600, RTX4060): SPP=1 with strong spatial+temporal.
    // Strategy: aggressive spatial blur on motion/disocclusion (fast coverage), then
    // temporal accumulation refines static areas. Motion blur > motion noise.
    nrd::ReblurSettings reblur{};
    reblur.maxAccumulatedFrameNum = 28;          // faster response for dim-lighting ghosting (was 48)
    reblur.maxFastAccumulatedFrameNum = 4;       // FAST converge on disocclusion (was 6)
    reblur.maxStabilizedFrameNum = 32;           // stabilize radiance for stills
    reblur.historyFixFrameNum = 2;               // minimal checkerboard (was 3)
    reblur.historyFixBasePixelStride = 4;        // dense fill grid (was 6)
    reblur.historyFixAlternatePixelStride = 4;
    reblur.diffusePrepassBlurRadius = 50.0f;     // WIDE prepass: eat SPP-1 grain fast
    reblur.specularPrepassBlurRadius = 60.0f;
    reblur.usePrepassOnlyForSpecularMotionEstimation = false;
    reblur.minBlurRadius = 3.0f;                 // never below 3px (was 2.0)
    reblur.maxBlurRadius = 48.0f;                // wider max for disocclusion (was 32)
    reblur.fastHistoryClampingSigmaScale = 1.8f; // tighter variance rejection (was 2.2)
    reblur.lobeAngleFraction = 0.20f;            // wider lobe = more spatial reuse (was 0.15)
    reblur.roughnessFraction = 0.20f;            // looser roughness match (was 0.15)
    reblur.planeDistanceSensitivity = 0.03f;     // looser plane check = more blur (was 0.02)
    reblur.minHitDistanceWeight = 0.08f;         // less hit-dist modulation (was 0.12)
    reblur.fireflySuppressorMinRelativeScale = 3.5f; // stronger outlier rejection (was 2.5)
    reblur.enableAntiFirefly = true;
    reblur.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::AREA_3X3;
    // Responsive accumulation: trigger fast blur on rough surfaces (motion-blur style).
    reblur.responsiveAccumulationSettings.roughnessThreshold = 0.12f; // wider trigger (was 0.08)
    reblur.responsiveAccumulationSettings.minAccumulatedFrameNum = 2;
    // Antilag: moderate — too strong kills temporal benefit, too weak leaves trails.
    reblur.antilagSettings.luminanceSigmaScale = 1.8f;
    reblur.antilagSettings.luminanceSensitivity = 3.0f; // aggressive ghosting/firefly rejection (was 2.2)
    reblur.convergenceSettings.s = 1.0f;
    reblur.convergenceSettings.b = 0.2f;
    reblur.convergenceSettings.p = 0.85f;
    nrd::SetDenoiserSettings(*c->instance, nrd::Identifier(nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR), &reblur);

    if (recreatePools(c, width, height) != 0) {
        destroyAll(c);
        delete c;
        return -5;
    }
    if (createPipelines(c) != 0) {
        destroyAll(c);
        delete c;
        return -6;
    }
    *out_ctx = c;
    return 0;
}

extern "C" int caustica_nrd_destroy(void* ctx) {
    if (!ctx) return 0;
    auto* c = static_cast<CausticaNrd*>(ctx);
    destroyAll(c);
    delete c;
    return 0;
}

extern "C" int caustica_nrd_resize(void* ctx, uint32_t width, uint32_t height) {
    if (!ctx || !width || !height) return -1;
    auto* c = static_cast<CausticaNrd*>(ctx);
    if (c->width == width && c->height == height) return 0;
    return recreatePools(c, width, height);
}

extern "C" int caustica_nrd_dispatch(
    void* ctx, uint64_t vk_command_buffer,
    uint64_t in_diff_image, uint64_t in_diff_view,
    uint64_t in_spec_image, uint64_t in_spec_view,
    uint64_t in_mv_image, uint64_t in_mv_view,
    uint64_t in_normal_image, uint64_t in_normal_view,
    uint64_t in_viewz_image, uint64_t in_viewz_view,
    uint64_t out_diff_image, uint64_t out_diff_view,
    uint64_t out_spec_image, uint64_t out_spec_view,
    const float* view_to_clip, const float* view_to_clip_prev,
    const float* world_to_view, const float* world_to_view_prev,
    float jitter_x, float jitter_y, float jitter_x_prev, float jitter_y_prev,
    uint32_t frame_index, int reset)
{
    if (!ctx || !vk_command_buffer) return -1;
    auto* c = static_cast<CausticaNrd*>(ctx);
    VkCommandBuffer cmd = (VkCommandBuffer)vk_command_buffer;

    setUserTex(c, nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST, (VkImage)in_diff_image, (VkImageView)in_diff_view);
    setUserTex(c, nrd::ResourceType::IN_SPEC_RADIANCE_HITDIST, (VkImage)in_spec_image, (VkImageView)in_spec_view);
    setUserTex(c, nrd::ResourceType::IN_MV, (VkImage)in_mv_image, (VkImageView)in_mv_view);
    setUserTex(c, nrd::ResourceType::IN_NORMAL_ROUGHNESS, (VkImage)in_normal_image, (VkImageView)in_normal_view);
    setUserTex(c, nrd::ResourceType::IN_VIEWZ, (VkImage)in_viewz_image, (VkImageView)in_viewz_view);
    setUserTex(c, nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST, (VkImage)out_diff_image, (VkImageView)out_diff_view);
    setUserTex(c, nrd::ResourceType::OUT_SPEC_RADIANCE_HITDIST, (VkImage)out_spec_image, (VkImageView)out_spec_view);

    nrd::CommonSettings cs{};
    std::memcpy(cs.viewToClipMatrix, view_to_clip, 16 * sizeof(float));
    std::memcpy(cs.viewToClipMatrixPrev, view_to_clip_prev, 16 * sizeof(float));
    std::memcpy(cs.worldToViewMatrix, world_to_view, 16 * sizeof(float));
    std::memcpy(cs.worldToViewMatrixPrev, world_to_view_prev, 16 * sizeof(float));
    // Vulkan Y flip for NRD (same as Radiance)
    for (int col = 0; col < 4; ++col) {
        cs.viewToClipMatrix[col * 4 + 1] *= -1.0f;
        cs.viewToClipMatrixPrev[col * 4 + 1] *= -1.0f;
    }
    cs.resourceSize[0] = (uint16_t)c->width;
    cs.resourceSize[1] = (uint16_t)c->height;
    cs.rectSize[0] = (uint16_t)c->width;
    cs.rectSize[1] = (uint16_t)c->height;
    cs.resourceSizePrev[0] = (uint16_t)c->width;
    cs.resourceSizePrev[1] = (uint16_t)c->height;
    cs.rectSizePrev[0] = (uint16_t)c->width;
    cs.rectSizePrev[1] = (uint16_t)c->height;
    cs.cameraJitter[0] = jitter_x;
    cs.cameraJitter[1] = jitter_y;
    cs.cameraJitterPrev[0] = jitter_x_prev;
    cs.cameraJitterPrev[1] = jitter_y_prev;
    cs.frameIndex = frame_index;
    cs.accumulationMode = (reset || frame_index == 0)
        ? nrd::AccumulationMode::CLEAR_AND_RESTART
        : nrd::AccumulationMode::CONTINUE;
    // 2D screen-space MV only. (2.5D mv.z from clip.w was inconsistent with prepare viewZ
    // and caused mass history rejection → undenoised firefly grain.)
    cs.motionVectorScale[0] = 1.0f / float(c->width);
    cs.motionVectorScale[1] = 1.0f / float(c->height);
    cs.motionVectorScale[2] = 0.0f;
    cs.viewZScale = 1.0f;
    // LOOSE disocclusion for mid-range: prefer blurry history over noisy single-frame.
    // RX7600 can't brute-force SPP → spatial blur on motion is cheaper than noise.
    cs.disocclusionThreshold = 0.035f;
    cs.isMotionVectorInWorldSpace = false;
    cs.enableValidation = false;

    if (nrd::SetCommonSettings(*c->instance, cs) != nrd::Result::SUCCESS) return -2;

    nrd::Identifier id = nrd::Identifier(nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR);
    const nrd::DispatchDesc* dispatches = nullptr;
    uint32_t nDisp = 0;
    if (nrd::GetComputeDispatches(*c->instance, &id, 1, dispatches, nDisp) != nrd::Result::SUCCESS)
        return -3;
    for (uint32_t i = 0; i < nDisp; ++i) {
        dispatchOne(c, cmd, dispatches[i]);
    }
    return 0;
}
