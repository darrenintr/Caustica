package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GPU storage buffer for block lights (vec4 array).
 * Uses RtContext's VMA-backed host-visible buffer for simple CPU→GPU uploads.
 */
public final class BlockLightBuffer {

    private static final int MAX_LIGHTS = 4096;

    private RtBuffer buffer;
    /** Buffers replaced while older submitted frames may still reference them. */
    private final List<RtBuffer> retiredBuffers = new ArrayList<>();
    private int count;
    private long uploadedRevision = Long.MIN_VALUE;

    public boolean upload(RtContext ctx, List<BlockLight> lights, long revision) {
        if (uploadedRevision == revision) {
            return false;
        }
        count = Math.min(lights.size(), MAX_LIGHTS);
        uploadedRevision = revision;
        if (count == 0) {
            // Keep any existing buffer; shader gates on blockLightCount == 0.
            return true;
        }

        long requiredSize = (long) count * 16; // vec4 = 16 bytes

        // Create or grow buffer if needed (never shrink mid-frame — avoids thrash).
        if (buffer == null || buffer.size < requiredSize) {
            if (buffer != null) {
                // The composite command buffer is submitted later and may still read this
                // descriptor. Destroying it immediately is a use-after-free on the GPU and
                // presents as VK_ERROR_DEVICE_LOST on AMD during the next swapchain acquire.
                retiredBuffers.add(buffer);
            }
            // The full 64 KiB capacity is tiny and avoids buffer replacement + descriptor churn as
            // the player enters a light-dense area.
            long allocSize = (long) MAX_LIGHTS * 16L;
            buffer = ctx.createBuffer(allocSize,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true, // hostVisible
                "block lights");
        }

        // Write directly to mapped memory
        if (buffer.mapped != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(buffer.mapped, count * 4);
            for (int i = 0; i < count; i++) {
                BlockLight light = lights.get(i);
                fb.put(light.position().x());
                fb.put(light.position().y());
                fb.put(light.position().z());
                fb.put(Float.intBitsToFloat(BlockLight.packMetadata(
                        light.intensity(), light.colorPacked(), light.dynamic())));
            }
            fb.flip();
        }
        return true;
    }

    public void destroy(RtContext ctx) {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
        for (RtBuffer retired : retiredBuffers) {
            retired.destroy();
        }
        retiredBuffers.clear();
        count = 0;
        uploadedRevision = Long.MIN_VALUE;
    }

    public long buffer() {
        return buffer != null ? buffer.handle : 0L;
    }

    public int count() {
        return count;
    }
}
