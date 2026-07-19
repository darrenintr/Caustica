package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * AMD FidelityFX preset denoise stack (no NRD):
 * <ol>
 *   <li>{@link OfficialFfxDenoiseBackend} — shadow (+ optional reflection history)</li>
 *   <li>Optional residual GI polish — mild spatial bilateral</li>
 * </ol>
 *
 * <p>Paired with classic FSR2 + CAS by the upscaler/display path. Never loads NRD.
 */
public final class AmdFidelityFxDenoiseBackend implements CausticaDenoiseBackend {

    private final OfficialFfxDenoiseBackend ffx = new OfficialFfxDenoiseBackend();
    /**
     * Residual polish for leftover GI/sky grain after shadow FFX.
     * Mild: 3 passes so a bad FFX plate is not amplified into a black world.
     */
    private final BilateralDenoiseBackend residual =
            new BilateralDenoiseBackend(3, 0.04f, 0.16f, 0.90f, "ffx-residual");

    private RtImage mid;
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
        residual.init(vkDevice, vkPhysicalDevice);
        ready = ffx.isReady();
        if (ready) {
            CausticaMod.LOGGER.info(
                    "AMD FidelityFX denoise ready (FFX shadow + mild residual GI; no NRD; pairs with FSR2)");
        }
    }

    @Override
    public void ensureSized(int width, int height) {
        if (!ready) {
            return;
        }
        ffx.ensureSized(width, height);
        residual.ensureSized(width, height);

        if (this.width == width && this.height == height && mid != null) {
            return;
        }
        if (mid != null) {
            mid.destroy();
            mid = null;
        }
        RtContext ctx = RtContext.get();
        if (ctx != null && width > 0 && height > 0) {
            mid = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "amd-ffx mid");
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

        // FFX writes a complete beauty plate into outColor (seed + optional shadow delta).
        boolean ffxOk = ffx.dispatch(stack, cmd, inColor, inNormal, inDepth, inMotion,
                mvScaleX, mvScaleY, outColor);
        if (!ffxOk) {
            lastPath = "raw (ffx fail)";
            return false;
        }

        // Mild residual: three compute passes into mid, then a transfer copy back to out.
        // Failure keeps the already-written FFX plate.
        try {
            if (residual.dispatch(stack, cmd, outColor, inNormal, inDepth, inMotion,
                    mvScaleX, mvScaleY, mid)) {
                copyImage(stack, cmd, mid, outColor);
                lastPath = "ffx→residual";
                return true;
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("FFX residual polish failed; keeping FFX plate", t);
        }
        lastPath = "ffx";
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
                .srcSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1))
                .dstSubresource(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1))
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

    @Override
    public void resetHistory() {
        ffx.resetHistory();
        residual.resetHistory();
    }

    @Override
    public void destroy() {
        ffx.destroy();
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
