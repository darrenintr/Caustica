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
            Blocks.QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.QUARTZ_SLAB, Blocks.SMOOTH_QUARTZ_SLAB,
            Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POLISHED_GRANITE, Blocks.POLISHED_GRANITE_STAIRS, Blocks.POLISHED_GRANITE_SLAB,
            Blocks.POLISHED_DIORITE, Blocks.POLISHED_DIORITE_STAIRS, Blocks.POLISHED_DIORITE_SLAB,
            Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE_STAIRS, Blocks.POLISHED_ANDESITE_SLAB,
            Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE_STAIRS, Blocks.POLISHED_DEEPSLATE_SLAB, Blocks.POLISHED_DEEPSLATE_WALL,
            Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_STAIRS, Blocks.POLISHED_BLACKSTONE_SLAB, Blocks.POLISHED_BLACKSTONE_WALL,
            Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB, Blocks.POLISHED_BLACKSTONE_BRICK_WALL,
            Blocks.PRISMARINE, Blocks.PRISMARINE_STAIRS, Blocks.PRISMARINE_SLAB, Blocks.PRISMARINE_WALL,
            Blocks.PRISMARINE_BRICKS, Blocks.PRISMARINE_BRICK_STAIRS, Blocks.PRISMARINE_BRICK_SLAB,
            Blocks.DARK_PRISMARINE, Blocks.DARK_PRISMARINE_STAIRS, Blocks.DARK_PRISMARINE_SLAB);

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
