package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Spatial-only multi-pass 3x3 joint bilateral. No temporal history (no ghost trails).
 * Pure SPIR-V — used as the safe denoise path on AMD while FFX temporal/à-trous is
 * being stabilised, and as a mild residual polish after NRD REBLUR.
 * Odd number of passes so the final write lands in {@code outColor}
 * when ping-ponging with an intermediate buffer.
 */
public final class BilateralDenoiseBackend implements CausticaDenoiseBackend {

    private static final String SHADER_DIR = "/caustica/rt/";

    private final int passes;
    private final float depthSigma;
    private final float normalSigma;
    private final float colorSigma;
    private final String label;

    private boolean ready;
    private int width;
    private int height;

    private long dsl;
    private long pool;
    private long set;
    private long layout;
    private long pipeline;
    private RtImage temp;

    /** Default strong spatial (5 passes) for pure bilateral backend. */
    public BilateralDenoiseBackend() {
        this(5, 0.04f, 0.18f, 1.2f, "bilateral");
    }

    /**
     * @param passes odd pass count (forced odd); 3 is a good residual polish after NRD
     */
    public BilateralDenoiseBackend(int passes, float depthSigma, float normalSigma, float colorSigma, String label) {
        this.passes = (passes < 1) ? 1 : (passes | 1); // force odd
        this.depthSigma = depthSigma;
        this.normalSigma = normalSigma;
        this.colorSigma = colorSigma;
        this.label = label != null ? label : "bilateral";
    }

    /** Mild residual polish after REBLUR (ghost-safe spatial only). */
    public static BilateralDenoiseBackend residualAfterNrd() {
        return new BilateralDenoiseBackend(3, 0.035f, 0.14f, 0.85f, "nrd-residual-bilateral");
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        ready = true;
    }

    @Override
    public void ensureSized(int width, int height) {
        if (!ready) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return;
        }
        if (pipeline == 0L) {
            createPipeline(ctx);
        }
        if (this.width == width && this.height == height && temp != null) {
            return;
        }
        if (temp != null) {
            temp.destroy();
            temp = null;
        }
        temp = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "bilateral temp");
        this.width = width;
        this.height = height;
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready || pipeline == 0L || temp == null || width <= 0 || height <= 0) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }

        // Odd passes → final result in outColor after ping-pong with temp.
        for (int pass = 0; pass < passes; pass++) {
            RtImage src;
            RtImage dst;
            if ((pass & 1) == 0) {
                src = (pass == 0) ? inColor : temp;
                dst = outColor;
            } else {
                src = outColor;
                dst = temp;
            }
            bind(ctx, src, inNormal, inDepth, dst);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label + " p" + pass)) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(set), null);
                ByteBuffer push = stack.malloc(16);
                // Tight depth/normal keep block edges; colour sigma tuned per-mode.
                push.putFloat(0, depthSigma);
                push.putFloat(4, normalSigma);
                push.putFloat(8, colorSigma);
                push.putFloat(12, 1.0f);
                VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            }
            barrier(stack, cmd, dst.image);
        }
        return true;
    }

    @Override
    public void resetHistory() {
        // Spatial-only — no temporal history.
    }

    @Override
    public void destroy() {
        if (!ready) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx != null) {
            VkDevice vk = ctx.vk();
            if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
        }
        if (temp != null) {
            temp.destroy();
            temp = null;
        }
        ready = false;
        width = 0;
        height = 0;
        pipeline = 0L;
        layout = 0L;
        pool = 0L;
        dsl = 0L;
        set = 0L;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    private void barrier(MemoryStack stack, VkCommandBuffer cmd, long image) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(1, stack);
        barriers.get(0).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private void createPipeline(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                binds.get(i).binding(i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(bilateral)");
            dsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(bilateral)");
            pool = p.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet), "vkAllocateDescriptorSets(bilateral)");
            set = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(bilateral)");
            layout = p.get(0);

            long module = loadModule(ctx.vk(), stack, "bilateral.comp.spv");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p), "vkCreateComputePipelines(bilateral)");
            pipeline = p.get(0);
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private void bind(RtContext ctx, RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage outColor) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] images = {inColor, inNormal, inDepth, outColor};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = BilateralDenoiseBackend.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
