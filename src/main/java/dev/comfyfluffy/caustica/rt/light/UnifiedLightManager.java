package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.CausticaConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified manager for both static block lights and dynamic entity lights.
 * Merges both sources into a single list for GPU upload via {@link BlockLightBuffer}.
 * <p>
 * Positions are rebased into terrain origin space so they match raygen hit positions
 * ({@code hitPos} lives in the same rebased frame as {@code camOffset}).
 */
public final class UnifiedLightManager {

    /** How far around the player to harvest emissive blocks (blocks). */
    private static final int SCAN_RADIUS = 64;
    /** Re-scan only after the player moves this many blocks. */
    private static final int SCAN_MOVE_THRESHOLD = 16;
    /** Hard cap of lights uploaded each frame (matches GPU buffer). */
    private static final int MAX_UPLOAD = 2048;
    /** Entity-held lights are cheap visually at 30 Hz and expensive to rediscover at render Hz. */
    private static final int DYNAMIC_UPDATE_INTERVAL_FRAMES = 2;
    private static final int MAX_DIRTY_LIGHTS_PER_FRAME = 64;
    private static final int MAX_QUEUED_DIRTY_LIGHTS = 8192;

    private final BlockLightTracker blockLights;
    private final DynamicLightTracker dynamicLights;
    private final List<BlockLight> mergedLights = new ArrayList<>();
    private boolean staticScanInitialized;
    private long lastDynamicUpdateFrame = Long.MIN_VALUE;
    private long mergedStaticRevision = Long.MIN_VALUE;
    private long mergedDynamicRevision = Long.MIN_VALUE;
    private int mergedOriginX = Integer.MIN_VALUE;
    private int mergedOriginY = Integer.MIN_VALUE;
    private int mergedOriginZ = Integer.MIN_VALUE;
    private long mergedRevision;
    private final LongOpenHashSet dirtyBlocks = new LongOpenHashSet();

    public UnifiedLightManager() {
        this.blockLights = new BlockLightTracker();
        this.dynamicLights = new DynamicLightTracker();
    }

    /**
     * Update dynamic lights + periodically rescan nearby emissive blocks.
     */
    public void updateFrame(ClientLevel level, double camX, double camY, double camZ, long frameCounter) {
        if (level == null) {
            return;
        }

        if (CausticaConfig.Rt.DynamicLights.ENABLED.value()
                && (lastDynamicUpdateFrame == Long.MIN_VALUE
                || frameCounter - lastDynamicUpdateFrame >= DYNAMIC_UPDATE_INTERVAL_FRAMES)) {
            dynamicLights.updateFrame(level.entitiesForRendering());
            lastDynamicUpdateFrame = frameCounter;
        } else if (!CausticaConfig.Rt.DynamicLights.ENABLED.value() && dynamicLights.getLightCount() != 0) {
            dynamicLights.clear();
        }

        int cx = Mth.floor(camX);
        int cy = Mth.floor(camY);
        int cz = Mth.floor(camZ);
        // An empty scan is still a valid scan. The old lightCount==0 force condition repeated the
        // complete 64-block harvest every render frame in dark areas, causing periodic CPU-sized hitches.
        boolean scanned = blockLights.rescanAround(level, cx, cy, cz, SCAN_RADIUS,
                SCAN_MOVE_THRESHOLD, !staticScanInitialized);
        staticScanInitialized |= scanned;
        if (scanned) {
            dirtyBlocks.clear();
        } else {
            refreshDirtyBlocks(level);
        }
    }

