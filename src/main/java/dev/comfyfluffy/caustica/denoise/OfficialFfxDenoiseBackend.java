package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.ffx.denoiser.FfxDenoiserRuntime;
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
import org.lwjgl.vulkan.VkImageCopy;
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
 * Official FidelityFX Denoiser path (Shadow + Reflection), Caustica-hosted SPIR-V.
 *
 * <p>Energy-correct composite:
 * {@code beauty + unshadowed*(S_clean-S_raw) + (R_clean-R_raw)} — never multiplies GI/sky by shadow.
 *
 * <p>Fail-open: seed with raw beauty; any filter failure leaves that seed.
 */
public final class OfficialFfxDenoiseBackend implements CausticaDenoiseBackend {

    private static final String SHADER_DIR = "/caustica/rt/";
    private static final int[] SPATIAL_STEPS = {1, 2, 4};

    private boolean ready;
    private int width;
    private int height;
    private boolean shadowHistoryValid;
    private boolean reflHistoryValid;
    private boolean hardReset;

    private RtImage shadowHit;
    private RtImage diffuse;      // unshadowed primary direct
    private RtImage reflection;   // raw specular
    private RtImage specMotion;   // optional; falls back to surface motion

    /** Last frame's cleaned shadow mask (rg16f: r=mask) after a successful shadow pass; null if skipped. */
    private RtImage lastCleanShadow;
    /** Last frame's cleaned specular (rgba16f) after a successful reflection pass; null if skipped. */
    private RtImage lastCleanReflection;
    private int lastCompositeFlags;

    // Shadow pipelines
    private long shReproDsl, shReproPool, shReproSet, shReproLayout, shReproPipe;
    private long shSpatDsl, shSpatPool, shSpatSet, shSpatLayout, shSpatPipe;
    // Reflection pipelines
    private long rfReproDsl, rfReproPool, rfReproSet, rfReproLayout, rfReproPipe;
    private long rfSpatDsl, rfSpatPool, rfSpatSet, rfSpatLayout, rfSpatPipe;
    // Composite
    private long compDsl, compPool, compSet, compLayout, compPipe;

    // Shadow buffers (rg16f: r=mask, g=count)
    private RtImage historyShadow;
    private RtImage shReproBuf;
    private RtImage shSpatA;
    private RtImage shSpatB;
    // Reflection buffers (rgba16f: rgb=spec, a=count)
    private RtImage historyRefl;
    private RtImage rfReproBuf;
    private RtImage rfSpatA;
    private RtImage rfSpatB;

    private boolean nativeProbeLogged;

