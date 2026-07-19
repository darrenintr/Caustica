// Caustica NRD REBLUR Vulkan backend — adapted from NVIDIA Apache-2.0 vk_denoise_nrd / Radiance MCVR.
#include "caustica_nrd_shim.h"

#include <NRD.h>
#include <NRDDescs.h>
#include <NRDSettings.h>

#include <vulkan/vulkan.h>

#include <array>
#include <algorithm>
#include <cassert>
#include <cstring>
#include <new>
#include <utility>
#include <vector>

#ifndef VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR
#define VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR 0x00000001
#endif

#ifndef CAUSTICA_NRD_NORMAL_ENCODING
#define CAUSTICA_NRD_NORMAL_ENCODING 4
#endif
#ifndef CAUSTICA_NRD_ROUGHNESS_ENCODING
#define CAUSTICA_NRD_ROUGHNESS_ENCODING 1
#endif

#define NRD_ARRAYSIZE(x) (sizeof(x) / sizeof(*(x)))
static constexpr uint32_t FALLBACK_DESCRIPTOR_VARIANTS = 8;

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

struct CachedDescriptorSet {
    uint16_t pipelineIndex = 0;
    VkDescriptorSet set = VK_NULL_HANDLE;
    std::vector<VkImageView> imageViews;
};

struct CausticaNrd {
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    PFN_vkCmdPushDescriptorSetKHR cmdPushDescriptorSetKHR = nullptr;
    uint32_t queueFamilies[2] = {VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED};
    uint32_t queueFamilyCount = 0;
    bool poolsNeedInit = false;

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
    VkDescriptorPool resourcePool = VK_NULL_HANDLE;
    std::vector<CachedDescriptorSet> cachedResourceSets;

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
    if (c->queueFamilyCount > 1) {
        ici.sharingMode = VK_SHARING_MODE_CONCURRENT;
        ici.queueFamilyIndexCount = c->queueFamilyCount;
        ici.pQueueFamilyIndices = c->queueFamilies;
    }
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
        setInfo.flags = c->cmdPushDescriptorSetKHR
            ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0;
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

    if (!c->cmdPushDescriptorSetKHR) {
        const nrd::DescriptorPoolDesc& dp = iDesc->descriptorPoolDesc;
        VkDescriptorPoolSize sizes[] = {
            {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, dp.totalTexturesNum * FALLBACK_DESCRIPTOR_VARIANTS},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, dp.totalStorageTexturesNum * FALLBACK_DESCRIPTOR_VARIANTS},
        };
        VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pci.maxSets = dp.setsMaxNum * FALLBACK_DESCRIPTOR_VARIANTS;
        pci.poolSizeCount = 2;
        pci.pPoolSizes = sizes;
        if (vkCreateDescriptorPool(c->device, &pci, nullptr, &c->resourcePool) != VK_SUCCESS) return -25;
    }
    return 0;
}

static int recreatePools(CausticaNrd* c, uint32_t w, uint32_t h) {
    if (c->resourcePool) {
        vkResetDescriptorPool(c->device, c->resourcePool, 0);
        c->cachedResourceSets.clear();
    }
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

    c->poolsNeedInit = true;
    return 0;
}

static void destroyAll(CausticaNrd* c) {
    if (!c) return;
    if (c->resourcePool) vkDestroyDescriptorPool(c->device, c->resourcePool, nullptr);
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

static void recordPoolInitialization(CausticaNrd* c, VkCommandBuffer cmd) {
    if (!c->poolsNeedInit) return;

    // Permanent history needs a defined zero value. Transition through TRANSFER for the clear,
    // then make the result visible to every NRD compute pass.
    for (GpuTex& tex : c->permanent) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
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
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    // Transient resources are not cleared, but must leave UNDEFINED before descriptors bind them
    // as GENERAL. The first NRD writer establishes their contents.
    for (GpuTex& tex : c->transient) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = tex.image;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &barrier);
    }
    c->poolsNeedInit = false;
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

