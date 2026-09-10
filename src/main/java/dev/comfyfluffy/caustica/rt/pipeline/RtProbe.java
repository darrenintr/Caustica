package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
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

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Pipeline "black box flight recorder" for NRD + TAAU diagnosis.
 *
 * <p>When {@code [probe] enabled=true}, every {@code interval-frames} frames the
 * composite calls {@link #record} with the live plates. A tiny compute shader
 * ({@code probe_moments.comp}) reduces each plate to mean/variance/max/NaN-count
 * into a host-visible SSBO; the host appends one CSV row per probe frame into
 * {@code <gameDir>/rt-probe/probe.csv}. Feed the CSV (+ a screenshot taken at
 * the same time) to {@code scripts/analyze_probe.py} to find which stage is
 * out of range — no visual guessing.
 *
 * <p>Two CSV sections. Beauty/history columns:
 * {@code frame, w, h, renderW, renderH, jitterX, jitterY,
 * denoiseOn, denoisePath, upscalerPath, upscalerOk,
 * rawMean, rawVar, rawMax, rawNaN,
 * fireflyMean, fireflyVar, fireflyMax, fireflyNaN,
 * denoiseMean, denoiseVar, denoiseMax, denoiseNaN,
 * upMean, upVar, upMax, upNaN,
 * mvMeanPx, mvVar, mvMaxPx, mvNaN,
 * disoccMean, disoccVar, disoccMax, disoccNaN}.
 * Lighting columns (split plates + sky/lights/exposure inputs):
 * {@code diffMean, diffVar, diffMax, diffNaN,
 * reflMean, reflVar, reflMax, reflNaN,
 * unshadowMean, unshadowVar, unshadowMax, unshadowNaN,
 * emissMean, emissVar, emissMax, emissNaN,
 * transmMean, transmVar, transmMax, transmNaN,
 * shadowHitMean, shadowHitVar, shadowHitMax, shadowHitNaN,
 * viewZMean, viewZVar, viewZMax, viewZNaN,
 * exposMean, exposVar, exposMax, exposNaN,
 * nrdDiffMean, nrdDiffVar, nrdDiffMax, nrdDiffNaN,
 * nrdSpecMean, nrdSpecVar, nrdSpecMax, nrdSpecNaN,
 * nrdShadowMean, nrdShadowVar, nrdShadowMax, nrdShadowNaN,
 * sunY, dayFactor, lightR, lightG, lightB,
 * blockLightCount, dynLightCount, uploadCount,
 * lightRev, spp, bounces, camX, camY, camZ}.
 *
 * <p>Zero stall design: the SSBO is host-visible + mapped once; the GPU writes
 * it during the frame and the CPU reads it back on the NEXT probe frame (one
 * probe-interval of lag, never a fence wait). The compute→host barrier plus an
 * invalidate() in the readback covers non-coherent BAR/GTT placements.
 */
public final class RtProbe {
    private static final String SHADER = "/caustica/rt/probe_moments.comp.spv";
    // 6 beauty/motion slots (0..23) + 11 lighting slots (24..67); [68..79] reserved.
    private static final int STAT_FLOATS = 80;
    private static final int STAT_BYTES = STAT_FLOATS * Float.BYTES;
    private static final int PUSH_BYTES = 16;

    /** Lighting plate slot order; index i → stats base 24 + i * 4. */
    static final int LIGHT_BASE = 24;
    static final int LIGHT_SLOTS = 11;
    /** Host-side scalars per row (sky/light/frame knobs, bypass the GPU). */
    static final int SCALAR_COUNT = 15;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    /** One descriptor set per probed plate: descriptors are consumed at execution time, so 17
     *  dispatches sharing one set would all sample whichever image was bound last. */
    private final long[] descriptorSets;
    /** Number of plates probed per record() call (must match descriptorSets.length). */
    private static final int PLATE_COUNT = 17;
    private final long pipelineLayout;
    private final long pipeline;
    private RtBuffer statsBuffer;
    private PrintWriter csv;
    private boolean csvOpenAttempted;
    private boolean destroyed;
    /** Last probe's stats, read back one interval late (never stalls the GPU). */
    private final float[] pendingStats = new float[STAT_FLOATS];
    /** Host-side scalars for the pending row: sunY, dayFactor, lightRGB, light counts... */
    private final double[] pendingScalars = new double[SCALAR_COUNT];
    private boolean pendingValid;
    /** Throttle for per-frame probe warnings (a hot probe failure must not spam). */
    private long lastWarnFrame = -1_000_000L;
    private static final long WARN_THROTTLE_FRAMES = 300L;

    private RtProbe(RtContext ctx, long dsl, long pool, long[] sets, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSets = sets;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtProbe create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        long dsl = 0L;
        long pool = 0L;
        long[] sets = null;
        long layout = 0L;
        long module = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Binding 0 = sampled plate (texture2D in probe_moments.comp needs
            // SAMPLED_IMAGE, not COMBINED_IMAGE_SAMPLER — the shader declares
            // samplerless `texture2D` and reads via texelFetch, no sampler).
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, p),
                    "vkCreateDescriptorSetLayout(probe)");
            dsl = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(PLATE_COUNT);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(PLATE_COUNT);
            check(VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(PLATE_COUNT).pPoolSizes(sizes), null, p),
                    "vkCreateDescriptorPool(probe)");
            pool = p.get(0);
            LongBuffer pSets = stack.mallocLong(PLATE_COUNT);
            LongBuffer pLayouts = stack.mallocLong(PLATE_COUNT);
            for (int i = 0; i < PLATE_COUNT; i++) {
                pLayouts.put(i, dsl);
            }
            check(VK10.vkAllocateDescriptorSets(vk,
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(pool).pSetLayouts(pLayouts), pSets),
                    "vkAllocateDescriptorSets(probe)");
            sets = new long[PLATE_COUNT];
            pSets.get(sets);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pcr), null, p),
                    "vkCreatePipelineLayout(probe)");
            layout = p.get(0);

            module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(probe)");
            pipeline = pPipeline.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            module = 0L;
            RtProbe created = new RtProbe(ctx, dsl, pool, sets, layout, pipeline);
            dsl = 0L;
            pool = 0L;
            layout = 0L;
            pipeline = 0L;
            CausticaMod.LOGGER.info("RtProbe: compute pipeline ready");
            return created;
        } catch (Throwable t) {
            // Loud by design: without this pipeline there is no probe.csv at all,
            // and the old swallowed exception left players staring at "where is my data".
            CausticaMod.LOGGER.warn("RtProbe: compute pipeline creation failed; no probe.csv will be written", t);
            try {
                if (module != 0L) {
                    VK10.vkDestroyShaderModule(vk, module, null);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (pipeline != 0L) {
                    VK10.vkDestroyPipeline(vk, pipeline, null);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (layout != 0L) {
                    VK10.vkDestroyPipelineLayout(vk, layout, null);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (pool != 0L) {
                    VK10.vkDestroyDescriptorPool(vk, pool, null);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (dsl != 0L) {
                    VK10.vkDestroyDescriptorSetLayout(vk, dsl, null);
                }
            } catch (Throwable ignored) {
            }
            throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
        }
    }

    /**
     * Record one probe frame. Dispatches 6 beauty/history reductions (raw /
     * firefly / denoised / upscaled plates + motion + disocclusion) and 11
     * lighting reductions (split diffuse/reflection/unshadowed/emission/
     * transmission/shadowHit/viewZ/exposure/NRD-out-diff/spec/shadow), then
     * appends the PREVIOUS probe's readback as a CSV row (one interval of lag,
     * zero stall).
     *
     * <p>Host-side scalars (sky, lights, exposure, frame knobs) bypass the GPU:
     * they are snapshotted into {@code pendingScalars} for the NEXT probe's row,
     * same cadence as the GPU stats. A {@code null} plate is skipped — its slot
     * keeps the previous probe's value and the CSV row is still written, so a
     * missing plate never drops a row (check the NaN column: a stuck slot reads
     * identical across rows).
     */
    public void record(MemoryStack stack, VkCommandBuffer cmd, long frame,
                       int width, int height, int renderW, int renderH,
                       float jitterX, float jitterY,
                       boolean denoiseOn, String denoisePath,
                       boolean upscalerPath, boolean upscalerOk,
                       RtImage raw, RtImage firefly, RtImage denoised, RtImage upscaled,
                       RtImage motion, RtImage disoccMix,
                       RtImage diffuse, RtImage reflection, RtImage unshadowedDirect,
                       RtImage emission, RtImage transmission, RtImage shadowHit,
                       RtImage viewZ, RtImage exposureImg,
                       RtImage nrdOutDiff, RtImage nrdOutSpec, RtImage nrdOutShadow,
                       float sunY, float dayFactor,
                       float lightR, float lightG, float lightB,
                       int blockLightCount, int dynLightCount, int uploadCount, long lightRev,
                       int spp, int bounces,
                       double camX, double camY, double camZ) {
        if (destroyed) {
            return;
        }
        ensureStatsBuffer();
        if (statsBuffer == null) {
            return;
        }
        // Append the PREVIOUS probe's readback as a CSV row (one interval of
        // lag, zero stall). The barrier in each probePlate dispatch makes the
        // GPU write visible; invalidate() inside readbackStats() pulls it to the
        // host on non-coherent BAR/GTT. At interval>=30 the writes are dozens of
        // frames old, so no fence wait is needed — only this ordering.
        if (pendingValid) {
            writeCsvRow(frame, width, height, renderW, renderH, jitterX, jitterY,
                    denoiseOn, denoisePath, upscalerPath, upscalerOk, pendingStats, pendingScalars);
            pendingValid = false;
        }
        // One dedicated descriptor set per plate: sharing one set aliases all 17 dispatches
        // to the last-bound image (descriptors are consumed at execution time).
        // mode 0 = plate luma moments → stats[0..3],[4..7],[8..11],[12..15]
        probePlate(stack, cmd, 0, raw, 0, 0);
        probePlate(stack, cmd, 1, firefly, 0, 4);
        probePlate(stack, cmd, 2, denoised, 0, 8);
        probePlate(stack, cmd, 3, upscaled, 0, 12);
        // mode 1 = motion |MV| px → stats[16..19]
        probePlate(stack, cmd, 4, motion, 1, 16);
        // mode 2 = disocclusion scalar [0,1] → stats[20..23]
        probePlate(stack, cmd, 5, disoccMix, 2, 20);
        // Lighting split plates: HDR radiance → mode 0, scalar guides → mode 2,
        // viewZ + exposure → mode 3 (raw channel, no [0,1] clamp).
        probePlate(stack, cmd, 6, diffuse, 0, LIGHT_BASE + 0 * 4);
        probePlate(stack, cmd, 7, reflection, 0, LIGHT_BASE + 1 * 4);
        probePlate(stack, cmd, 8, unshadowedDirect, 0, LIGHT_BASE + 2 * 4);
        probePlate(stack, cmd, 9, emission, 0, LIGHT_BASE + 3 * 4);
        probePlate(stack, cmd, 10, transmission, 0, LIGHT_BASE + 4 * 4);
        probePlate(stack, cmd, 11, shadowHit, 2, LIGHT_BASE + 5 * 4);
        probePlate(stack, cmd, 12, viewZ, 3, LIGHT_BASE + 6 * 4);
        probePlate(stack, cmd, 13, exposureImg, 3, LIGHT_BASE + 7 * 4);
        probePlate(stack, cmd, 14, nrdOutDiff, 0, LIGHT_BASE + 8 * 4);
        probePlate(stack, cmd, 15, nrdOutSpec, 0, LIGHT_BASE + 9 * 4);
        probePlate(stack, cmd, 16, nrdOutShadow, 0, LIGHT_BASE + 10 * 4);
        barrierStats(stack, cmd);
        // Snapshot for next probe's CSV row via readbackStats() (invalidate +
        // mapped read). One probe-interval stale, never a fence wait. A failed
        // readback (stale native lib, lost device) invalidates the pending row
        // so the next probe writes nothing instead of a duplicated stale row.
        pendingValid = readbackStats();
        if (pendingValid) {
            snapshotScalars(sunY, dayFactor, lightR, lightG, lightB,
                    blockLightCount, dynLightCount, uploadCount, lightRev,
                    spp, bounces, camX, camY, camZ);
        }
    }

    /** Backwards-compatible entry point: beauty/history plates only, scalars zeroed. */
    public void record(MemoryStack stack, VkCommandBuffer cmd, long frame,
                       int width, int height, int renderW, int renderH,
                       float jitterX, float jitterY,
                       boolean denoiseOn, String denoisePath,
                       boolean upscalerPath, boolean upscalerOk,
                       RtImage raw, RtImage firefly, RtImage denoised, RtImage upscaled,
                       RtImage motion, RtImage disoccMix) {
        record(stack, cmd, frame, width, height, renderW, renderH, jitterX, jitterY,
                denoiseOn, denoisePath, upscalerPath, upscalerOk,
                raw, firefly, denoised, upscaled, motion, disoccMix,
                null, null, null, null, null, null, null, null, null, null, null,
                0f, 0f, 0f, 0f, 0f, 0, 0, 0, 0L, 0, 0, 0.0, 0.0, 0.0);
    }

    private void probePlate(MemoryStack stack, VkCommandBuffer cmd, int slot, RtImage img, int mode, int statBase) {
        if (img == null || slot < 0 || slot >= PLATE_COUNT) {
            return;
        }
        try {
            bind(stack, descriptorSets[slot], img.view);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descriptorSets[slot]), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, img.width);
            push.putInt(4, img.height);
            push.putInt(8, mode);
            push.putInt(12, statBase);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, 1, 1, 1);
            barrierStats(stack, cmd);
        } catch (Throwable t) {
            warnThrottled("RtProbe: plate dispatch failed (slot=" + slot + " base=" + statBase + ")", t);
        }
    }

    private void bind(MemoryStack stack, long set, long plateView) {
        VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(1, stack);
        infos.get(0).sampler(VK10.VK_NULL_HANDLE).imageView(plateView)
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                .pImageInfo(infos);
        VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
        bufInfo.get(0).buffer(statsBuffer.handle).offset(0).range(STAT_BYTES);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .pBufferInfo(bufInfo);
        VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
    }

    private void ensureStatsBuffer() {
        if (statsBuffer != null) {
            return;
        }
        try {
            statsBuffer = ctx.createBuffer(STAT_BYTES,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    true, "probe stats");
            // Zero-init so the first readback (before any dispatch) is 0, not garbage.
            if (statsBuffer.mapped != 0L) {
                for (int i = 0; i < STAT_FLOATS; i++) {
                    MemoryUtil.memPutFloat(statsBuffer.mapped + (long) i * Float.BYTES, 0.0f);
                }
                statsBuffer.flush(0L, STAT_BYTES);
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("RtProbe: stats buffer unavailable; probe disabled this session", t);
            statsBuffer = null;
        }
    }

    private void barrierStats(MemoryStack stack, VkCommandBuffer cmd) {
        // SSBO write → host read visibility. A memory barrier (not an image
        // barrier) is the correct primitive for a storage-buffer write.
        org.lwjgl.vulkan.VkMemoryBarrier2.Buffer mem =
                org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstStageMask(VK10.VK_PIPELINE_STAGE_HOST_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(mem);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private boolean readbackStats() {
        if (statsBuffer == null || statsBuffer.mapped == 0L) {
            warnThrottled("RtProbe: stats buffer not host-mapped; skipping readback", null);
            return false;
        }
        try {
            // Device→host visibility on non-coherent BAR/GTT: the probe dispatches
            // landed this frame, and this mapped read runs without any fence wait
            // one probe-interval later. On HOST_COHERENT this is a driver no-op.
            statsBuffer.invalidate(0L, STAT_BYTES);
            for (int i = 0; i < STAT_FLOATS; i++) {
                float v = MemoryUtil.memGetFloat(statsBuffer.mapped + (long) i * Float.BYTES);
                pendingStats[i] = Float.isFinite(v) ? v : -1.0f;
            }
            return true;
        } catch (Throwable t) {
            warnThrottled("RtProbe: stats readback failed; row dropped", t);
            return false;
        }
    }

    private void snapshotScalars(float sunY, float dayFactor,
                                 float lightR, float lightG, float lightB,
                                 int blockLightCount, int dynLightCount, int uploadCount, long lightRev,
                                 int spp, int bounces,
                                 double camX, double camY, double camZ) {
        pendingScalars[0] = sunY;
        pendingScalars[1] = dayFactor;
        pendingScalars[2] = lightR;
        pendingScalars[3] = lightG;
        pendingScalars[4] = lightB;
        pendingScalars[5] = blockLightCount;
        pendingScalars[6] = dynLightCount;
        pendingScalars[7] = uploadCount;
        pendingScalars[8] = lightRev;
        pendingScalars[9] = spp;
        pendingScalars[10] = bounces;
        pendingScalars[11] = camX;
        pendingScalars[12] = camY;
        pendingScalars[13] = camZ;
        pendingScalars[14] = 0.0;
    }

    private void writeCsvRow(long frame, int width, int height, int renderW, int renderH,
                             float jitterX, float jitterY,
                             boolean denoiseOn, String denoisePath,
                             boolean upscalerPath, boolean upscalerOk,
                             float[] s, double[] scalars) {
        ensureCsv();
        if (csv == null) {
            return;
        }
        String dp = denoisePath != null ? denoisePath.replace(',', ';').replace('\n', ' ') : "off";
        double[] k = scalars != null && scalars.length >= SCALAR_COUNT ? scalars : new double[SCALAR_COUNT];
        csv.printf(Locale.ROOT,
                "%d,%d,%d,%d,%d,%.4f,%.4f,%d,%s,%d,%d,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.4f,%.5f,%.3f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.5f,%.6f,%.4f,%.0f,"
                        + "%.4f,%.4f,%.4f,%.4f,%.4f,"
                        + "%.0f,%.0f,%.0f,%.0f,%.0f,%.0f,%.1f,%.1f,%.1f%n",
                frame, width, height, renderW, renderH, jitterX, jitterY,
                denoiseOn ? 1 : 0, dp, upscalerPath ? 1 : 0, upscalerOk ? 1 : 0,
                s[0], s[1], s[2], s[3],
                s[4], s[5], s[6], s[7],
                s[8], s[9], s[10], s[11],
                s[12], s[13], s[14], s[15],
                s[16], s[17], s[18], s[19],
                s[20], s[21], s[22], s[23],
                s[24], s[25], s[26], s[27],
                s[28], s[29], s[30], s[31],
                s[32], s[33], s[34], s[35],
                s[36], s[37], s[38], s[39],
                s[40], s[41], s[42], s[43],
                s[44], s[45], s[46], s[47],
                s[48], s[49], s[50], s[51],
                s[52], s[53], s[54], s[55],
                s[56], s[57], s[58], s[59],
                s[60], s[61], s[62], s[63],
                s[64], s[65], s[66], s[67],
                k[0], k[1], k[2], k[3], k[4],
                k[5], k[6], k[7], k[8], k[9], k[10], k[11], k[12], k[13]);
        csv.flush();
    }

    private void ensureCsv() {
        if (csvOpenAttempted) {
            return;
        }
        csvOpenAttempted = true;
        Path dir = FabricLoader.getInstance().getGameDir().resolve("rt-probe");
        Path file = dir.resolve("probe.csv");
        try {
            Files.createDirectories(dir);
            csv = new PrintWriter(new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8)));
            csv.println("frame,width,height,renderW,renderH,jitterX,jitterY,"
                    + "denoiseOn,denoisePath,upscalerPath,upscalerOk,"
                    + "rawMean,rawVar,rawMax,rawNaN,"
                    + "fireflyMean,fireflyVar,fireflyMax,fireflyNaN,"
                    + "denoiseMean,denoiseVar,denoiseMax,denoiseNaN,"
                    + "upMean,upVar,upMax,upNaN,"
                    + "mvMeanPx,mvVar,mvMaxPx,mvNaN,"
                    + "disoccMean,disoccVar,disoccMax,disoccNaN,"
                    + "diffMean,diffVar,diffMax,diffNaN,"
                    + "reflMean,reflVar,reflMax,reflNaN,"
                    + "unshadowMean,unshadowVar,unshadowMax,unshadowNaN,"
                    + "emissMean,emissVar,emissMax,emissNaN,"
                    + "transmMean,transmVar,transmMax,transmNaN,"
                    + "shadowHitMean,shadowHitVar,shadowHitMax,shadowHitNaN,"
                    + "viewZMean,viewZVar,viewZMax,viewZNaN,"
                    + "exposMean,exposVar,exposMax,exposNaN,"
                    + "nrdDiffMean,nrdDiffVar,nrdDiffMax,nrdDiffNaN,"
                    + "nrdSpecMean,nrdSpecVar,nrdSpecMax,nrdSpecNaN,"
                    + "nrdShadowMean,nrdShadowVar,nrdShadowMax,nrdShadowNaN,"
                    + "sunY,dayFactor,lightR,lightG,lightB,"
                    + "blockLightCount,dynLightCount,uploadCount,lightRev,spp,bounces,"
                    + "camX,camY,camZ");
            csv.flush();
            CausticaMod.LOGGER.info("RtProbe: recording to {}", file.toAbsolutePath());
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("RtProbe: failed to open CSV {}: {}", file, e.toString());
            csv = null;
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        if (csv != null) {
            try {
                csv.close();
            } catch (Throwable ignored) {
            }
            csv = null;
        }
        if (statsBuffer != null) {
            try {
                statsBuffer.destroy();
            } catch (Throwable ignored) {
            }
            statsBuffer = null;
        }
        VkDevice vk = ctx.vk();
        try {
            if (pipeline != 0L) {
                VK10.vkDestroyPipeline(vk, pipeline, null);
            }
            if (pipelineLayout != 0L) {
                VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
            }
            if (descriptorPool != 0L) {
                VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
            }
            if (descriptorSetLayout != 0L) {
                VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream in = RtProbe.class.getResourceAsStream(SHADER)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(probe)");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    /** Frame-throttled warn: per-frame probe failures must not spam the log. */
    private void warnThrottled(String message, Throwable t) {
        long nowFrame = frameForThrottle();
        if (nowFrame - lastWarnFrame < WARN_THROTTLE_FRAMES) {
            return;
        }
        lastWarnFrame = nowFrame;
        if (t != null) {
            CausticaMod.LOGGER.warn("{} (frame {}, further probe warnings throttled)", message, nowFrame, t);
        } else {
            CausticaMod.LOGGER.warn("{} (frame {}, further probe warnings throttled)", message, nowFrame);
        }
    }

    private static long frameForThrottle() {
        try {
            return dev.comfyfluffy.caustica.rt.RtComposite.frameCounter();
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
