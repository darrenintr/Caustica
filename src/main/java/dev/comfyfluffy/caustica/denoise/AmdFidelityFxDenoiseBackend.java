package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.FireflyKill;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * AMD FidelityFX preset denoise stack (no NRD):
 * <ol>
 *   <li>{@link FireflyKill} — 3×3 per-channel median outlier rejection on the raw
 *       path-traced radiance plate (kills SPP=2 single-pixel fireflies before any
 *       smoothed denoise can apply).</li>
 *   <li>{@link OfficialFfxDenoiseBackend} — shadow (+ optional reflection history).</li>
 *   <li>{@link BilateralDenoiseBackend} — mild spatial residual polish for leftover
 *       GI/sky grain after shadow FFX.</li>
 * </ol>
 *
 * <p>Paired with classic FSR2 + CAS by the upscaler/display path. Never loads NRD.
 * Firefly kill is owned here — not in {@code RtComposite} — so the FFX-only and
 * FFX-AMD presets both see clean radiance without {@code RtComposite} caring
 * whether the denoise backend wants the kill or not.
 *
 * <p>Re-enabled (2026-07-20) after the v0.6 revert. The earlier heuristic killed
 * sea-lantern NEE hits because it was the only pass; now it sits between world.rgen's
 * per-material HDR clamp (line 2588+) and the FFX spatial filter, which have
 * already suppressed the bulk of the firefly population. The remaining peaks that
 * hit the median are real outliers the kernel can reject cleanly.
 */
public final class AmdFidelityFxDenoiseBackend implements CausticaDenoiseBackend {

    private final OfficialFfxDenoiseBackend ffx = new OfficialFfxDenoiseBackend();
    private final FireflyKill fireflyKill = new FireflyKill();
    /**
     * Residual polish for leftover GI/sky grain after shadow FFX.
     * Mild: 3 passes so a bad FFX plate is not amplified into a black world.
     */
    private final BilateralDenoiseBackend residual =
            new BilateralDenoiseBackend(3, 0.04f, 0.16f, 0.90f, "ffx-residual");

    private RtImage mid;
    private RtImage fireflyKilled;
    private int width;
    private int height;
    private boolean ready;
    private String lastPath = "idle";

    @Override
    public String name() {
        return "amd-fidelityfx";
    }

    public String lastPathLabel() {
        return lastPath;
    }

    public void setSplitBuffers(RtImage shadowHit, RtImage diffuse, RtImage reflection) {
        ffx.setSplitBuffers(shadowHit, diffuse, reflection);
    }

    public void setUnshadowedDirect(RtImage unshadowedDirect) {
        ffx.setUnshadowedDirect(unshadowedDirect);
    }

