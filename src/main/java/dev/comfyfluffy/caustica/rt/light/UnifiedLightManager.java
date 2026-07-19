package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.multiplayer.ClientLevel;
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

    private final BlockLightTracker blockLights;
    private final DynamicLightTracker dynamicLights;
    private final List<BlockLight> mergedLights = new ArrayList<>();
    private long lastForceScanFrame = -1L;

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

        if (CausticaConfig.Rt.DynamicLights.ENABLED.value()) {
            dynamicLights.updateFrame(level.entitiesForRendering());
        }

        int cx = Mth.floor(camX);
        int cy = Mth.floor(camY);
        int cz = Mth.floor(camZ);
        boolean force = frameCounter == 0L
                || (frameCounter - lastForceScanFrame) > 120L
                || blockLights.getLightCount() == 0;
        if (blockLights.rescanAround(level, cx, cy, cz, SCAN_RADIUS, SCAN_MOVE_THRESHOLD, force)) {
            lastForceScanFrame = frameCounter;
        }
    }

    /**
     * Get merged list of all lights (static + dynamic), rebased to terrain origin.
     */
    public List<BlockLight> getAllLights(float originX, float originY, float originZ) {
        mergedLights.clear();

        List<BlockLight> staticLights = blockLights.getAllLights();
        for (BlockLight light : staticLights) {
            mergedLights.add(light.rebased(originX, originY, originZ));
            if (mergedLights.size() >= MAX_UPLOAD) {
                return mergedLights;
            }
        }

        if (CausticaConfig.Rt.DynamicLights.ENABLED.value()) {
            for (DynamicLight dyn : dynamicLights.getAllLights()) {
                mergedLights.add(dyn.toBlockLight().rebased(originX, originY, originZ));
                if (mergedLights.size() >= MAX_UPLOAD) {
                    break;
                }
            }
        }

        return mergedLights;
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
        lastForceScanFrame = -1L;
    }
}
