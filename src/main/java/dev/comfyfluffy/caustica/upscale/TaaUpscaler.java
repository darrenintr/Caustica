package dev.comfyfluffy.caustica.upscale;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

/**
 * TAAU - Temporal Anti-Aliasing Upsampler.
 *
 * <p>Pure-compute upscaler. No SDK, no vendor lock-in, no native binary. Works on every Vulkan-capable
 * GPU on every OS (Linux + Windows + macOS). Renders the path tracer at a downscaled render resolution,
 * then upsamples to display resolution with a TAA-style temporal accumulator (see {@code taau.comp}).
 *
 * <p>Quality presets map to render-scale factors:
 * <ul>
 *   <li>0 = NATIVE (1.00x, no savings)</li>
 *   <li>1 = QUALITY (0.67x render)</li>
 *   <li>2 = BALANCED (0.75x render)</li>
 *   <li>3 = PERFORMANCE (0.50x render)</li>
 *   <li>4 = ULTRA PERFORMANCE (0.40x render)</li>
 * </ul>
 *
 * <p>Why this exists: optional SDK-backed providers have platform and hardware constraints. TAAU is the
 * universal fallback -- lower quality than dedicated temporal super-resolution providers but
 * works on anything and gives meaningful temporal stability (the main thing the user was missing
 * with spp=1 + NRD-only + no upscaler).
 */
public final class TaaUpscaler implements Upscaler {

    private static final String SHADER_PATH = "/caustica/rt/taau.comp.spv";
    private static final String CAS_SHADER_PATH = "/caustica/rt/cas.comp.spv";

    private final VulkanDevice vkDevice;
    private boolean ready;
    private int renderWidth;
    private int renderHeight;
    private int displayWidth;
    private int displayHeight;

    // Vulkan resources — temporal pass
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private final long[] descriptorSets = new long[2];
    // 9 bindings (0..8): 5 storage inputs, 2 combined-image-samplers (history + jitterGuide),
    //                   2 storage outputs. v0.6.8 added binding 8 (jitterGuide).
    private final long[][] boundViews = new long[2][9];
    private long sampler;
    // v0.6.8+: per-tile jitter guide written by world.rgen (R8G8_UNORM, render res). TAAU
    // reads this in q2TrilinearHistory to add the per-tile offset to the reproject UV.
    // Optional — when null the binding is filled with a placeholder (any view) and the
    // sampler returns 0.5 (= 0 offset), preserving legacy behaviour.
    private RtImage jitterGuide;
    // Vulkan resources — RCAS post-pass (AMD CAS sharpening, same algorithm as FFX RCAS).
    // RCAS is a 5-tap contrast-adaptive sharpen, runs after temporal blend at display res.
    private long casDescriptorSetLayout;
    private long casPipelineLayout;
    private long casPipeline;
    private long casDescriptorPool;
    private long[] casDescriptorSets = new long[0];
    private long[][] casBoundViews = new long[0][];
    // Intermediate scratch: RCAS reads from temporal pass output, writes to final out.
    // We can do RCAS in-place if temporal output is not the final swapchain, but to keep
    // the temporal history copy clean we always have TAAU write into a separate "casSrc"
    // and RCAS write into out. TAAU's existing logic writes directly to `out` though, so
    // we keep that and use the same image for both stages (RCAS reads from outColor which
    // was just written by TAAU, writes back to outColor — descriptor alias).
    private RtImage casScratch;

    // History at display resolution (two ping-pong slots)
    private RtImage historyA;
    private RtImage historyB;
    private boolean historyAIsCurrent = true;
    private boolean historyInitialized;

    private TaaUpscaler(VulkanDevice device) {
        this.vkDevice = device;
    }