    /**
     * Queue vanilla block updates for bounded incremental emissive-light maintenance. Large explosions
     * are amortized across frames instead of triggering another radius-wide synchronous scan.
     */
    public void markBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int loX = Math.min(minX, maxX);
        int loY = Math.min(minY, maxY);
        int loZ = Math.min(minZ, maxZ);
        int hiX = Math.max(minX, maxX);
        int hiY = Math.max(minY, maxY);
        int hiZ = Math.max(minZ, maxZ);
        int inspected = 0;
        for (int y = loY; y <= hiY && inspected < MAX_QUEUED_DIRTY_LIGHTS; y++) {
            for (int z = loZ; z <= hiZ && inspected < MAX_QUEUED_DIRTY_LIGHTS; z++) {
                for (int x = loX; x <= hiX && inspected++ < MAX_QUEUED_DIRTY_LIGHTS; x++) {
                    dirtyBlocks.add(BlockPos.asLong(x, y, z));
                }
            }
        }
    }

    private void refreshDirtyBlocks(ClientLevel level) {
        int remaining = MAX_DIRTY_LIGHTS_PER_FRAME;
        for (LongIterator it = dirtyBlocks.iterator(); it.hasNext() && remaining-- > 0; ) {
            blockLights.refreshLight(level, BlockPos.of(it.nextLong()));
            it.remove();
        }
    }

    /**
     * Get merged list of all lights (static + dynamic), rebased to terrain origin.
     */
    public List<BlockLight> getAllLights(float originX, float originY, float originZ) {
        int ox = Mth.floor(originX);
        int oy = Mth.floor(originY);
        int oz = Mth.floor(originZ);
        long staticRevision = blockLights.revision();
        long dynamicRevision = dynamicLights.revision();
        if (staticRevision == mergedStaticRevision && dynamicRevision == mergedDynamicRevision
                && ox == mergedOriginX && oy == mergedOriginY && oz == mergedOriginZ) {
            return mergedLights;
        }

        mergedLights.clear();

        List<BlockLight> staticLights = blockLights.getAllLights();
        for (BlockLight light : staticLights) {
            mergedLights.add(light.rebased(ox, oy, oz));
            if (mergedLights.size() >= MAX_UPLOAD) {
                break;
            }
        }

        if (mergedLights.size() < MAX_UPLOAD && CausticaConfig.Rt.DynamicLights.ENABLED.value()) {
            for (DynamicLight dyn : dynamicLights.getAllLights()) {
                mergedLights.add(dyn.toBlockLight().rebased(ox, oy, oz));
                if (mergedLights.size() >= MAX_UPLOAD) {
                    break;
                }
            }
        }

        mergedStaticRevision = staticRevision;
        mergedDynamicRevision = dynamicRevision;
        mergedOriginX = ox;
        mergedOriginY = oy;
        mergedOriginZ = oz;
        mergedRevision++;
        return mergedLights;
    }

    /** Monotonic revision of the terrain-rebased list returned by {@link #getAllLights}. */
    public long revision() {
        return mergedRevision;
    }

    /**
     * Get the block light tracker for chunk scanning and updates.
     */
    public BlockLightTracker getBlockLights() {
        return blockLights;
    }

    /**
     * Get the dynamic light tracker for entity light management.
     */
    public DynamicLightTracker getDynamicLights() {
        return dynamicLights;
    }

    /**
     * Get total light count (static + dynamic), pre-cap.
     */
    public int getTotalLightCount() {
        int count = blockLights.getLightCount();
        if (CausticaConfig.Rt.DynamicLights.ENABLED.value()) {
            count += dynamicLights.getLightCount();
        }
        return Math.min(count, MAX_UPLOAD);
    }

    /**
     * Clear all lights (for cleanup/reload).
     */
    public void clear() {
        dynamicLights.clear();
        staticScanInitialized = false;
        lastDynamicUpdateFrame = Long.MIN_VALUE;
        mergedStaticRevision = Long.MIN_VALUE;
        mergedDynamicRevision = Long.MIN_VALUE;
        mergedOriginX = Integer.MIN_VALUE;
        mergedOriginY = Integer.MIN_VALUE;
        mergedOriginZ = Integer.MIN_VALUE;
        mergedLights.clear();
        dirtyBlocks.clear();
        mergedRevision++;
    }
}
