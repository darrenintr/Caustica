package dev.comfyfluffy.caustica.rt.light;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks dynamic light sources attached to entities (held items, glowing mobs, projectiles).
 * Updated every frame based on entity positions and held/dropped items.
 * <p>
 * Design: Per-frame scan of relevant entities, extracting light-emitting items and inherently
 * glowing entities. Integrates with {@link BlockLightTracker} for unified GPU upload.
 */
public final class DynamicLightTracker {

    // Current frame's dynamic lights, rebuilt each frame
    private final List<DynamicLight> lights = new ArrayList<>();

    // Previous frame's lights by entity ID, for smooth updates
    private final Int2ObjectMap<DynamicLight> prevLights = new Int2ObjectOpenHashMap<>();

    public DynamicLightTracker() {
    }

    /**
     * Update dynamic lights for this frame based on current entities.
     * Call once per frame before GPU upload.
     */
    public void updateFrame(Iterable<Entity> entities) {
        lights.clear();

        if (!dev.comfyfluffy.caustica.CausticaConfig.Rt.DynamicLights.ENABLED.value()) {
            return;
        }

        float intensityScale = dev.comfyfluffy.caustica.CausticaConfig.Rt.DynamicLights.INTENSITY_SCALE.value();
        boolean heldItems = dev.comfyfluffy.caustica.CausticaConfig.Rt.DynamicLights.HELD_ITEMS.value();
        boolean droppedItems = dev.comfyfluffy.caustica.CausticaConfig.Rt.DynamicLights.DROPPED_ITEMS.value();
        boolean entityLights = dev.comfyfluffy.caustica.CausticaConfig.Rt.DynamicLights.ENTITIES.value();

        for (Entity entity : entities) {
            if (entity.isRemoved() || !entity.isAlive()) {
                continue;
            }

            int entityId = entity.getId();
            Vector3f pos = new Vector3f(
                (float) entity.getX(),
                (float) entity.getY() + entity.getEyeHeight() * 0.5f,
                (float) entity.getZ()
            );

            // Check held items (players and mobs)
            if (heldItems) {
                int heldLight = getHeldLightLevel(entity);
                if (heldLight > 0) {
                    int scaledLight = Math.min(15, (int) (heldLight * intensityScale));
                    lights.add(DynamicLight.fromLightLevel(
                        entityId,
                        pos,
                        scaledLight,
                        getHeldLightColor(entity)
                    ));
                    continue; // One light per entity
                }
            }

            // Check dropped item entities
            if (droppedItems && entity instanceof ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                int itemLight = getItemLightLevel(stack);
                if (itemLight > 0) {
                    int scaledLight = Math.min(15, (int) (itemLight * intensityScale));
                    lights.add(DynamicLight.fromLightLevel(
                        entityId,
                        pos,
                        scaledLight,
                        getItemLightColor(stack)
                    ));
                    continue;
                }
            }

            // Check projectiles (arrows on fire, etc.)
            if (entityLights && entity instanceof Projectile) {
                if (entity.isOnFire()) {
                    int scaledLight = Math.min(15, (int) (10 * intensityScale));
                    lights.add(DynamicLight.fromLightLevel(
                        entityId,
                        pos,
                        scaledLight,
                        0xFF6A00 // Orange fire
                    ));
                }
            }

            // Check inherently glowing entities (charged creepers, glow squids, etc.)
            if (entityLights) {
                int entityLight = getEntityLightLevel(entity);
                if (entityLight > 0) {
                    int scaledLight = Math.min(15, (int) (entityLight * intensityScale));
                    lights.add(DynamicLight.fromLightLevel(
                        entityId,
                        pos,
                        scaledLight,
                        getEntityLightColor(entity)
                    ));
                }
            }
        }

        // Update previous frame tracking
        prevLights.clear();
        for (DynamicLight light : lights) {
            prevLights.put(light.sourceId(), light);
        }
    }

    /**
     * Get all dynamic lights for GPU upload.
     */
    public List<DynamicLight> getAllLights() {
        return lights;
    }

