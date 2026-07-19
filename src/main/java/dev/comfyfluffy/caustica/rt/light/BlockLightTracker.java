package dev.comfyfluffy.caustica.rt.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks emissive block positions for ReSTIR direct illumination sampling.
 * <p>
 * Periodic camera-radius rescan with section air early-out. Positions are absolute
 * world coords; {@link UnifiedLightManager} rebases them into terrain space for the GPU.
 */
public final class BlockLightTracker {

    private static final int CHUNK_SHIFT = 4;

    private final Long2ObjectMap<List<BlockLight>> lightsByChunk = new Long2ObjectOpenHashMap<>();
    private final List<BlockLight> allLights = new ArrayList<>();
    private final LongSet occupiedBlocks = new LongOpenHashSet();
    private boolean dirty;

    private int lastScanX = Integer.MIN_VALUE;
    private int lastScanY = Integer.MIN_VALUE;
    private int lastScanZ = Integer.MIN_VALUE;

    public BlockLightTracker() {
    }

    public void addLight(BlockPos pos, int lightLevel, int colorRGB) {
        if (lightLevel <= 0) {
            removeLight(pos);
            return;
        }
        long blockKey = pos.asLong();
        if (occupiedBlocks.contains(blockKey)) {
            removeLight(pos);
        }
        Vector3f worldPos = new Vector3f(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
        BlockLight light = BlockLight.fromLightLevel(worldPos, lightLevel, colorRGB, false);
        long cellKey = cellKey(pos.getX(), pos.getY(), pos.getZ());
        lightsByChunk.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(light);
        occupiedBlocks.add(blockKey);
        dirty = true;
    }

    public void removeLight(BlockPos pos) {
        long cellKey = cellKey(pos.getX(), pos.getY(), pos.getZ());
        List<BlockLight> cell = lightsByChunk.get(cellKey);
        if (cell == null) {
            return;
        }
        boolean removed = cell.removeIf(l ->
                Math.abs(l.position().x() - pos.getX() - 0.5f) < 0.1f
                        && Math.abs(l.position().y() - pos.getY() - 0.5f) < 0.1f
                        && Math.abs(l.position().z() - pos.getZ() - 0.5f) < 0.1f);
        if (!removed) {
            return;
        }
        if (cell.isEmpty()) {
            lightsByChunk.remove(cellKey);
        }
        occupiedBlocks.remove(pos.asLong());
        dirty = true;
    }

    /**
     * Scan a single chunk column for emissive blocks.
     */
    public void scanChunk(Level level, int chunkX, int chunkZ) {
        clearChunkColumn(chunkX, chunkZ);
        if (!(level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
            return;
        }
        harvestChunk(chunk, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Drop every light whose block XZ falls inside the given chunk column.
     */
    public void clearChunk(int chunkX, int chunkZ) {
        clearChunkColumn(chunkX, chunkZ);
    }

    private void clearChunkColumn(int chunkX, int chunkZ) {
        LongSet toRemove = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<List<BlockLight>> entry : lightsByChunk.long2ObjectEntrySet()) {
            List<BlockLight> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                toRemove.add(entry.getLongKey());
                continue;
            }
            // Cell keys encode cx/cy/cz — check first light's world XZ.
            BlockLight sample = list.get(0);
            int lightCx = Math.floorDiv(Math.round(sample.position().x() - 0.5f), 16);
            int lightCz = Math.floorDiv(Math.round(sample.position().z() - 0.5f), 16);
            if (lightCx != chunkX || lightCz != chunkZ) {
                continue;
            }
            toRemove.add(entry.getLongKey());
            for (BlockLight l : list) {
                int bx = Math.round(l.position().x() - 0.5f);
                int by = Math.round(l.position().y() - 0.5f);
                int bz = Math.round(l.position().z() - 0.5f);
                occupiedBlocks.remove(BlockPos.asLong(bx, by, bz));
            }
        }
        if (toRemove.isEmpty()) {
            return;
        }
        for (long key : toRemove) {
            lightsByChunk.remove(key);
        }
        dirty = true;
    }

    /**
     * Rescan emissive blocks in a radius around the camera.
     *
     * @return true if a rescan actually ran
     */
    public boolean rescanAround(Level level, int centerX, int centerY, int centerZ,
                                int radiusBlocks, int moveThreshold, boolean force) {
        if (!force
                && Math.abs(centerX - lastScanX) < moveThreshold
                && Math.abs(centerY - lastScanY) < moveThreshold
                && Math.abs(centerZ - lastScanZ) < moveThreshold
                && !allLights.isEmpty()) {
            return false;
        }

        lightsByChunk.clear();
        allLights.clear();
        occupiedBlocks.clear();
        dirty = true;

        int chunkR = Math.max(1, (radiusBlocks + 15) >> 4);
        int minCx = (centerX >> 4) - chunkR;
        int maxCx = (centerX >> 4) + chunkR;
        int minCz = (centerZ >> 4) - chunkR;
        int maxCz = (centerZ >> 4) + chunkR;
        int minY = centerY - radiusBlocks;
        int maxY = centerY + radiusBlocks;
        int radiusSq = radiusBlocks * radiusBlocks;

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                    continue;
                }
                harvestChunk(chunk, minY, maxY, centerX, centerY, centerZ, radiusSq);
            }
        }

