package dev.comfyfluffy.caustica.upscale;

import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.plate.RtPlateBridge;
import org.joml.Matrix4fc;

/**
 * A denoise + upscale pass that takes the path-traced color (and optional guide buffers) at render
 * resolution and writes the display-resolution image.
 *
 * <p>Current implementations: {@link TaaUpscaler} (TAAU, pure compute, always available), and
 * {@link NoopUpscaler} (1:1 blit fallback). The path tracer always writes all six guide buffers (the
 * cost is one per-primitive store on the GPU side; the rgen's first-hit attributes are computed
 * regardless), so the interface contract still takes them even though the only consumer today ignores
 * everything past color + depth.
 *
 * <p>All implementations are expected to be lazy: {@link #isReady()} returns false until
 * {@link #ensureFeature(long, int, int, int, int, int)} has run successfully for the current dimensions.
 */
public interface Upscaler {

    /** Stable provider identifier used by diagnostics and capability routing. */
    String id();

    /** Human-readable provider name used in logs and overlays. */
    default String displayName() {
        return id();
    }

    /** Whether this provider owns temporal reconstruction/history at the upscale stage. */
    default boolean performsTemporalReconstruction() {
        return false;
    }

    /** Whether this provider intentionally delegates output to the renderer's fallback blit. */
    default boolean isPassThrough() {
        return false;
    }

    /** Required Vulkan format for the render-resolution color plate. */
    default int inputColorFormat(int rawBeautyFormat) {
        return rawBeautyFormat;
    }

    /** Vulkan format produced by the provider before bridge output adaptation. */
    default int outputColorFormat(int rawBeautyFormat) {
        return inputColorFormat(rawBeautyFormat);
    }

    /**
     * Format the provider can deliver to the renderer's display-side plate. Most
     * providers write the raw beauty format; HDR-aware adapters may keep RGBA16F.
     */
    default int displayColorFormat(int rawBeautyFormat, boolean hdrEnabled) {
        return rawBeautyFormat;
    }

    /** Whether the provider consumes the bridge-generated reactive mask. */
    default boolean needsReactiveMask() {
        return false;
    }

    /** Whether the provider needs the bridge's output blackout fail-open guard. */
    default boolean needsBlackoutGuard() {
        return false;
    }

    /**
     * Whether {@link #evaluate} expects the raw path-tracer jitter sign instead of the
     * camera-equivalent sign used by external temporal reconstruction APIs.
     */
    default boolean expectsRawRenderJitter() {
        return false;
    }

    /** Whether the provider already applies configured sharpening internally. */
    default boolean includesSharpening() {
        return false;
    }

    /** Consume a provider-owned fail-open latch after evaluation. */
    default boolean consumeFailOpen() {
        return false;
    }

    /**
     * Whether the SDK initialised AND a feature for the current size/quality has been created. False
     * means {@link #evaluate} is a no-op (and the caller should fall through to a 1:1 blit).
     */
    boolean isReady();

    /**
     * Ask the SDK what render resolution the current quality mode expects for the given display size, or
     * {@code null} if it can't (off, or no feature yet). Implementations that ignore quality should return
     * {@code new int[] { displayWidth, displayHeight }}.
     */
    int[] queryOptimalRenderSize(int displayWidth, int displayHeight);

