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
 * Retired buffers are kept for KEEP_FRAMES to ensure in-flight frames finish before destruction.
 */
public final class BlockLightBuffer {

    private static final int MAX_LIGHTS = 4096;
    /** Frames a retired buffer must outlive before it's freed (> frames-in-flight). */
    private static final int KEEP_FRAMES = 4;

    private RtBuffer buffer;
    /** Buffers replaced while older submitted frames may still reference them. */
    private final List<RetiredBuffer> retiredBuffers = new ArrayList<>();
    private int count;
    private long uploadedRevision = Long.MIN_VALUE;
    private long frameCounter = 0;

    /** Helper to track when a buffer was retired. */
    private static class RetiredBuffer {
        final RtBuffer buffer;
        final long retiredAtFrame;

        RetiredBuffer(RtBuffer buffer, long frame) {
            this.buffer = buffer;
            this.retiredAtFrame = frame;
        }
    }

    public boolean upload(RtContext ctx, List<BlockLight> lights, long revision) {
        frameCounter++; // Increment every upload (once per frame)

        if (uploadedRevision == revision) {
            // Still clean up old retired buffers even if no upload
            cleanupRetiredBuffers();
            return false;
        }
        count = Math.min(lights.size(), MAX_LIGHTS);
        uploadedRevision = revision;
        if (count == 0) {
            // Keep any existing buffer; shader gates on blockLightCount == 0.
            cleanupRetiredBuffers();
            return true;
        }

        long requiredSize = (long) count * 16; // vec4 = 16 bytes

        // Create or grow buffer if needed (never shrink mid-frame — avoids thrash).
        if (buffer == null || buffer.size < requiredSize) {
            if (buffer != null) {
                // The composite command buffer is submitted later and may still read this
                // descriptor. Destroying it immediately is a use-after-free on the GPU and
                // presents as VK_ERROR_DEVICE_LOST on AMD during the next swapchain acquire.
                // 🔍 FIX: Track frame number to safely delete after KEEP_FRAMES
                retiredBuffers.add(new RetiredBuffer(buffer, frameCounter));
                dev.comfyfluffy.caustica.CausticaMod.LOGGER.debug(
                    "BlockLightBuffer: retired buffer 0x{} at frame {}, {} retired buffers pending",
                    Long.toHexString(buffer.handle), frameCounter, retiredBuffers.size());
            }
            // The full 64 KiB capacity is tiny and avoids buffer replacement + descriptor churn as
            // the player enters a light-dense area.
            long allocSize = (long) MAX_LIGHTS * 16L;
            buffer = ctx.createBuffer(allocSize,
                org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true, // hostVisible
                "block lights");
        }

        // Clean up retired buffers that are old enough
        cleanupRetiredBuffers();

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

    /**
     * 🔍 FIX: Destroy retired buffers that are old enough (> KEEP_FRAMES frames old).
     * This prevents accumulation of retired buffers causing memory issues.
     */
    private void cleanupRetiredBuffers() {
        retiredBuffers.removeIf(retired -> {
            if (frameCounter - retired.retiredAtFrame > KEEP_FRAMES) {
                dev.comfyfluffy.caustica.CausticaMod.LOGGER.debug(
                    "BlockLightBuffer: destroying retired buffer 0x{} (retired at frame {}, now {})",
                    Long.toHexString(retired.buffer.handle), retired.retiredAtFrame, frameCounter);
                retired.buffer.destroy();
                return true;
            }
            return false;
        });
    }

    public void destroy(RtContext ctx) {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
        for (RetiredBuffer retired : retiredBuffers) {
            retired.buffer.destroy();
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
