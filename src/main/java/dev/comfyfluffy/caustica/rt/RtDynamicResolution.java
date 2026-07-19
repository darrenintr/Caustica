package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;

/**
 * Dynamic Resolution Scaling (DRS) - automatically adjusts render resolution based on GPU performance
 * to maintain a stable target framerate.
 *
 * <p>When GPU is overloaded, DRS lowers the internal render resolution to maintain framerate.
 * When GPU has headroom, DRS increases resolution back up to maximize quality.
 *
 * <p>The upscaler (TAAU/FSR/XeSS/DLSS) then scales the dynamic resolution to the fixed display resolution,
 * making resolution changes nearly invisible to the user while maintaining smooth framerate.
 */
public class RtDynamicResolution {

    // Target framerate (from config)
    private float targetFPS = 60.0f;

    // Current resolution scale (0.5 = 50%, 1.0 = 100%)
    private float currentScale = 1.0f;

    // Previous scale for smooth interpolation
    private float previousScale = 1.0f;

    // Min/max scale limits
    private float minScale = 0.5f;  // 50% minimum (720p for 1440p display)
    private float maxScale = 1.0f;  // 100% maximum (native resolution)

    // Adjustment speeds
    private static final float SCALE_DOWN_RATE = 0.05f;  // Faster降低 (5% per frame)
    private static final float SCALE_UP_RATE = 0.02f;    // Slower提升 (2% per frame)
    private static final float SMOOTHING_FACTOR = 0.15f; // Smooth interpolation

    // FPS thresholds
    private static final float FPS_LOW_THRESHOLD = 5.0f;   // Below target - 5
    private static final float FPS_HIGH_THRESHOLD = 10.0f; // Above target + 10

    // Frame time history for stable measurement
    private static final int HISTORY_SIZE = 10;
    private final float[] frameTimeHistory = new float[HISTORY_SIZE];
    private int historyIndex = 0;
    private boolean historyFilled = false;

    // Display resolution (fixed)
    private int displayWidth;
    private int displayHeight;

    // Current render resolution (dynamic)
    private int renderWidth;
    private int renderHeight;

    // Statistics
    private int adjustmentCount = 0;
    private float minReachedScale = 1.0f;
    private float avgScale = 1.0f;

    public RtDynamicResolution() {
        loadConfig();
        CausticaMod.LOGGER.info("Dynamic Resolution Scaling initialized - target={}fps, range={}%-{}%",
                targetFPS, (int)(minScale * 100), (int)(maxScale * 100));
    }

    private void loadConfig() {
        targetFPS = CausticaConfig.Drs.TARGET_FPS.value();
        minScale = CausticaConfig.Drs.MIN_SCALE.value();
        maxScale = CausticaConfig.Drs.MAX_SCALE.value();

        // Clamp to sane values
        targetFPS = Math.max(30.0f, Math.min(targetFPS, 240.0f));
        minScale = Math.max(0.25f, Math.min(minScale, 1.0f));
        maxScale = Math.max(minScale, Math.min(maxScale, 1.0f));
    }

    /**
     * Update resolution scale based on frame time.
     * Call this every frame before rendering.
     *
     * @param frameTimeMs Last frame time in milliseconds
     * @return true if render resolution changed (need to recreate resources)
     */
    public boolean update(float frameTimeMs) {
        if (!CausticaConfig.Drs.ENABLED.value()) {
            // DRS disabled, always use max scale
            if (currentScale != maxScale) {
                currentScale = maxScale;
                previousScale = maxScale;
                return true;
            }
            return false;
        }

        // Add to history
        frameTimeHistory[historyIndex] = frameTimeMs;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        if (historyIndex == 0) {
            historyFilled = true;
        }

        // Calculate average frame time from history
        float avgFrameTime = calculateAverageFrameTime();
        float currentFPS = 1000.0f / avgFrameTime;

        // Store previous scale
        previousScale = currentScale;

        // Adjust scale based on FPS
        float targetScale = currentScale;

        if (currentFPS < targetFPS - FPS_LOW_THRESHOLD) {
            // FPS too low, reduce resolution
            targetScale = currentScale - SCALE_DOWN_RATE;
            adjustmentCount++;
        } else if (currentFPS > targetFPS + FPS_HIGH_THRESHOLD) {
            // FPS high, increase resolution
            targetScale = currentScale + SCALE_UP_RATE;
            adjustmentCount++;
        }

        // Clamp to limits
        targetScale = Math.max(minScale, Math.min(targetScale, maxScale));

        // Smooth interpolation to avoid sudden changes
        currentScale = lerp(currentScale, targetScale, SMOOTHING_FACTOR);

        // Track statistics
        minReachedScale = Math.min(minReachedScale, currentScale);
        avgScale = avgScale * 0.99f + currentScale * 0.01f;

        // Check if resolution actually changed (threshold 1%)
        boolean changed = Math.abs(currentScale - previousScale) > 0.01f;

        if (changed) {
            updateRenderResolution();
        }

        return changed;
    }

    /**
     * Set display resolution (called when window resizes).
     */
    public void setDisplayResolution(int width, int height) {
        this.displayWidth = width;
        this.displayHeight = height;
        updateRenderResolution();
    }

    /**
     * Calculate render resolution from current scale.
     */
    private void updateRenderResolution() {
        renderWidth = Math.max(1, (int)(displayWidth * currentScale));
        renderHeight = Math.max(1, (int)(displayHeight * currentScale));

        // Align to 8 pixels for better cache performance
        renderWidth = (renderWidth + 7) & ~7;
        renderHeight = (renderHeight + 7) & ~7;
    }

    /**
     * Get current render width.
     */
    public int getRenderWidth() {
        return renderWidth;
    }

    /**
     * Get current render height.
     */
    public int getRenderHeight() {
        return renderHeight;
    }

    /**
     * Get current resolution scale (0.5-1.0).
     */
    public float getCurrentScale() {
        return currentScale;
    }

    /**
     * Get display resolution (fixed).
     */
    public int getDisplayWidth() {
        return displayWidth;
    }

    public int getDisplayHeight() {
        return displayHeight;
    }

    /**
     * Calculate average frame time from history.
     */
    private float calculateAverageFrameTime() {
        if (!historyFilled && historyIndex == 0) {
            return 16.67f; // Default to 60fps
        }

        int count = historyFilled ? HISTORY_SIZE : historyIndex;
        float sum = 0.0f;
        for (int i = 0; i < count; i++) {
            sum += frameTimeHistory[i];
        }
        return sum / count;
    }

    /**
     * Linear interpolation.
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Get debug info string for overlay.
     */
    public String getDebugInfo() {
        float avgFrameTime = calculateAverageFrameTime();
        float currentFPS = 1000.0f / avgFrameTime;

        return String.format("DRS: %dx%d (%.0f%%) → %dx%d | %.1f fps | min=%.0f%% avg=%.0f%% | adj=%d",
                renderWidth, renderHeight, currentScale * 100,
                displayWidth, displayHeight,
                currentFPS,
                minReachedScale * 100, avgScale * 100,
                adjustmentCount);
    }

    /**
     * Reset statistics.
     */
    public void resetStats() {
        adjustmentCount = 0;
        minReachedScale = currentScale;
        avgScale = currentScale;
    }
}