    public static TaaUpscaler tryCreate() {
        try {
            if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
                return null;
            }
            TaaUpscaler upscaler = new TaaUpscaler(device);
            CausticaMod.LOGGER.info("TAAU upscaler created (pure compute, cross-vendor, Linux-friendly)");
            return upscaler;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("TAAU upscaler creation failed", t);
            return null;
        }
    }

    @Override
    public String id() {
        return "taau";
    }

    @Override
    public String displayName() {
        return "TAAU";
    }

    @Override
    public boolean performsTemporalReconstruction() {
        return true;
    }

    @Override
    public boolean expectsRawRenderJitter() {
        return true;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    /**
     * Set the per-tile jitter guide (R8G8_UNORM, render res) written by world.rgen. The
     * TAAU's reproject reads this and adds the per-tile offset to {@code prevUV} so the
     * history is sampled at the previous-frame position that matches the current sample's
     * sub-pixel. Optional — when null, a placeholder view is bound and the sampler returns
     * 0.5 (= 0 offset), preserving legacy behaviour.
     */
    public void setJitterGuide(RtImage jitterGuide) {
        this.jitterGuide = jitterGuide;
    }

    private float renderScaleForQuality(int quality) {
        return switch (quality) {
            case 0 -> 1.00f;
            case 1 -> 0.67f;
            case 2 -> 0.75f;
            case 3 -> 0.50f;
            default -> 0.40f;
        };
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        int quality = CausticaConfig.Rt.Upscaler.QUALITY.value();
        float scale = renderScaleForQuality(quality);
        int rw = Math.max(64, (int) (displayWidth * scale));
        int rh = Math.max(64, (int) (displayHeight * scale));
        return new int[]{rw, rh};
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                 int quality, int featureFlags) {
        if (renderWidth == this.renderWidth && renderHeight == this.renderHeight
                && displayWidth == this.displayWidth && displayHeight == this.displayHeight && ready) {
            return true;
        }
        destroy();
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;

        try {
            createPipeline();
            createCasPipeline();
            RtContext ctx = RtContext.get();
            if (ctx != null) {
                historyA = ctx.createStorageImage(displayWidth, displayHeight,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "taau history A");
                historyB = ctx.createStorageImage(displayWidth, displayHeight,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "taau history B");
                // Clear history on (re)create.
                clearImage(cmd, historyA);
                clearImage(cmd, historyB);
                // RCAS scratch at display res in the same format TAAU produces (B10G11R11
                // matches the TAAU out image, which is the same RtContext.HDR_RADIANCE_FORMAT).
                // RCAS reads from outColor (alias of casScratch.view via descriptor rebind),
                // writes to outColor — so scratch and out can be the same image.
                casScratch = ctx.createStorageImage(displayWidth, displayHeight,
                        RtContext.HDR_RADIANCE_FORMAT, "taau cas scratch");
                historyInitialized = false;
            }
            ready = true;
            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("TAAU ensureFeature failed", t);
            ready = false;
            return false;
        }
    }

    @Override
    public boolean evaluate(long cmd,
                             RtImage color, RtImage depth, RtImage motion,
                             RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                             RtImage specularMotion, RtImage specularHitDistance,
                             RtImage out,
                             int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                             float jitterX, float jitterY,
                             Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!ready || pipeline == 0L || historyA == null || historyB == null || color == null
                || depth == null || motion == null || out == null) {
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage prevHistory = historyAIsCurrent ? historyA : historyB;
            RtImage newHistory = historyAIsCurrent ? historyB : historyA;
            int setIndex = historyAIsCurrent ? 0 : 1;

            bindDescriptors(stack, setIndex, color, depth, motion, normals, diffuseAlbedo,
                    prevHistory, newHistory, out);

            ByteBuffer push = stack.malloc(56);
            push.putFloat(0, (float) renderWidth);
            push.putFloat(4, (float) renderHeight);
            push.putFloat(8, (float) displayWidth);
            push.putFloat(12, (float) displayHeight);
            // MV passed in is in render pixels; we want delta in display UV: mv / dstSize.
            push.putFloat(16, 1.0f / displayWidth);
            push.putFloat(20, 1.0f / displayHeight);
            float baseAlpha = CausticaConfig.Rt.Composite.TEMPORAL_ALPHA.value();
            float alpha = historyInitialized ? baseAlpha : 1.0f; // first frame: no blend
            push.putFloat(24, alpha);
            push.putFloat(28, CausticaConfig.Rt.Composite.TEMPORAL_DISOCCLUSION.value());
            float sharpness = CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    ? CausticaConfig.Rt.Upscaler.SHARPNESS.value() : 0.0f;
            // 0..0.5 Catmull-Rom bias. The shader further modulates per-pixel by roughness:
            // matte (rough>=0.4) gets sharpened=0 to avoid amplifying path-tracer noise,
            // glossy gets full sharpness.
            push.putFloat(32, sharpness * 0.5f);
            // varianceClipGamma: 1.0 = textbook, 1.5 = more conservative (less ghost, slightly more blur).
            // We use 1.3 as a balanced default; the shader tightens to 0.7*gamma on glossy surfaces.
            push.putFloat(36, 1.3f);
            push.putInt(40, historyInitialized ? 1 : 0);
            push.putInt(44, 0);
            // Signed render-pixel offset applied to the primary ray. taau.comp subtracts it
            // from the current-frame lookup to reconstruct the unjittered display grid.
            push.putFloat(48, jitterX);
            push.putFloat(52, jitterY);

            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDevice.vkDevice());
            VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSets[setIndex]), null);
            VK10.vkCmdPushConstants(cb, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

            int groupsX = (displayWidth + 7) / 8;
            int groupsY = (displayHeight + 7) / 8;
            VK10.vkCmdDispatch(cb, groupsX, groupsY, 1);

            // TaaUpscaler doesn't know the actual command buffer (long cmd), so the barrier is the
            // caller's responsibility (RtComposite.recordFrame inserts one after dispatch).
            historyAIsCurrent = !historyAIsCurrent;
            historyInitialized = true;

            // --- RCAS post-pass (AMD CAS sharpening, same algorithm as FFX RCAS) ---
            // Runs at display res, reads from `out` (just written by TAAU), writes back to `out`
            // in-place via descriptor aliasing. Skipped if sharpness <= 1e-4.
            float sharpen = CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    ? CausticaConfig.Rt.Upscaler.SHARPNESS.value() : 0.0f;
            if (sharpen > 1e-4f && casPipeline != 0L && casScratch != null) {
                try {
                    bindCasDescriptors(stack, out, out);
                    ByteBuffer casPush = stack.malloc(16);
                    casPush.putInt(0, displayWidth);
                    casPush.putInt(4, displayHeight);
                    casPush.putFloat(8, Math.min(sharpen, 1.0f));
                    casPush.putFloat(12, 0.0f);
                    VkCommandBuffer casCb = new VkCommandBuffer(cmd, vkDevice.vkDevice());
                    VK10.vkCmdBindPipeline(casCb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, casPipeline);
                    VK10.vkCmdBindDescriptorSets(casCb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                            casPipelineLayout, 0, stack.longs(casDescriptorSets[0]), null);
                    VK10.vkCmdPushConstants(casCb, casPipelineLayout,
                            VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, casPush);
                    int casGx = (displayWidth + 7) / 8;
                    int casGy = (displayHeight + 7) / 8;
                    VK10.vkCmdDispatch(casCb, casGx, casGy, 1);
                } catch (Throwable t) {
                    CausticaMod.LOGGER.warn("TAAU RCAS post-pass failed", t);
                }
            }
            return true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("TAAU evaluate failed", t);
            return false;
        }
    }

    @Override
    public void destroy() {
        ready = false;
        if (pipeline != 0L) {
            VK10.vkDestroyPipeline(vkDevice.vkDevice(), pipeline, null);
            pipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vkDevice.vkDevice(), pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (descriptorSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vkDevice.vkDevice(), descriptorSetLayout, null);
            descriptorSetLayout = 0L;
        }
        if (descriptorPool != 0L) {
            VK10.vkDestroyDescriptorPool(vkDevice.vkDevice(), descriptorPool, null);
            descriptorPool = 0L;
        }
        Arrays.fill(descriptorSets, 0L);
        for (long[] views : boundViews) {
            Arrays.fill(views, 0L);
        }
        if (sampler != 0L) {
            VK10.vkDestroySampler(vkDevice.vkDevice(), sampler, null);
            sampler = 0L;
        }
        if (casPipeline != 0L) {
            VK10.vkDestroyPipeline(vkDevice.vkDevice(), casPipeline, null);
            casPipeline = 0L;
        }
        if (casPipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vkDevice.vkDevice(), casPipelineLayout, null);
            casPipelineLayout = 0L;
        }
        if (casDescriptorSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vkDevice.vkDevice(), casDescriptorSetLayout, null);
            casDescriptorSetLayout = 0L;
        }
        if (casDescriptorPool != 0L) {
            VK10.vkDestroyDescriptorPool(vkDevice.vkDevice(), casDescriptorPool, null);
            casDescriptorPool = 0L;
        }
        Arrays.fill(casDescriptorSets, 0L);
        for (long[] views : casBoundViews) {
            Arrays.fill(views, 0L);
        }
        if (casScratch != null) {
            casScratch.destroy();
            casScratch = null;
        }
        if (historyA != null) { historyA.destroy(); historyA = null; }
        if (historyB != null) { historyB.destroy(); historyB = null; }
        historyInitialized = false;
        historyAIsCurrent = true;
    }

    @Override
    public void requestResetHistory() {
        historyInitialized = false;
    }

    private void createPipeline() throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 9 bindings (v0.6.8+): 5 storage image inputs (color/depth/motion/normal/albedo)
            //                      + 2 combined image samplers (history @5, jitterGuide @8)
            //                      + 2 storage images (outColor, historyOut).
            int bindings = 9;
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(bindings, stack);
            // 0..4: storage images (inColorLow, inDepthLow, inMotionLow, inNormalLow, inAlbedoLow)
            for (int i = 0; i < 5; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            // 5: combined image sampler for history
            binds.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            // 6, 7: outColor + historyOut storage
            binds.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(7).binding(7).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            // 8 (v0.6.8+): per-tile jitter guide, combined image sampler. Reuses the
            // existing `sampler` (linear clamp, same as history). When the host doesn't
            // call setJitterGuide, bindDescriptors writes a placeholder view and the
            // sampler reads 0.5 → offset = 0, preserving the legacy path.
            binds.get(8).binding(8).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer pDsl = stack.mallocLong(1);
            VK10.vkCreateDescriptorSetLayout(vkDevice.vkDevice(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds),
                    null, pDsl);
            descriptorSetLayout = pDsl.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            // 7 storage images × 2 sets = 14 (unchanged). 2 combined samplers × 2 sets = 4
            // (was 2; binding 5 history + binding 8 jitterGuide).
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(14);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
            LongBuffer pPool = stack.mallocLong(1);
            VK10.vkCreateDescriptorPool(vkDevice.vkDevice(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                            .maxSets(2).pPoolSizes(sizes),
                    null, pPool);
            descriptorPool = pPool.get(0);

            LongBuffer pSet = stack.mallocLong(2);
            VK10.vkAllocateDescriptorSets(vkDevice.vkDevice(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(descriptorPool)
                            .pSetLayouts(stack.longs(descriptorSetLayout, descriptorSetLayout)),
                    pSet);
            descriptorSets[0] = pSet.get(0);
            descriptorSets[1] = pSet.get(1);

            // Push constants: 56 bytes (vec2 srcSize, vec2 dstSize, float motionX/Y, float alpha,
            //                  float disocclusion, float sharpness, float varianceClipGamma,
            //                  uint ready, uint pad)
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(56);
            LongBuffer pLayout = stack.mallocLong(1);
            VK10.vkCreatePipelineLayout(vkDevice.vkDevice(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(descriptorSetLayout))
                            .pPushConstantRanges(pcr),
                    null, pLayout);
            pipelineLayout = pLayout.get(0);

            // Linear sampler for history.
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f);
            LongBuffer pSampler = stack.mallocLong(1);
            VK10.vkCreateSampler(vkDevice.vkDevice(), samplerInfo, null, pSampler);
            sampler = pSampler.get(0);

            // Load SPIR-V.
            byte[] spv;
            try (InputStream in = TaaUpscaler.class.getResourceAsStream(SHADER_PATH)) {
                if (in == null) {
                    throw new IOException("Missing " + SHADER_PATH);
                }
                spv = in.readAllBytes();
            }
            ByteBuffer code = org.lwjgl.system.MemoryUtil.memAlloc(spv.length);
            code.put(spv).flip();

            LongBuffer pMod = stack.mallocLong(1);
            VK10.vkCreateShaderModule(vkDevice.vkDevice(),
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pMod);
            long mod = pMod.get(0);
            org.lwjgl.system.MemoryUtil.memFree(code);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod)
                    .pName(stack.UTF8("main"));
            LongBuffer pPipe = stack.mallocLong(1);
            VK10.vkCreateComputePipelines(vkDevice.vkDevice(), 0,
                    VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                            .stage(stage).layout(pipelineLayout), null, pPipe);
            pipeline = pPipe.get(0);

            VK10.vkDestroyShaderModule(vkDevice.vkDevice(), mod, null);
        }
    }

    private void bindDescriptors(MemoryStack stack, int setIndex, RtImage inColorLow, RtImage inDepthLow,
                                  RtImage inMotionLow, RtImage inNormalLow, RtImage inAlbedoLow,
                                  RtImage prevHistory, RtImage newHistory, RtImage out) {
        long descriptorSet = descriptorSets[setIndex];
        // jitterGuide is optional; when null use a placeholder (any view) — the sampler
        // returns 0.5 which the shader decodes to offset=0, preserving legacy behaviour.
        long jitterView = (jitterGuide != null) ? jitterGuide.view : inColorLow.view;
        long[] views = {
                inColorLow.view,
                inDepthLow.view,
                inMotionLow.view,
                (inNormalLow != null ? inNormalLow : inColorLow).view,
                (inAlbedoLow != null ? inAlbedoLow : inColorLow).view,
                prevHistory.view,
                out.view,
                newHistory.view,
                jitterView
        };
        if (Arrays.equals(boundViews[setIndex], views)) {
            return;
        }
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(9, stack);
        // 0..4: storage images (inColorLow, inDepthLow, inMotionLow, inNormalLow, inAlbedoLow).
        // Normal / albedo may be null (legacy callers); bind a placeholder view (any image view)
        // to satisfy the descriptor set layout. We pass the existing color view as the placeholder
        // when normal / albedo is missing -- the shader never reads it on those paths.
        RtImage placeHolder = inColorLow;
        RtImage[] storageIn = {inColorLow, inDepthLow, inMotionLow,
                inNormalLow != null ? inNormalLow : placeHolder,
                inAlbedoLow != null ? inAlbedoLow : placeHolder};
        for (int i = 0; i < 5; i++) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageView(storageIn[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(info);
        }
        // 5: combined image sampler for prevHistory
        VkDescriptorImageInfo.Buffer histInfo = VkDescriptorImageInfo.calloc(1, stack);
        histInfo.get(0).imageView(prevHistory.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .sampler(sampler);
        writes.get(5).sType$Default().dstSet(descriptorSet).dstBinding(5)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(histInfo);
        // 6: outColor storage
        VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
        outInfo.get(0).imageView(out.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(6).sType$Default().dstSet(descriptorSet).dstBinding(6)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(outInfo);
        // 7: historyOut storage
        VkDescriptorImageInfo.Buffer histOutInfo = VkDescriptorImageInfo.calloc(1, stack);
        histOutInfo.get(0).imageView(newHistory.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(7).sType$Default().dstSet(descriptorSet).dstBinding(7)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(histOutInfo);
        // 8 (v0.6.8+): per-tile jitter guide sampler. Reuses the linear-clamp `sampler`
        // (same as history @5). When the host didn't call setJitterGuide, `jitterView`
        // is a placeholder and the sampler returns 0.5 (decoded offset = 0).
        VkDescriptorImageInfo.Buffer jitterInfo = VkDescriptorImageInfo.calloc(1, stack);
        jitterInfo.get(0).imageView(jitterView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .sampler(sampler);
        writes.get(8).sType$Default().dstSet(descriptorSet).dstBinding(8)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(jitterInfo);
        VK10.vkUpdateDescriptorSets(vkDevice.vkDevice(), writes, null);
        System.arraycopy(views, 0, boundViews[setIndex], 0, views.length);
    }

    private void clearImage(long cmd, RtImage image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDevice.vkDevice());
            VkImageMemoryBarrier2.Buffer bar = VkImageMemoryBarrier2.calloc(1, stack);
            bar.get(0).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(image.image)
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    cb,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(bar));

            VkClearColorValue clear = VkClearColorValue.calloc(stack);
            clear.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cb, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);

            bar.get(0)
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    cb,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(bar));
        }
    }

    /**
     * Build the RCAS (AMD CAS) post-pass pipeline. Same algorithm as the FFX SDK's RCAS
     * shader (5-tap contrast-adaptive sharpen with luminance + chroma derivative lobes).
     * Reads from the TAAU out image, writes back to the same image — the temporal history
     * already ping-ponged, so aliasing in-place is safe.
     */
    private void createCasPipeline() throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 2 bindings: 0 = input HDR radiance, 1 = output HDR radiance (both B10G11R11).
            // CasPushConstants: int width, int height, float sharpness, float pad.
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pDsl = stack.mallocLong(1);
            VK10.vkCreateDescriptorSetLayout(vkDevice.vkDevice(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds),
                    null, pDsl);
            casDescriptorSetLayout = pDsl.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            LongBuffer pPool = stack.mallocLong(1);
            VK10.vkCreateDescriptorPool(vkDevice.vkDevice(),
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                            .maxSets(1).pPoolSizes(sizes),
                    null, pPool);
            casDescriptorPool = pPool.get(0);

            casDescriptorSets = new long[1];
            LongBuffer pSet = stack.mallocLong(1);
            VK10.vkAllocateDescriptorSets(vkDevice.vkDevice(),
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(casDescriptorPool)
                            .pSetLayouts(stack.longs(casDescriptorSetLayout)),
                    pSet);
            casDescriptorSets[0] = pSet.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            LongBuffer pLayout = stack.mallocLong(1);
            VK10.vkCreatePipelineLayout(vkDevice.vkDevice(),
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(casDescriptorSetLayout))
                            .pPushConstantRanges(pcr),
                    null, pLayout);
            casPipelineLayout = pLayout.get(0);

            // Load SPIR-V.
            byte[] spv;
            try (InputStream in = TaaUpscaler.class.getResourceAsStream(CAS_SHADER_PATH)) {
                if (in == null) {
                    throw new IOException("Missing " + CAS_SHADER_PATH);
                }
                spv = in.readAllBytes();
            }
            ByteBuffer code = org.lwjgl.system.MemoryUtil.memAlloc(spv.length);
            code.put(spv).flip();

            LongBuffer pMod = stack.mallocLong(1);
            VK10.vkCreateShaderModule(vkDevice.vkDevice(),
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pMod);
            long mod = pMod.get(0);
            org.lwjgl.system.MemoryUtil.memFree(code);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod)
                    .pName(stack.UTF8("main"));
            LongBuffer pPipe = stack.mallocLong(1);
            VK10.vkCreateComputePipelines(vkDevice.vkDevice(), 0,
                    VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                            .stage(stage).layout(casPipelineLayout), null, pPipe);
            casPipeline = pPipe.get(0);

            VK10.vkDestroyShaderModule(vkDevice.vkDevice(), mod, null);
        }
    }

    /**
     * Bind the RCAS descriptor set. Both in and out point to the same image — RCAS reads
     * the TAAU output, writes its sharpened version back to the same image. The temporal
     * history already ping-ponged before this call so in-place is safe.
     */
    private void bindCasDescriptors(MemoryStack stack, RtImage in, RtImage out) {
        long[] views = {in.view, out.view};
        if (casBoundViews.length > 0 && Arrays.equals(casBoundViews[0], views)) {
            return;
        }
        try {
            casBoundViews = new long[][]{views};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(views[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(casDescriptorSets[0]).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(vkDevice.vkDevice(), writes, null);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("TAAU RCAS bindDescriptors failed", t);
        }
    }
}