    /**
     * Get light level from held items (main hand + off hand, take max).
     */
    private int getHeldLightLevel(Entity entity) {
        int maxLight = 0;

        if (entity instanceof Player player) {
            maxLight = Math.max(maxLight, getItemLightLevel(player.getMainHandItem()));
            maxLight = Math.max(maxLight, getItemLightLevel(player.getOffhandItem()));
        } else if (entity instanceof LivingEntity living) {
            // For mobs, check main hand item
            ItemStack mainHand = living.getMainHandItem();
            if (!mainHand.isEmpty()) {
                maxLight = getItemLightLevel(mainHand);
            }
        }

        return maxLight;
    }

    /**
     * Get light color from held items.
     */
    private int getHeldLightColor(Entity entity) {
        if (entity instanceof Player player) {
            ItemStack main = player.getMainHandItem();
            if (!main.isEmpty() && getItemLightLevel(main) > 0) {
                return getItemLightColor(main);
            }
            ItemStack off = player.getOffhandItem();
            if (!off.isEmpty() && getItemLightLevel(off) > 0) {
                return getItemLightColor(off);
            }
        } else if (entity instanceof LivingEntity living) {
            ItemStack mainHand = living.getMainHandItem();
            if (!mainHand.isEmpty()) {
                return getItemLightColor(mainHand);
            }
        }
        return 0xFFDDAA; // Default warm white
    }

    /**
     * Extract light level from an ItemStack.
     * Checks if the item is a light-emitting block.
     */
    private int getItemLightLevel(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        // Try to get the block state from the item
        Block block = Block.byItem(stack.getItem());
        if (block != null) {
            BlockState state = block.defaultBlockState();
            return state.getLightEmission();
        }

        return 0;
    }

    /**
     * Get light color for an item based on its block type.
     */
    private int getItemLightColor(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0xFFDDAA;
        }

        String name = stack.getItem().toString().toLowerCase();
        if (name.contains("lava")) return 0xFF6A00; // orange
        if (name.contains("redstone")) return 0xFF0000; // red
        if (name.contains("glowstone")) return 0xFFDD88; // yellow
        if (name.contains("torch")) return 0xFFCC66; // warm
        if (name.contains("lantern")) return 0xFFDD77; // lantern
        if (name.contains("sea_lantern")) return 0xAAFFFF; // cyan
        if (name.contains("soul")) return 0x4DFFFF; // cyan soul
        if (name.contains("frog")) return 0xCCFFDD; // pale green
        if (name.contains("glow")) return 0x88EEDD; // teal glow

        return 0xFFDDAA; // default warm white
    }

    /**
     * Get inherent light level from entity type (charged creepers, glow squids, etc.).
     */
    private int getEntityLightLevel(Entity entity) {
        // Charged creeper
        if (entity instanceof Creeper creeper && creeper.isPowered()) {
            return 10;
        }

        String entityType = entity.getType().toString().toLowerCase();

        // Glow squid
        if (entityType.contains("glow_squid")) {
            return 12;
        }

        // Blaze
        if (entityType.contains("blaze")) {
            return 10;
        }

        // Magma cube
        if (entityType.contains("magma_cube")) {
            return 8;
        }

        // Entity on fire (check without protected method)
        if (entity.isOnFire()) {
            return 10;
        }

        return 0;
    }

    /**
     * Get light color for inherently glowing entities.
     */
    private int getEntityLightColor(Entity entity) {
        // Charged creeper
        if (entity instanceof Creeper creeper && creeper.isPowered()) {
            return 0x4DFFFF; // Cyan electric
        }

        String entityType = entity.getType().toString().toLowerCase();

        if (entityType.contains("glow_squid")) {
            return 0x88EEDD; // Teal glow
        }
        if (entityType.contains("blaze")) {
            return 0xFFAA33; // Orange-yellow
        }
        if (entityType.contains("magma_cube")) {
            return 0xFF4400; // Red-orange
        }
        if (entity.isOnFire()) {
            return 0xFF6A00; // Orange fire
        }

        return 0xFFDDAA; // Default
    }

    /**
     * Get current light count for stats/debugging.
     */
    public int getLightCount() {
        return lights.size();
    }

    /**
     * Clear all tracked lights (for cleanup).
     */
    public void clear() {
        lights.clear();
        prevLights.clear();
    }
}
