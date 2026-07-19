package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * GPU storage buffer for block lights (vec4 array).
 * Uses RtContext's VMA-backed host-visible buffer for simple CPU→GPU uploads.
 */
public final class BlockLightBuffer {

    private static final int MAX_LIGHTS = 4096;

    private RtBuffer buffer;
    private int count;

    public void upload(RtContext ctx, List<BlockLight> lights) {
        count = Math.min(lights.size(), MAX_LIGHTS);
        if (count == 0) {
            // Keep any existing buffer; shader gates on blockLightCount == 0.
            return;
        }

        long requiredSize = (long) count * 16; // vec4 = 16 bytes

        // Create or grow buffer if needed (never shrink mid-frame — avoids thrash).
        if (buffer == null || buffer.size < requiredSize) {
            if (buffer != null) {
                buffer.destroy();
            }
            // Create host-visible buffer (persistently mapped). Round up a bit for growth.
            long allocSize = Math.max(requiredSize, 64L * 16L);
            buffer = ctx.createBuffer(allocSize,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true, // hostVisible
                "block lights");
        }

        // Write directly to mapped memory
        if (buffer.mapped != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(buffer.mapped, count * 4);
            float[] vec4 = new float[4];
            for (int i = 0; i < count; i++) {
                lights.get(i).writeToBuffer(vec4, 0);
                fb.put(vec4);
            }
            fb.flip();
        }
    }

    public void destroy(RtContext ctx) {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
        count = 0;
    }

    public long buffer() {
        return buffer != null ? buffer.handle : 0L;
    }

    public int count() {
        return count;
    }
}
