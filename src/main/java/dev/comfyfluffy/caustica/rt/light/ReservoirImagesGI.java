package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

/**
 * Ping-pong GI reservoir images for ReSTIR Global Illumination temporal reuse.
 * Each pixel stores a ReservoirGI split across two rgba32f images (32 bytes total):
 *   A: dir.xyz + wSum
 *   B: M + age + targetPdf + spare
 * Simplified version using RtContext's existing image creation.
 */
public final class ReservoirImagesGI {

    private RtImage currentA;
    private RtImage currentB;
    private RtImage previousA;
    private RtImage previousB;

    public void ensureSized(RtContext ctx, int width, int height) {
        if (currentA != null && currentA.width == width && currentA.height == height) {
            return;
        }
        destroy();

        currentA = ctx.createStorageImage(width, height,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT, "gi-reservoir-current-A");
        currentB = ctx.createStorageImage(width, height,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT, "gi-reservoir-current-B");
        previousA = ctx.createStorageImage(width, height,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT, "gi-reservoir-previous-A");
        previousB = ctx.createStorageImage(width, height,
            org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT, "gi-reservoir-previous-B");
    }

    /**
     * Swap current ↔ previous for next frame's temporal reuse.
     */
    public void swap() {
        RtImage tmpA = currentA; currentA = previousA; previousA = tmpA;
        RtImage tmpB = currentB; currentB = previousB; previousB = tmpB;
    }

    public void destroy() {
        if (currentA != null) { currentA.destroy(); currentA = null; }
        if (currentB != null) { currentB.destroy(); currentB = null; }
        if (previousA != null) { previousA.destroy(); previousA = null; }
        if (previousB != null) { previousB.destroy(); previousB = null; }
    }

    public RtImage currentA() { return currentA; }
    public RtImage currentB() { return currentB; }
    public RtImage previousA() { return previousA; }
    public RtImage previousB() { return previousB; }
}