        lastScanX = centerX;
        lastScanY = centerY;
        lastScanZ = centerZ;
        rebuildBuffer();
        return true;
    }

    private void harvestChunk(LevelChunk chunk, int minY, int maxY,
                              int centerX, int centerY, int centerZ, int radiusSq) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean limitRadius = radiusSq < Integer.MAX_VALUE / 4;

        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int baseY = (minSectionY + si) << 4;
            if (baseY + 15 < minY || baseY > maxY) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = baseY + ly;
                if (wy < minY || wy > maxY) {
                    continue;
                }
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        int lightLevel = state.getLightEmission();
                        if (lightLevel <= 0) {
                            continue;
                        }
                        int wx = (chunkX << 4) + lx;
                        int wz = (chunkZ << 4) + lz;
                        if (limitRadius) {
                            int dx = wx - centerX;
                            int dy = wy - centerY;
                            int dz = wz - centerZ;
                            if (dx * dx + dy * dy + dz * dz > radiusSq) {
                                continue;
                            }
                        }
                        pos.set(wx, wy, wz);
                        addLight(pos, lightLevel, getLightColor(state));
                    }
                }
            }
        }
    }

    public void rebuildBuffer() {
        if (!dirty) {
            return;
        }
        allLights.clear();
        for (List<BlockLight> cell : lightsByChunk.values()) {
            allLights.addAll(cell);
        }
        dirty = false;
    }

    public List<BlockLight> getAllLights() {
        if (dirty) {
            rebuildBuffer();
        }
        return allLights;
    }

    public int[] queryNearestLights(Vector3f worldPos, int maxCount, float maxRadius) {
        if (dirty) {
            rebuildBuffer();
        }
        if (allLights.isEmpty()) {
            return new int[0];
        }

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

    private static long cellKey(int x, int y, int z) {
        int cx = x >> CHUNK_SHIFT;
        int cy = y >> CHUNK_SHIFT;
        int cz = z >> CHUNK_SHIFT;
        return ((long) cx << 42) | ((long) (cy & 0x1FFFFF) << 21) | (long) (cz & 0x1FFFFF);
    }

    private static int getLightColor(BlockState state) {
        String name = state.getBlock().getName().getString().toLowerCase();
        if (name.contains("lava") || name.contains("magma")) return 0xFF6A00;
        if (name.contains("redstone")) return 0xFF2020;
        if (name.contains("soul")) return 0x66DDFF;
        if (name.contains("glowstone") || name.contains("shroomlight")) return 0xFFDD88;
        if (name.contains("sea") || name.contains("prismarine") || name.contains("conduit")) return 0x66FFEE;
        if (name.contains("torch") || name.contains("lantern") || name.contains("campfire")) return 0xFFCC66;
        if (name.contains("amethyst") || name.contains("sculk")) return 0xAA88FF;
        if (name.contains("copper") || name.contains("oxid")) return 0x88DDAA;
        return 0xFFDDAA;
    }

    private record LightDistance(int index, float distSq) {}

    public int getLightCount() {
        if (dirty) {
            rebuildBuffer();
        }
        return allLights.size();
    }

    public boolean isDirty() {
        return dirty;
    }
}
