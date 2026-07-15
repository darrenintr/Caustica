package dev.comfyfluffy.caustica.rt.light;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A single emissive block light source (torch, glowstone, lava, etc.).
 * Immutable value type for GPU upload.
 */
public record BlockLight(
        Vector3fc position,  // world position (block center)
        float intensity,     // light level 0-15, normalized to 0-1
        int colorPacked      // RGB888: (r << 16) | (g << 8) | b
) {
    /**
     * Pack into vec4 for GPU buffer: (xyz, w) where w = (intensity, colorR, colorG, colorB) packed.
     */
    public void writeToBuffer(float[] buf, int offset) {
        buf[offset] = position.x();
        buf[offset + 1] = position.y();
        buf[offset + 2] = position.z();
        // w: float with intensity in high 16 bits, color index in low 16 bits (unpacked in shader)
        buf[offset + 3] = Float.intBitsToFloat(
                (Float.floatToRawIntBits(intensity) & 0xFFFF0000) | (colorPacked & 0xFFFF)
        );
    }

    public static BlockLight fromLightLevel(Vector3fc pos, int lightLevel, int colorRGB) {
        float intensity = lightLevel / 15.0f;
        return new BlockLight(new Vector3f(pos), intensity, colorRGB);
    }
}
