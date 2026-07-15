package dev.comfyfluffy.caustica.rt.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks emissive block positions for ReSTIR direct illumination sampling.
 * <p>
 * Design: chunk-based spatial hash for O(1) nearest-neighbor queries.
 * Updates are incremental (add/remove on block change), not full rescan.
 */
public final class BlockLightTracker {

    private static final int CHUNK_SHIFT = 4; // 16×16×16 cells
    private static final int CHUNK_MASK = (1 << CHUNK_SHIFT) - 1;

    // Spatial hash: chunkKey → list of lights in that 16³ cell
    private final Long2ObjectMap<List<BlockLight>> lightsByChunk = new Long2ObjectOpenHashMap<>();
    private final List<BlockLight> allLights = new ArrayList<>();
    private boolean dirty;

    public BlockLightTracker() {
    }

    /**
     * Add or update a light at the given block position.
     */
    public void addLight(BlockPos pos, int lightLevel, int colorRGB) {
        if (lightLevel <= 0) {
            removeLight(pos);
            return;
        }
        Vector3f worldPos = new Vector3f(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
        BlockLight light = BlockLight.fromLightLevel(worldPos, lightLevel, colorRGB);
        long chunkKey = chunkKey(pos);
        lightsByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(light);
        dirty = true;
    }

    /**
     * Remove a light at the given position.
     */
    public void removeLight(BlockPos pos) {
        long chunkKey = chunkKey(pos);
        List<BlockLight> chunk = lightsByChunk.get(chunkKey);
        if (chunk != null) {
            chunk.removeIf(l ->
                Math.abs(l.position().x() - pos.getX() - 0.5f) < 0.1f &&
                Math.abs(l.position().y() - pos.getY() - 0.5f) < 0.1f &&
                Math.abs(l.position().z() - pos.getZ() - 0.5f) < 0.1f
            );
            if (chunk.isEmpty()) {
                lightsByChunk.remove(chunkKey);
            }
            dirty = true;
        }
    }

    /**
     * Scan a chunk (16×256×16) for emissive blocks and populate tracker.
     * Called when a chunk is loaded or invalidated.
     */
    public void scanChunk(Level level, int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -64; y < 320; y++) { // Minecraft 1.18+ world height
                    pos.set(minX + x, y, minZ + z);
                    BlockState state = level.getBlockState(pos);
                    int lightLevel = state.getLightEmission();
                    if (lightLevel > 0) {
                        int color = getLightColor(state);
                        addLight(pos, lightLevel, color);
                    }
                }
            }
        }
    }

    /**
     * Clear all lights in a chunk when it unloads.
     */
    public void clearChunk(int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        for (int cx = 0; cx < 16; cx += 16) {
            for (int cz = 0; cz < 16; cz += 16) {
                for (int cy = -16; cy < 256; cy += 16) {
                    long key = chunkKey(minX + cx, cy, minZ + cz);
                    lightsByChunk.remove(key);
                }
            }
        }
        dirty = true;
    }

    /**
     * Rebuild flat GPU buffer (call when dirty).
     */
    public void rebuildBuffer() {
        if (!dirty) return;
        allLights.clear();
        for (List<BlockLight> chunk : lightsByChunk.values()) {
            allLights.addAll(chunk);
        }
        dirty = false;
    }

    /**
     * Get all lights for GPU upload (vec4 array).
     */
    public List<BlockLight> getAllLights() {
        if (dirty) rebuildBuffer();
        return allLights;
    }

    /**
     * Query nearest N lights within radius from a world position.
     * Returns indices into allLights array.
     */
    public int[] queryNearestLights(Vector3f worldPos, int maxCount, float maxRadius) {
        if (dirty) rebuildBuffer();
        if (allLights.isEmpty()) return new int[0];

        // Simple brute-force for now; can optimize with spatial hash later
        List<LightDistance> candidates = new ArrayList<>();
        float maxRadiusSq = maxRadius * maxRadius;

        for (int i = 0; i < allLights.size(); i++) {
            BlockLight light = allLights.get(i);
            float dx = light.position().x() - worldPos.x;
            float dy = light.position().y() - worldPos.y;
            float dz = light.position().z() - worldPos.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= maxRadiusSq) {
                candidates.add(new LightDistance(i, distSq));
            }
        }

        candidates.sort((a, b) -> Float.compare(a.distSq, b.distSq));
        int count = Math.min(maxCount, candidates.size());
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = candidates.get(i).index;
        }
        return result;
    }

    private static long chunkKey(BlockPos pos) {
        return chunkKey(pos.getX(), pos.getY(), pos.getZ());
    }

    private static long chunkKey(int x, int y, int z) {
        int cx = x >> CHUNK_SHIFT;
        int cy = y >> CHUNK_SHIFT;
        int cz = z >> CHUNK_SHIFT;
        return ((long) cx << 42) | ((long) cy << 21) | (long) cz;
    }

    private static int getLightColor(BlockState state) {
        // Heuristic: lava=orange, redstone=red, glowstone=yellow, default=warm white
        String name = state.getBlock().getName().getString().toLowerCase();
        if (name.contains("lava")) return 0xFF6A00; // orange
        if (name.contains("redstone")) return 0xFF0000; // red
        if (name.contains("glowstone")) return 0xFFDD88; // yellow
        if (name.contains("torch")) return 0xFFCC66; // warm
        if (name.contains("lantern")) return 0xFFDD77;
        return 0xFFDDAA; // default warm white
    }

    private record LightDistance(int index, float distSq) {}

    public int getLightCount() {
        if (dirty) rebuildBuffer();
        return allLights.size();
    }

    public boolean isDirty() {
        return dirty;
    }
}