static bool dispatchOne(CausticaNrd* c, VkCommandBuffer cmd, const nrd::DispatchDesc& dDesc) {
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
    } else if (nUp > 0) {
        std::vector<VkImageView> signature(nUp);
        for (uint32_t i = 0; i < nUp; ++i)
            signature[i] = infos[i].imageView;
        auto cached = std::find_if(c->cachedResourceSets.begin(), c->cachedResourceSets.end(),
            [&signature, &dDesc](const CachedDescriptorSet& entry) {
                return entry.pipelineIndex == dDesc.pipelineIndex && entry.imageViews == signature;
            });
        VkDescriptorSet set = VK_NULL_HANDLE;
        if (cached != c->cachedResourceSets.end()) {
            set = cached->set;
        } else {
            VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            dai.descriptorPool = c->resourcePool;
            dai.descriptorSetCount = 1;
            dai.pSetLayouts = &pipe.resourceLayout;
            if (vkAllocateDescriptorSets(c->device, &dai, &set) != VK_SUCCESS) return false;
            for (uint32_t i = 0; i < nUp; ++i) writes[i].dstSet = set;
            vkUpdateDescriptorSets(c->device, nUp, writes.data(), 0, nullptr);
            c->cachedResourceSets.push_back({dDesc.pipelineIndex, set, std::move(signature)});
        }
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe.layout,
                                iDesc->resourcesSpaceIndex, 1, &set, 0, nullptr);
    }
    vkCmdDispatch(cmd, dDesc.gridWidth, dDesc.gridHeight, 1);
    return true;
}

extern "C" int caustica_nrd_probe(void) {
    const nrd::LibraryDesc* desc = nrd::GetLibraryDesc();
    return desc ? desc->versionMajor * 10000 + desc->versionMinor * 100 + desc->versionBuild : 0;
}

extern "C" int caustica_nrd_abi_version(void) { return CAUSTICA_NRD_ABI_VERSION; }
extern "C" int caustica_nrd_normal_encoding(void) {
    const nrd::LibraryDesc* desc = nrd::GetLibraryDesc();
    return desc ? int(desc->normalEncoding) : -1;
}
extern "C" int caustica_nrd_roughness_encoding(void) {
    const nrd::LibraryDesc* desc = nrd::GetLibraryDesc();
    return desc ? int(desc->roughnessEncoding) : -1;
}

