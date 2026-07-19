package dev.comfyfluffy.caustica.rt.light;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A single dynamic light source attached to an entity or item.
 * Similar to {@link BlockLight} but updates position every frame.
 */
public record DynamicLight(
        int sourceId,        // entity ID or unique identifier
        Vector3fc position,  // world position (updated each frame)
        float intensity,     // light level 0-15, normalized to 0-1
        int colorPacked      // RGB888: (r << 16) | (g << 8) | b
) {
    /**
     * Pack into vec4 for GPU buffer — same layout as {@link BlockLight}.
     */
    public void writeToBuffer(float[] buf, int offset) {
        buf[offset] = position.x();
        buf[offset + 1] = position.y();
        buf[offset + 2] = position.z();
        buf[offset + 3] = Float.intBitsToFloat(
                BlockLight.packMetadata(intensity, colorPacked, true)
        );
    }

    public BlockLight toBlockLight() {
        return new BlockLight(position, intensity, colorPacked, true);
    }

    public static DynamicLight fromLightLevel(int sourceId, Vector3fc pos, int lightLevel, int colorRGB) {
        float intensity = lightLevel / 15.0f;
        return new DynamicLight(sourceId, new Vector3f(pos), intensity, colorRGB);
    }

    /**
     * Create a new DynamicLight with updated position (for per-frame tracking).
     */
    public DynamicLight withPosition(Vector3fc newPos) {
        return new DynamicLight(sourceId, new Vector3f(newPos), intensity, colorPacked);
    }
}
