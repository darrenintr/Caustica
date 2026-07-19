package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;

/**
 * Camera-centred vanilla light field for hybrid GI.
 * <p>
 * Each cell packs {@code (sky << 4) | block} as a single byte (0..15 each).
 * Uploaded as a host-visible SSBO and sampled in {@code world.rgen} as a
 * low-variance ambient base under RT direct lighting.
 */
public final class LightFieldVolume {

    /** Edge length in blocks. 40³ ≈ 64 KB — cheap enough for per-frame-ish refresh. */
    public static final int SIZE = 40;
    private static final int CELL_COUNT = SIZE * SIZE * SIZE;
    private static final int MOVE_THRESHOLD = 2;
    private static final int FORCE_INTERVAL = 8;

    private final byte[] cells = new byte[CELL_COUNT];
    private RtBuffer buffer;
    private int originX = Integer.MIN_VALUE / 4;
    private int originY = Integer.MIN_VALUE / 4;
    private int originZ = Integer.MIN_VALUE / 4;
    private int lastUploadFrame = -999;
    private boolean valid;

    public void update(ClientLevel level, int centerX, int centerY, int centerZ, int frameCounter) {
        if (level == null) {
            valid = false;
            return;
        }
        int half = SIZE / 2;
        int newOx = centerX - half;
        int newOy = centerY - half;
        int newOz = centerZ - half;

        boolean moved = Math.abs(newOx - originX) >= MOVE_THRESHOLD
                || Math.abs(newOy - originY) >= MOVE_THRESHOLD
                || Math.abs(newOz - originZ) >= MOVE_THRESHOLD;
        boolean stale = (frameCounter - lastUploadFrame) >= FORCE_INTERVAL;
        if (!moved && !stale && valid) {
            return;
        }

        originX = newOx;
        originY = newOy;
        originZ = newOz;
        lastUploadFrame = frameCounter;

        LevelLightEngine engine = level.getLightEngine();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    pos.set(originX + x, originY + y, originZ + z);
                    int block = engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos);
                    int sky = engine.getLayerListener(LightLayer.SKY).getLightValue(pos);
                    if (block < 0) block = 0;
                    if (sky < 0) sky = 0;
                    if (block > 15) block = 15;
                    if (sky > 15) sky = 15;
                    cells[i++] = (byte) ((sky << 4) | block);
                }
            }
        }
        valid = true;
    }

    public void upload(RtContext ctx) {
        if (!valid) {
            return;
        }
        long bytes = CELL_COUNT;
        if (buffer == null || buffer.size < bytes) {
            if (buffer != null) {
                buffer.destroy();
            }
            buffer = ctx.createBuffer(bytes, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "light field");
        }
        if (buffer.mapped != 0L) {
            ByteBuffer bb = MemoryUtil.memByteBuffer(buffer.mapped, CELL_COUNT);
            bb.clear();
            bb.put(cells, 0, CELL_COUNT);
        }
    }

    public void destroy() {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
        valid = false;
    }

    public long buffer() {
        return buffer != null ? buffer.handle : 0L;
    }

    public long byteSize() {
        return CELL_COUNT;
    }

    public boolean valid() {
        return valid && buffer != null && buffer.handle != 0L;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public int size() {
        return SIZE;
    }
}
