package dev.comfyfluffy.caustica.rt.light;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A single emissive block light source (torch, glowstone, lava, etc.).
 * Immutable value type for GPU upload.
 * <p>
 * GPU pack (vec4.w as raw bits):
 * <pre>
 *   bits 31..16 : IEEE float16 intensity (0..1+)
 *   bit  15     : dynamic flag (1 = entity/held light → lower temporal reuse)
 *   bits 14..10 : R5
 *   bits  9..5  : G5
 *   bits  4..0  : B5
 * </pre>
 */
public record BlockLight(
        Vector3fc position,  // world or terrain-rebased position (shader-space)
        float intensity,     // light level 0-15, normalized to 0-1
        int colorPacked,     // RGB888: (r << 16) | (g << 8) | b
        boolean dynamic
) {
    public BlockLight(Vector3fc position, float intensity, int colorPacked) {
        this(position, intensity, colorPacked, false);
    }

    /**
     * Pack into vec4 for GPU buffer: xyz = position, w = packed intensity/color/flags.
     */
    public void writeToBuffer(float[] buf, int offset) {
        buf[offset] = position.x();
        buf[offset + 1] = position.y();
        buf[offset + 2] = position.z();
        buf[offset + 3] = Float.intBitsToFloat(packMetadata(intensity, colorPacked, dynamic));
    }

    public BlockLight rebased(float originX, float originY, float originZ) {
        return new BlockLight(
                new Vector3f(position.x() - originX, position.y() - originY, position.z() - originZ),
                intensity,
                colorPacked,
                dynamic
        );
    }

    public static BlockLight fromLightLevel(Vector3fc pos, int lightLevel, int colorRGB) {
        return fromLightLevel(pos, lightLevel, colorRGB, false);
    }

    public static BlockLight fromLightLevel(Vector3fc pos, int lightLevel, int colorRGB, boolean dynamic) {
        float intensity = lightLevel / 15.0f;
        return new BlockLight(new Vector3f(pos), intensity, colorRGB, dynamic);
    }

    static int packMetadata(float intensity, int colorRgb888, boolean dynamic) {
        int halfI = floatToHalfBits(Math.max(0.0f, intensity)) & 0xFFFF;
        int r5 = Math.min(31, ((colorRgb888 >> 16) & 0xFF) >> 3);
        int g5 = Math.min(31, ((colorRgb888 >> 8) & 0xFF) >> 3);
        int b5 = Math.min(31, (colorRgb888 & 0xFF) >> 3);
        int color = (r5 << 10) | (g5 << 5) | b5;
        if (dynamic) {
            color |= 0x8000;
        }
        return (halfI << 16) | (color & 0xFFFF);
    }

    /**
     * IEEE-754 binary16 conversion (round-to-nearest-even). Enough for light intensity.
     */
    static int floatToHalfBits(float value) {
        int f = Float.floatToRawIntBits(value);
        int sign = (f >>> 16) & 0x8000;
        int exp = ((f >>> 23) & 0xFF) - 127 + 15;
        int mant = f & 0x7FFFFF;

        if (exp <= 0) {
            if (exp < -10) {
                return sign; // underflow → 0
            }
            mant = (mant | 0x800000) >> (1 - exp);
            return sign | ((mant + 0x1000) >> 13);
        }
        if (exp >= 31) {
            if ((f & 0x7FFFFFFF) > 0x7F800000) {
                return sign | 0x7E00; // quiet NaN
            }
            return sign | 0x7C00; // Inf
        }
        return sign | (exp << 10) | ((mant + 0x1000) >> 13);
    }
}