    @Override
    public String name() {
        return "ffx-official";
    }

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        if (!nativeProbeLogged) {
            nativeProbeLogged = true;
            var ver = FfxDenoiserRuntime.INSTANCE.tryLoad();
            if (ver.isPresent()) {
                CausticaMod.LOGGER.info(
                        "OfficialFfxDenoiseBackend: native probe OK (packed={}); SPIR-V shadow+reflection filters active",
                        ver.getAsInt());
            } else {
                CausticaMod.LOGGER.info(
                        "OfficialFfxDenoiseBackend: native probe unavailable; SPIR-V shadow+reflection still active");
            }
        }
        ready = true;
    }

    public void setSplitBuffers(RtImage shadowHit, RtImage diffuse, RtImage reflection) {
        this.shadowHit = shadowHit;
        this.diffuse = diffuse;
        this.reflection = reflection;
    }

    /** Optional specular motion vectors; if null, surface motion is used for reflection reproject. */
    public void setSpecMotion(RtImage specMotion) {
        this.specMotion = specMotion;
    }

    /**
     * Cleaned shadow after the last {@link #dispatch} (for hybrid FFX→NRD). Valid only until the next
     * ensureSized/destroy. Null if the shadow pass was skipped.
     */
    public RtImage lastCleanShadow() {
        return lastCleanShadow;
    }

    /** Cleaned specular after the last {@link #dispatch}. Null if reflection pass was skipped. */
    public RtImage lastCleanReflection() {
        return lastCleanReflection;
    }

    /** Bit0 = shadow applied, bit1 = reflection applied (same as composite push flags). */
    public int lastCompositeFlags() {
        return lastCompositeFlags;
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
        if (shReproPipe == 0L) {
            try {
                createPipelines(ctx);
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("OfficialFfxDenoiseBackend pipeline create failed; fail-open to raw beauty", t);
                destroyPipelines(ctx);
                return;
            }
        }
        if (this.width == width && this.height == height && historyShadow != null && historyRefl != null) {
            return;
        }
        destroyImages();
        historyShadow = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT, "ffx-off shadow history");
        shReproBuf = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT, "ffx-off shadow reproject");
        shSpatA = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT, "ffx-off shadow spat A");
        shSpatB = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT, "ffx-off shadow spat B");
        historyRefl = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx-off refl history");
        rfReproBuf = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx-off refl reproject");
        rfSpatA = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx-off refl spat A");
        rfSpatB = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ffx-off refl spat B");
        this.width = width;
        this.height = height;
        shadowHistoryValid = false;
        reflHistoryValid = false;
        hardReset = true;
    }

    @Override
    public void dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready || width <= 0 || height <= 0 || outColor == null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return;
        }

        lastCleanShadow = null;
        lastCleanReflection = null;
        lastCompositeFlags = 0;

        boolean canComposite = shadowHit != null && diffuse != null && reflection != null
                && inColor != null && compPipe != 0L && shSpatA != null && rfSpatA != null;

        // Seed: pure beauty (flags=0)
        if (canComposite) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off seed beauty")) {
                bindComposite(ctx, diffuse, shadowHit, shSpatA, inColor, reflection, rfSpatA, outColor);
                dispatchComposite(stack, cmd, 0);
            }
            barrier(stack, cmd, outColor.image);
        } else {
            return;
        }

        int flags = 0;
        boolean reset = hardReset || !shadowHistoryValid;

        // --- Shadow denoise ---
        RtImage cleanShadow = shSpatA;
        if (shReproPipe != 0L && shSpatPipe != 0L && historyShadow != null
                && inNormal != null && inDepth != null && inMotion != null) {
            try {
                bindImages(ctx, shReproSet, shadowHit, inDepth, inNormal, inMotion, historyShadow, shReproBuf);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off shadow reproject")) {
                    dispatchPush(stack, cmd, shReproPipe, shReproLayout, shReproSet, true, reset);
                }
                barrier(stack, cmd, shReproBuf.image);

                cleanShadow = spatialPingPong(stack, cmd, ctx, shSpatPipe, shSpatLayout, shSpatSet,
                        shReproBuf, shSpatA, shSpatB, inDepth, inNormal, "shadow");
                copyImage(stack, cmd, cleanShadow, historyShadow);
                shadowHistoryValid = true;
                barrier(stack, cmd, historyShadow.image);
                flags |= 1;
                lastCleanShadow = cleanShadow;
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("OfficialFfx shadow path failed; skipping shadow delta", t);
                cleanShadow = shSpatA;
            }
        }

        // --- Reflection denoise ---
        RtImage cleanRefl = rfSpatA;
        RtImage reflMv = (specMotion != null) ? specMotion : inMotion;
        reset = hardReset || !reflHistoryValid;
        if (rfReproPipe != 0L && rfSpatPipe != 0L && historyRefl != null
                && reflection != null && inNormal != null && inDepth != null && reflMv != null) {
            try {
                bindImages(ctx, rfReproSet, reflection, inDepth, inNormal, reflMv, historyRefl, rfReproBuf);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off refl reproject")) {
                    dispatchPush(stack, cmd, rfReproPipe, rfReproLayout, rfReproSet, true, reset);
                }
                barrier(stack, cmd, rfReproBuf.image);

                cleanRefl = spatialPingPong(stack, cmd, ctx, rfSpatPipe, rfSpatLayout, rfSpatSet,
                        rfReproBuf, rfSpatA, rfSpatB, inDepth, inNormal, "refl");
                copyImage(stack, cmd, cleanRefl, historyRefl);
                reflHistoryValid = true;
                barrier(stack, cmd, historyRefl.image);
                flags |= 2;
                lastCleanReflection = cleanRefl;
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("OfficialFfx reflection path failed; skipping reflection delta", t);
                cleanRefl = rfSpatA;
            }
        }

        hardReset = false;
        lastCompositeFlags = flags;

        // Final composite (also the hybrid FFX stage output before NRD residual)
        try {
            bindComposite(ctx, diffuse, shadowHit, cleanShadow, inColor, reflection, cleanRefl, outColor);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off composite f=" + flags)) {
                dispatchComposite(stack, cmd, flags);
            }
            barrier(stack, cmd, outColor.image);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("OfficialFfx composite failed; restoring beauty seed", t);
            try {
                bindComposite(ctx, diffuse, shadowHit, shSpatA, inColor, reflection, rfSpatA, outColor);
                dispatchComposite(stack, cmd, 0);
                lastCompositeFlags = 0;
            } catch (Throwable ignored) {
            }
        }
    }

    private RtImage spatialPingPong(MemoryStack stack, VkCommandBuffer cmd, RtContext ctx,
                                    long pipe, long layout, long set,
                                    RtImage input, RtImage a, RtImage b,
                                    RtImage depth, RtImage normal, String tag) {
        RtImage finalDst = a;
        for (int i = 0; i < SPATIAL_STEPS.length; i++) {
            RtImage src;
            RtImage dst;
            if (i == 0) {
                src = input;
                dst = a;
            } else if ((i & 1) == 1) {
                src = a;
                dst = b;
            } else {
                src = b;
                dst = a;
            }
            finalDst = dst;
            bindImages(ctx, set, src, depth, normal, dst);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ffx-off " + tag + " spat s" + SPATIAL_STEPS[i])) {
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0,
                        stack.longs(set), null);
                ByteBuffer push = stack.malloc(16);
                push.putInt(0, SPATIAL_STEPS[i]);
                push.putFloat(4, 0.03f);
                push.putFloat(8, 0.15f);
                push.putFloat(12, 0.0f);
                VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
            }
            barrier(stack, cmd, dst.image);
        }
        return finalDst;
    }

    private void dispatchPush(MemoryStack stack, VkCommandBuffer cmd, long pipe, long layout, long set,
                              boolean reproject, boolean reset) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0,
                stack.longs(set), null);
        ByteBuffer push = stack.malloc(16);
        push.putFloat(0, 1.0f / Math.max(1, width));
        push.putFloat(4, 1.0f / Math.max(1, height));
        push.putFloat(8, 0.02f);
        push.putFloat(12, reset ? 1.0f : 0.0f);
        VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
    }

    private void dispatchComposite(MemoryStack stack, VkCommandBuffer cmd, int useFlags) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compPipe);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compLayout, 0,
                stack.longs(compSet), null);
        ByteBuffer push = stack.malloc(16);
        push.putInt(0, useFlags);
        push.putFloat(4, 0.0f);
        push.putFloat(8, 0.0f);
        push.putFloat(12, 0.0f);
        VK10.vkCmdPushConstants(cmd, compLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
    }

    @Override
    public void resetHistory() {
        hardReset = true;
        shadowHistoryValid = false;
        reflHistoryValid = false;
    }

    @Override
    public void destroy() {
        RtContext ctx = RtContext.get();
        destroyImages();
        if (ctx != null) {
            destroyPipelines(ctx);
        }
        shadowHit = null;
        diffuse = null;
        reflection = null;
        specMotion = null;
        ready = false;
        width = 0;
        height = 0;
        shadowHistoryValid = false;
        reflHistoryValid = false;
    }

    @Override
    public boolean isReady() {
        return ready && compPipe != 0L;
    }

    private void createPipelines(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            shReproDsl = createDsl(ctx, stack, 6);
            shReproPool = createPool(ctx, stack, 6);
            shReproSet = allocSet(ctx, stack, shReproPool, shReproDsl);
            shReproLayout = createLayout(ctx, stack, shReproDsl, 16);
            shReproPipe = createPipeline(ctx, stack, shReproLayout, "shadow_reproject.comp.spv");

            shSpatDsl = createDsl(ctx, stack, 4);
            shSpatPool = createPool(ctx, stack, 4);
            shSpatSet = allocSet(ctx, stack, shSpatPool, shSpatDsl);
            shSpatLayout = createLayout(ctx, stack, shSpatDsl, 16);
            shSpatPipe = createPipeline(ctx, stack, shSpatLayout, "shadow_spatial.comp.spv");

            rfReproDsl = createDsl(ctx, stack, 6);
            rfReproPool = createPool(ctx, stack, 6);
            rfReproSet = allocSet(ctx, stack, rfReproPool, rfReproDsl);
            rfReproLayout = createLayout(ctx, stack, rfReproDsl, 16);
            rfReproPipe = createPipeline(ctx, stack, rfReproLayout, "reflection_reproject.comp.spv");

            rfSpatDsl = createDsl(ctx, stack, 4);
            rfSpatPool = createPool(ctx, stack, 4);
            rfSpatSet = allocSet(ctx, stack, rfSpatPool, rfSpatDsl);
            rfSpatLayout = createLayout(ctx, stack, rfSpatDsl, 16);
            rfSpatPipe = createPipeline(ctx, stack, rfSpatLayout, "reflection_spatial.comp.spv");

            // unshadowed, shadowRaw, shadowClean, beauty, specRaw, specClean, out
            compDsl = createDsl(ctx, stack, 7);
            compPool = createPool(ctx, stack, 7);
            compSet = allocSet(ctx, stack, compPool, compDsl);
            compLayout = createLayout(ctx, stack, compDsl, 16);
            compPipe = createPipeline(ctx, stack, compLayout, "denoise_composite.comp.spv");
        }
    }

    private void destroyPipelines(RtContext ctx) {
        VkDevice vk = ctx.vk();
        destroyPipe(vk, shReproPipe, shReproLayout, shReproPool, shReproDsl);
        destroyPipe(vk, shSpatPipe, shSpatLayout, shSpatPool, shSpatDsl);
        destroyPipe(vk, rfReproPipe, rfReproLayout, rfReproPool, rfReproDsl);
        destroyPipe(vk, rfSpatPipe, rfSpatLayout, rfSpatPool, rfSpatDsl);
        destroyPipe(vk, compPipe, compLayout, compPool, compDsl);
        shReproPipe = shReproLayout = shReproPool = shReproDsl = shReproSet = 0L;
        shSpatPipe = shSpatLayout = shSpatPool = shSpatDsl = shSpatSet = 0L;
        rfReproPipe = rfReproLayout = rfReproPool = rfReproDsl = rfReproSet = 0L;
        rfSpatPipe = rfSpatLayout = rfSpatPool = rfSpatDsl = rfSpatSet = 0L;
        compPipe = compLayout = compPool = compDsl = compSet = 0L;
    }

    private static void destroyPipe(VkDevice vk, long pipeline, long layout, long pool, long dsl) {
        if (pipeline != 0L) VK10.vkDestroyPipeline(vk, pipeline, null);
        if (layout != 0L) VK10.vkDestroyPipelineLayout(vk, layout, null);
        if (pool != 0L) VK10.vkDestroyDescriptorPool(vk, pool, null);
        if (dsl != 0L) VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
    }

    private void destroyImages() {
        if (historyShadow != null) { historyShadow.destroy(); historyShadow = null; }
        if (shReproBuf != null) { shReproBuf.destroy(); shReproBuf = null; }
        if (shSpatA != null) { shSpatA.destroy(); shSpatA = null; }
        if (shSpatB != null) { shSpatB.destroy(); shSpatB = null; }
        if (historyRefl != null) { historyRefl.destroy(); historyRefl = null; }
        if (rfReproBuf != null) { rfReproBuf.destroy(); rfReproBuf = null; }
        if (rfSpatA != null) { rfSpatA.destroy(); rfSpatA = null; }
        if (rfSpatB != null) { rfSpatB.destroy(); rfSpatB = null; }
    }

    private void bindComposite(RtContext ctx, RtImage unshadowed, RtImage shadowRaw, RtImage shadowClean,
                               RtImage beauty, RtImage specRaw, RtImage specClean, RtImage out) {
        bindImages(ctx, compSet, unshadowed, shadowRaw, shadowClean, beauty, specRaw, specClean, out);
    }

    private static void bindImages(RtContext ctx, long set, RtImage... images) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(images.length, stack);
            for (int i = 0; i < images.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private static long createDsl(RtContext ctx, MemoryStack stack, int count) {
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            binds.get(i).binding(i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(ffx-off)");
        return p.get(0);
    }

    private static long createPool(RtContext ctx, MemoryStack stack, int count) {
        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
        poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(count);
        VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p), "vkCreateDescriptorPool(ffx-off)");
        return p.get(0);
    }

    private static long allocSet(RtContext ctx, MemoryStack stack, long pool, long dsl) {
        VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet), "vkAllocateDescriptorSets(ffx-off)");
        return pSet.get(0);
    }

    private static long createLayout(RtContext ctx, MemoryStack stack, long dsl, int pushSize) {
        VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushSize);
        VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pcr);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(ffx-off)");
        return p.get(0);
    }

    private static long createPipeline(RtContext ctx, MemoryStack stack, long layout, String spvName) {
        long module = loadModule(ctx.vk(), stack, spvName);
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, cpci, null, p),
                    "vkCreateComputePipelines(" + spvName + ")");
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = OfficialFfxDenoiseBackend.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V: " + name, e);
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

    private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(1, stack);
        barriers.get(0).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private static void copyImage(MemoryStack stack, VkCommandBuffer cmd, RtImage src, RtImage dst) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.dstSubresource().set(VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.extent().set(src.width, src.height, 1);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
    }
}
