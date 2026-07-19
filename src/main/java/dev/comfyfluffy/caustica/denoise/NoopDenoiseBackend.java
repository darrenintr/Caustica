package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageSubresourceLayers;

/**
 * Passthrough denoise backend. Copies {@code inColor} into {@code outColor} via
 * {@link VK10#vkCmdBlitImage} so the upscaler keeps the same input contract;
 * allocates no descriptors, pipelines, samplers, or history. Used when the user
 * picks {@code off} or when the requested backend fails to initialise.
 */
public final class NoopDenoiseBackend implements CausticaDenoiseBackend {

    public static final NoopDenoiseBackend INSTANCE = new NoopDenoiseBackend();

    private int width;
    private int height;
    private boolean ready;

    private NoopDenoiseBackend() {
    }

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        this.ready = true;
    }

    @Override
    public void ensureSized(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public boolean dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready || width == 0 || height == 0) {
            return false;
        }
        VkImageSubresourceLayers layers = VkImageSubresourceLayers.calloc(stack)
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.srcOffsets(0).set(0, 0, 0);
        region.srcOffsets(1).set(width, height, 1);
        region.srcSubresource(layers);
        region.dstOffsets(0).set(0, 0, 0);
        region.dstOffsets(1).set(width, height, 1);
        region.dstSubresource(layers);
        VK10.vkCmdBlitImage(cmd, inColor.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                outColor.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_NEAREST);
        return false;
    }

    @Override
    public void resetHistory() {
        // Noop has no history. Width/height are not reset — the next dispatch will re-blit
        // through the current color regardless.
    }

    @Override
    public void destroy() {
        ready = false;
        width = 0;
        height = 0;
    }

    @Override
    public boolean isReady() {
        return ready;
    }
}