    /**
     * Prepare a feature (or refresh an existing one) for the given dimensions / quality. Idempotent; safe
     * to call every frame. Returns true on success. On failure the implementation should latch
     * {@code isReady()} to false so subsequent calls become no-ops.
     *
     * @param cmd                recording command buffer (the SDK records its setup into it)
     * @param quality            quality mode on the shared 0=NATIVE..4=ULTRA_PERF scale; providers may
     *                           map the value to their own internal presets
     * @param featureFlags       bit flags (interpretation implementation-defined)
     */
    boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                          int quality, int featureFlags);

    /**
     * Run the denoise + upscale. Returns true on success, false on any failure (caller falls back to a
     * linear blit). The {@code worldToView} / {@code viewToClip} matrices are used to reproject the
     * previous frame's guide data for temporal accumulation; implementations that don't need them
     * (none today) may ignore.
     *
     * <p>Inputs that an implementation doesn't need may be null (e.g. FSR / XeSS don't need normals).
     * The {@code color} / {@codedepth} / {@code motion} / {@code out} images are always non-null and at
     * the same render / display sizes the feature was created for.
     */
    boolean evaluate(long cmd,
                     RtImage color, RtImage depth, RtImage motion,
                     RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                     RtImage specularMotion, RtImage specularHitDistance,
                     RtImage out,
                     int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                     float jitterX, float jitterY,
                     Matrix4fc worldToView, Matrix4fc viewToClip);

    /**
     * Release the feature + (if owned) the SDK handle. Idempotent; safe to call even if
     * {@link #ensureFeature} never succeeded. After this, {@link #isReady()} is false until
     * {@code ensureFeature} is called again.
     */
    void destroy();

    /**
     * Ask the upscaler to drop its internal temporal history on the next evaluate
     * (or as soon as the SDK supports it). Called by
     * {@code RtComposite.invalidateHistory()} on hard cuts (teleport,
     * dimension change, resource reload) so the provider's internal accumulator
     * does not smear the previous scene's colour into the new one.
     *
     * <p>Default no-op is safe: an upscaler whose SDK doesn't expose a reset path
     * simply relies on its own history-rejection logic + the consumer's depth /
     * MV rejection. Implementations with an explicit reset path must override.
     */
    default void requestResetHistory() {
    }

    /**
     * v0.6.8+: bind the per-tile jitter guide (R8G8_UNORM, render res) written by
     * world.rgen. The upscaler reads this and adds the per-tile offset to its
     * reproject UV so the temporal accumulation lines up with the path tracer's
     * actual sub-pixel sampling pattern.
     *
     * <p>Default no-op is safe: upscalers that don't use the guide (FSR, XeSS —
     * they handle jitter via their SDK's internal math, not the user-
     * supplied guide) just ignore it. Only TAAU / FFX's user-space reproject
     * need this. The caller (RtComposite) passes {@code null} if the rgen is from
     * an older shader set that doesn't write the guide.
     */
    default void setJitterGuide(RtImage jitterGuide) {
    }

    /**
     * Bind the denoise-pipeline outputs an upscaler needs to derive a
     * self-supplied reactive mask. The motion / deviceZ / normal-roughness are
     * already part of {@link #evaluate} so the caller only needs to provide
     * the two NRD-owned guides: linear viewZ and the prep-pass disocclusion
     * mix (rgba8 .b = disocclusion signal).
     *
     * <p>Upscalers that already own their own reactive-mask path (for example XeSS
     * auto-reactive) leave this as a no-op.
     * Classic FSR2 uses these to derive the reactive signal ported from
     * iterationRP's DepthClip_CS.glsl motion+depth divergence (see
     * {@code shaders/display/denoise_ffx/fsr2_reactive_mask.comp}) so the SDK
     * gets a real reactive input without the host having to author one.
     *
     * <p>Caller (RtComposite) sets this once per frame after the denoise backend
     * has written its outputs. Either argument may be {@code null} to disable
     * the reactive mask for this frame (e.g. denoise-off path) — the upscaler
     * should fall back to its no-reactive behaviour.
     */
    default void setReactiveMaskGuides(RtImage viewZ, RtImage disocclusionMix) {
    }

    /**
     * Inject the composite-owned RT plate bridge. Implementations must treat it as
     * non-owning and must not destroy it. Format-adapting upscalers (currently
     * classic FSR2) use it for denoise-to-upscale staging.
     */
    default void setPlateBridge(RtPlateBridge bridge) {
    }

    /**
     * Describe the actual Vulkan format of {@code color} passed to the next
     * {@link #evaluate} call. This is separate from {@link RtImage} because the
     * image wrapper intentionally does not retain its VkFormat.
     */
    default void setInputColorFormat(int format) {
    }
}