static bool queueFamilySupportsCompute(VkPhysicalDevice physical, uint32_t family) {
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
    if (family >= count) return false;
    std::vector<VkQueueFamilyProperties> properties(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, properties.data());
    return (properties[family].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0;
}

static bool supportsNrdFormats(CausticaNrd* c) {
    const nrd::InstanceDesc* desc = nrd::GetInstanceDesc(*c->instance);
    auto supports = [c](const nrd::TextureDesc& texture) {
        VkFormat format = nrdToVkFormat(texture.format);
        if (format == VK_FORMAT_UNDEFINED) return false;
        VkFormatProperties props{};
        vkGetPhysicalDeviceFormatProperties(c->physical, format, &props);
        const VkFormatFeatureFlags required =
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
        return (props.optimalTilingFeatures & required) == required;
    };
    for (uint32_t i = 0; i < desc->permanentPoolSize; ++i)
        if (!supports(desc->permanentPool[i])) return false;
    for (uint32_t i = 0; i < desc->transientPoolSize; ++i)
        if (!supports(desc->transientPool[i])) return false;
    return true;
}

extern "C" int caustica_nrd_create(
    uint64_t vk_device, uint64_t vk_physical, uint64_t get_device_proc_addr,
    uint32_t width, uint32_t height, void** out_ctx)
{
    return caustica_nrd_create_v2(vk_device, vk_physical, get_device_proc_addr, width, height,
                                  0, UINT32_MAX, out_ctx);
}

extern "C" int caustica_nrd_create_v2(
    uint64_t vk_device, uint64_t vk_physical, uint64_t get_device_proc_addr,
    uint32_t width, uint32_t height, uint32_t graphics_queue_family,
    uint32_t compute_queue_family, void** out_ctx)
{
    if (!out_ctx || !vk_device || !vk_physical || !get_device_proc_addr || !width || !height) return -1;
    auto* c = new (std::nothrow) CausticaNrd();
    if (!c) return -2;
    c->device = (VkDevice)vk_device;
    c->physical = (VkPhysicalDevice)vk_physical;
    c->getDeviceProcAddr = (PFN_vkGetDeviceProcAddr)get_device_proc_addr;
    VkPhysicalDeviceProperties deviceProperties{};
    vkGetPhysicalDeviceProperties(c->physical, &deviceProperties);
    // AMDVLK has historically been less forgiving around push descriptors. Core descriptor
    // sets also let us cache immutable NRD bindings instead of rewriting driver state per pass.
    if (deviceProperties.vendorID != 0x1002) {
        c->cmdPushDescriptorSetKHR = (PFN_vkCmdPushDescriptorSetKHR)
            c->getDeviceProcAddr(c->device, "vkCmdPushDescriptorSetKHR");
    }
    if (!queueFamilySupportsCompute(c->physical, graphics_queue_family) ||
        (compute_queue_family != UINT32_MAX &&
         !queueFamilySupportsCompute(c->physical, compute_queue_family))) {
        delete c;
        return -3;
    }
    c->queueFamilies[0] = graphics_queue_family;
    c->queueFamilyCount = 1;
    if (compute_queue_family != UINT32_MAX && compute_queue_family != graphics_queue_family) {
        c->queueFamilies[1] = compute_queue_family;
        c->queueFamilyCount = 2;
    }

    VkPhysicalDeviceFeatures features{};
    vkGetPhysicalDeviceFeatures(c->physical, &features);
    if (!features.shaderStorageImageExtendedFormats) {
        delete c;
        return -7;
    }

    nrd::DenoiserDesc denoisers[] = {
        {nrd::Identifier(nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR), nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR},
        {nrd::Identifier(nrd::Denoiser::SIGMA_SHADOW), nrd::Denoiser::SIGMA_SHADOW},
    };
    nrd::InstanceCreationDesc idesc{{}, denoisers, 2};
    if (nrd::CreateInstance(idesc, c->instance) != nrd::Result::SUCCESS) {
        delete c;
        return -4;
    }
    if (!supportsNrdFormats(c)) {
        destroyAll(c);
        delete c;
        return -8;
    }

    // Conservative one-path-per-pixel baseline. Valid guides, confidence and disocclusion
    // drive rejection; spatial radius is not a substitute for the standard NRD contract.
    nrd::ReblurSettings reblur{};
    reblur.maxAccumulatedFrameNum = 24;
    reblur.maxFastAccumulatedFrameNum = 4;
    reblur.maxStabilizedFrameNum = 24;
    reblur.historyFixFrameNum = 3;
    reblur.historyFixBasePixelStride = 6;
    reblur.historyFixAlternatePixelStride = 12;
    // Spatial: ADAPTIVE prepass blur (v0.6.11) - fix near-field raw noise.
    // Problem: 120px prepass works at distance but FAILS at near-field (1-2 blocks).
    // Root cause: At near-field, 120px radius samples across HUGE depth discontinuities
    //             (edge of nearby wall) → invalid samples → NRD falls back to raw input.
    // Solution: Use SMALLER prepass at near-field, LARGER at distance.
    // But NRD doesn't support per-pixel radius, so we choose a MIDDLE GROUND:
    //   - Reduce from 120/150 to 60/80 (still aggressive but won't fail near-field)
    //   - Rely more on temporal accumulation (64 frames) than spatial
    reblur.diffusePrepassBlurRadius = 8.0f;
    reblur.specularPrepassBlurRadius = 12.0f;
    reblur.usePrepassOnlyForSpecularMotionEstimation = true;
    reblur.minBlurRadius = 1.0f;
    reblur.maxBlurRadius = 30.0f;
    reblur.fastHistoryClampingSigmaScale = 2.0f;
    reblur.lobeAngleFraction = 0.30f;
    reblur.roughnessFraction = 0.30f;
    reblur.planeDistanceSensitivity = 0.02f;
    reblur.minHitDistanceWeight = 0.10f;
    reblur.fireflySuppressorMinRelativeScale = 2.0f;
    reblur.enableAntiFirefly = true;
    reblur.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::AREA_5X5;
    reblur.responsiveAccumulationSettings.roughnessThreshold = 0.15f;
    reblur.responsiveAccumulationSettings.minAccumulatedFrameNum = 2;
    // Antilag remains enabled; confidence/disocclusion inputs accelerate unstable history.
    // Problem: "peripheral noise around a clear central area" - NRD works in center but fails at edges.
    // Root cause: Antilag at 4.0/6.0 is TOO AGGRESSIVE at screen edges where:
    //   - Motion vectors are less reliable (reprojection OOB)
    //   - Spatial samples are asymmetric (fewer neighbors)
    //   - History is frequently rejected → fallback to raw noisy input
    reblur.antilagSettings.luminanceSigmaScale = 2.0f;
    reblur.antilagSettings.luminanceSensitivity = 3.0f;
    reblur.convergenceSettings.s = 1.0f;
    reblur.convergenceSettings.b = 0.20f;
    reblur.convergenceSettings.p = 0.80f;
    nrd::SetDenoiserSettings(*c->instance, nrd::Identifier(nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR), &reblur);

    nrd::SigmaSettings sigma{};
    sigma.planeDistanceSensitivity = 0.02f;
    sigma.maxStabilizedFrameNum = 5;
    nrd::SetDenoiserSettings(*c->instance, nrd::Identifier(nrd::Denoiser::SIGMA_SHADOW), &sigma);

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

extern "C" int caustica_nrd_dispatch_v2(
    void* ctx, uint64_t vk_command_buffer,
    uint64_t in_diff_image, uint64_t in_diff_view,
    uint64_t in_spec_image, uint64_t in_spec_view,
    uint64_t in_mv_image, uint64_t in_mv_view,
    uint64_t in_normal_image, uint64_t in_normal_view,
    uint64_t in_viewz_image, uint64_t in_viewz_view,
    uint64_t in_shadow_image, uint64_t in_shadow_view,
    uint64_t in_diff_conf_image, uint64_t in_diff_conf_view,
    uint64_t in_spec_conf_image, uint64_t in_spec_conf_view,
    uint64_t in_disocclusion_image, uint64_t in_disocclusion_view,
    uint64_t out_diff_image, uint64_t out_diff_view,
    uint64_t out_spec_image, uint64_t out_spec_view,
    uint64_t out_shadow_image, uint64_t out_shadow_view,
    const float* view_to_clip, const float* view_to_clip_prev,
    const float* world_to_view, const float* world_to_view_prev,
    float jitter_x, float jitter_y, float jitter_x_prev, float jitter_y_prev,
    float light_dir_x, float light_dir_y, float light_dir_z,
    uint32_t frame_index, int reset)
{
    if (!ctx || !vk_command_buffer) return -1;
    auto* c = static_cast<CausticaNrd*>(ctx);
    VkCommandBuffer cmd = (VkCommandBuffer)vk_command_buffer;
    recordPoolInitialization(c, cmd);

    setUserTex(c, nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST, (VkImage)in_diff_image, (VkImageView)in_diff_view);
    setUserTex(c, nrd::ResourceType::IN_SPEC_RADIANCE_HITDIST, (VkImage)in_spec_image, (VkImageView)in_spec_view);
    setUserTex(c, nrd::ResourceType::IN_MV, (VkImage)in_mv_image, (VkImageView)in_mv_view);
    setUserTex(c, nrd::ResourceType::IN_NORMAL_ROUGHNESS, (VkImage)in_normal_image, (VkImageView)in_normal_view);
    setUserTex(c, nrd::ResourceType::IN_VIEWZ, (VkImage)in_viewz_image, (VkImageView)in_viewz_view);
    setUserTex(c, nrd::ResourceType::IN_PENUMBRA, (VkImage)in_shadow_image, (VkImageView)in_shadow_view);
    setUserTex(c, nrd::ResourceType::IN_DIFF_CONFIDENCE, (VkImage)in_diff_conf_image, (VkImageView)in_diff_conf_view);
    setUserTex(c, nrd::ResourceType::IN_SPEC_CONFIDENCE, (VkImage)in_spec_conf_image, (VkImageView)in_spec_conf_view);
    setUserTex(c, nrd::ResourceType::IN_DISOCCLUSION_THRESHOLD_MIX, (VkImage)in_disocclusion_image, (VkImageView)in_disocclusion_view);
    setUserTex(c, nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST, (VkImage)out_diff_image, (VkImageView)out_diff_view);
    setUserTex(c, nrd::ResourceType::OUT_SPEC_RADIANCE_HITDIST, (VkImage)out_spec_image, (VkImageView)out_spec_view);
    setUserTex(c, nrd::ResourceType::OUT_SHADOW_TRANSLUCENCY, (VkImage)out_shadow_image, (VkImageView)out_shadow_view);

    nrd::SigmaSettings sigma{};
    sigma.lightDirection[0] = light_dir_x;
    sigma.lightDirection[1] = light_dir_y;
    sigma.lightDirection[2] = light_dir_z;
    sigma.planeDistanceSensitivity = 0.02f;
    sigma.maxStabilizedFrameNum = 5;
    nrd::SetDenoiserSettings(*c->instance, nrd::Identifier(nrd::Denoiser::SIGMA_SHADOW), &sigma);

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
    // Standard primary threshold plus an alternate selected by the application mix texture.
    cs.disocclusionThreshold = 0.03f;
    cs.disocclusionThresholdAlternate = 0.05f;
    cs.isHistoryConfidenceAvailable = true;
    cs.isDisocclusionThresholdMixAvailable = true;
    cs.isMotionVectorInWorldSpace = false;
    cs.enableValidation = false;

    if (nrd::SetCommonSettings(*c->instance, cs) != nrd::Result::SUCCESS) return -2;

    nrd::Identifier ids[] = {
        nrd::Identifier(nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR),
        nrd::Identifier(nrd::Denoiser::SIGMA_SHADOW),
    };
    const nrd::DispatchDesc* dispatches = nullptr;
    uint32_t nDisp = 0;
    if (nrd::GetComputeDispatches(*c->instance, ids, 2, dispatches, nDisp) != nrd::Result::SUCCESS)
        return -3;
    for (uint32_t i = 0; i < nDisp; ++i) {
        if (!dispatchOne(c, cmd, dispatches[i])) return -5;
    }
    return 0;
}
