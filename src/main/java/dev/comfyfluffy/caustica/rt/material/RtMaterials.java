package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Heuristic PBR material classifier for blocks that carry only albedo. Assigns each block a
 * {@code (roughness, metalness)} pair from its {@link SoundType} (metal/glass) plus a small set of
 * known smooth dielectrics. Per-prim {@code mat} lanes store the pair; the GGX BRDF reads them.
 */
public final class RtMaterials {
    private RtMaterials() {}

    /** Default terrain (dirt/wood/stone): very matte so desert sand doesn't read plastic. */
    private static final float DEFAULT_ROUGH = 0.95f;
    private static final float METAL_ROUGH = 0.3f;
    private static final float GLASS_ROUGH = 0.1f;
    private static final float SMOOTH_ROUGH = 0.4f;
    private static final float SAND_ROUGH = 0.98f;

    /** Water roughness; near-smooth so DLSS-RR resolves stable reflections. */
    public static final float WATER_ROUGH = 0.08f;
    /** Lava: opaque emitter, moderately rough. */
    public static final float LAVA_ROUGH = 0.7f;
    /** Default entity roughness. */
    public static final float ENTITY_ROUGH = 0.85f;

    private static final Set<Block> SMOOTH = Set.of(
            Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
            Blocks.SMOOTH_STONE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POLISHED_GRANITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE,
            Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_BLACKSTONE,
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);

    private static final Set<Block> SANDY = Set.of(
            Blocks.SAND, Blocks.RED_SAND, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.CUT_SANDSTONE, Blocks.CUT_RED_SANDSTONE,
            Blocks.CHISELED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE,
            Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE,
            Blocks.SANDSTONE_STAIRS, Blocks.RED_SANDSTONE_STAIRS,
            Blocks.SANDSTONE_SLAB, Blocks.RED_SANDSTONE_SLAB,
            Blocks.SANDSTONE_WALL, Blocks.RED_SANDSTONE_WALL,
            Blocks.GRAVEL, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
            Blocks.MUD, Blocks.CLAY);

    /** Perceptual roughness for this block's surface. */
    public static float roughness(BlockState state) {
        if (state == null) {
            return DEFAULT_ROUGH;
        }
        SoundType sound = state.getSoundType();
        if (isMetal(sound)) {
            return METAL_ROUGH;
        }
        if (sound == SoundType.GLASS) {
            return GLASS_ROUGH;
        }
        if (SMOOTH.contains(state.getBlock())) {
            return SMOOTH_ROUGH;
        }
        if (SANDY.contains(state.getBlock()) || sound == SoundType.SAND || sound == SoundType.GRAVEL) {
            return SAND_ROUGH;
        }
        return DEFAULT_ROUGH;
    }

    /** Metalness (1 = conductor: F0 tinted by albedo, no diffuse; 0 = dielectric). */
    public static float metalness(BlockState state) {
        return state != null && isMetal(state.getSoundType()) ? 1f : 0f;
    }

    private static boolean isMetal(SoundType sound) {
        return sound == SoundType.METAL || sound == SoundType.COPPER
                || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.ANVIL
                || sound == SoundType.CHAIN;
    }
}