    public void setSpecMotion(RtImage specMotion) {
        ffx.setSpecMotion(specMotion);
    }

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        ffx.init(vkDevice, vkPhysicalDevice);
        // FireflyKill has no init() — pipeline setup happens in ensureSized(), see below.
        residual.init(vkDevice, vkPhysicalDevice);
        ready = ffx.isReady();
        if (ready) {
            CausticaMod.LOGGER.info(
                    "AMD FidelityFX denoise ready (firefly kill + FFX shadow/refl + spatial GI residual; no NRD; pairs with FSR2)");
        }
    }

    @Override
    public void ensureSized(int width, int height) {
        if (!ready) {
            return;
        }
        ffx.ensureSized(width, height);
        residual.ensureSized(width, height);

        if (this.width == width && this.height == height && mid != null && fireflyKilled != null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (mid != null) {
            mid.destroy();
            mid = null;
        }
        if (fireflyKilled != null) {
            fireflyKilled.destroy();
            fireflyKilled = null;
        }
        if (ctx != null && width > 0 && height > 0) {
            mid = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "amd-ffx mid");
            fireflyKilled = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT,
                    "amd-ffx firefly killed");
            // Build the firefly-kill pipeline against the freshly-allocated output buffer.
            fireflyKill.ensureSized(ctx, width, height, fireflyKilled);
        }
        this.width = width;
        this.height = height;
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                            RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                            float mvScaleX, float mvScaleY,
                            RtImage outColor) {
        if (!ready || mid == null || outColor == null) {
            lastPath = "raw";
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }

        // Pipeline (in order):
        //   raw RT  → firefly_kill  →  OfficialFfx (shadow+reflection reproject+spatial+composite)
        //                              → bilateral residual (3 passes)
        //                              → outColor
        //
        // 1) Firefly kill: 3×3 per-channel median on the SPP-2 radiance plate.
        if (fireflyKilled != null && fireflyKill.isReady()) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "amd-ffx firefly kill");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.ffxFirefly")) {
                // inColor argument is the raw RT (passed through the CausticaDenoiseBackend
                // interface, where this backend is the only consumer of it). Firefly kill
                // reads raw RT, writes to its fireflyKilled slot; downstream stages see it.
                fireflyKill.dispatch(cmd.address(), ctx, inColor, fireflyKilled);
            }
            barrierComputeToShader(cmd, stack, fireflyKilled.image);
        }

        // 2) OfficialFfx: shadow + reflection + composite on the firefly-killed radiance.
        boolean ffxOk = ffx.dispatch(stack, cmd, fireflyKilled != null ? fireflyKilled : inColor,
                inNormal, inDepth, inMotion,
                mvScaleX, mvScaleY, outColor);
        if (!ffxOk) {
            lastPath = "raw (ffx fail)";
            return false;
        }

        // 3) Bilateral residual: three edge-aware passes into mid, copy back to outColor.
        try {
            if (residual.dispatch(stack, cmd, outColor, inNormal, inDepth, inMotion,
                    mvScaleX, mvScaleY, mid)) {
                copyImage(stack, cmd, mid, outColor);
                lastPath = "firefly→ffx→residual";
                return true;
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("FFX residual polish failed; keeping FFX plate", t);
        }
        lastPath = "firefly→ffx";
        return true;
    }

    /** Copy the three-pass residual result back without recording a second descriptor mutation. */
    private static void copyImage(MemoryStack stack, VkCommandBuffer cmd, RtImage src, RtImage dst) {
        VkImageMemoryBarrier2.Buffer before = VkImageMemoryBarrier2.calloc(2, stack);
        before.get(0).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(src.image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        before.get(1).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(dst.image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(before));

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0)
                .srcSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1))
                .dstSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1))
                .extent(it -> it.width(src.width).height(src.height).depth(1));
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);

        VkImageMemoryBarrier2.Buffer after = VkImageMemoryBarrier2.calloc(1, stack);
        after.get(0).sType$Default()
                .srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(dst.image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(after));
    }

    /**
     * Make a compute shader write visible to a subsequent compute shader read on RADV
     * (compute→compute barrier). Caller passes the just-written images; the dispatcher
     * uses {@code VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT} on both src and dst.
     */
    private static void barrierComputeToShader(VkCommandBuffer cmd, MemoryStack stack, long... images) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            barriers.get(i).sType$Default()
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .image(images[i])
                    .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        }
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        check(0, "barrierComputeToShader"); // barrier scheduling, never an error path
    }

    @Override
    public void resetHistory() {
        ffx.resetHistory();
        residual.resetHistory();
    }

    @Override
    public void destroy() {
        ffx.destroy();
        fireflyKill.destroy();
        if (fireflyKilled != null) {
            fireflyKilled.destroy();
            fireflyKilled = null;
        }
        residual.destroy();
        if (mid != null) {
            mid.destroy();
            mid = null;
        }
        ready = false;
        width = 0;
        height = 0;
        lastPath = "destroyed";
    }

    @Override
    public boolean isReady() {
        return ready && ffx.isReady();
    }
}
