package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.CausticaJitter;
import dev.comfyfluffy.caustica.mixin.CommandEncoderAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import dev.comfyfluffy.caustica.denoise.CausticaDenoiseBackend;
import dev.comfyfluffy.caustica.denoise.DenoiseBackendSelector;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtEntityMaterials;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtTemporalAccumulation;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.upscale.Upscaler;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dcaustica.rt=true}.
 *
 * <p>The path tracer and its guide buffers run at the configured render scale of display res with a per-frame
 * sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the display-res image. With RR
 * disabled the trace runs at 1:1 and a linear blit stands in for the upscale (a raw, noisy reference).
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();

    public static boolean enabled() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    // invViewProj(64) + camOffset(@64) + sectionTableAddr(@80) + debugView(@88) + frameIndex(@92)
    // + prevViewProj(@96) + camDelta(@160) + spp(@172) + jitter(@176) + entityTableAddr(@184)
    // + flags(@192): bit 0 = camera submerged, bit 1 = PBR BRDF enabled, bit 4 = water waves
    // + maxBounces(@196) + maxRayDistance(@200)
    // + dynamic sky (16-byte aligned vec4s): sunDir+dayFactor(@208) + lightDir(@224) + lightRadiance(@240)
    // + sky rewrite: moonDir+moonPhase(@256) + celestialAxis+starAngle(@272) + sunUv(@288) + moonUv(@304)
    // + W1/W2 water: waterParams(@320) xyz=camera-biome tint, w=wave time; waterAnchor(@336) xy=wave anchor
    // + curViewProj(@352, forward camera-relative view-projection)
    // + block-breaking overlay (Tier 2): breakCount(@416) + pad(@420-428) + breaking[MAX_BREAKING](@432,
    // 16B each: ivec3 blockPos rebased relative to terrain origin, int bindless entityTex slot for this
    // block's destroy-stage texture; unused entries are simply not counted, not zeroed)
    private static final int MAX_BREAKING = 8;
    private static final int BREAKING_OFFSET = 432;
    // ReSTIR DI: 16 bytes for blockLightCount(4) + restirCandidates(4) + maxMTemporal(4) + maxMSpatial(4)
    // Light field: 16 bytes for origin xyz + size (ivec4) @576
    private static final int RESTIR_PUSH_BYTES = 16;
    private static final int LIGHTFIELD_PUSH_BYTES = 16;
    // ReSTIR GI: 32 bytes for giEnabled(4) + giCandidates(4) + giMaxMTemporal(4) + giMaxMSpatial(4)
    // + giAmbientParams vec4 (16) @592
    private static final int GI_PUSH_BYTES = 32;
    // Hybrid: 16 bytes for hybridEnabled(4) + hybridRoughThreshold(4) + hybridLightfieldThreshold(4) + pad(4) @624
    private static final int HYBRID_PUSH_BYTES = 16;
    private static final int WORLD_PUSH_SIZE = BREAKING_OFFSET + MAX_BREAKING * 16
            + RESTIR_PUSH_BYTES + LIGHTFIELD_PUSH_BYTES + GI_PUSH_BYTES + HYBRID_PUSH_BYTES;
    // Real inline push constants (fast constant-bank reads), separate from the WORLD_PUSH_SIZE BDA ring
    // above. tableAddr/entityTableAddr/frameIndex are duplicated here so world.rchit/world.rahit's hottest
    // per-hit lookups (Section/EntityGeom fetch) skip the extra global-memory load that dereferencing
    // WorldPushRef(pcAddr.worldPushAddr) costs; everything else (matrices, sky, water, breaking) stays in
    // the BDA struct since it's cold or only read once per ray-gen invocation.
    // layout: worldPushAddr(@0, 8B) + tableAddr(@8, 8B) + entityTableAddr(@16, 8B) + frameIndex(@24, 4B)
    private static final int WORLD_PUSH_CONST_SIZE = 32;
    // RR guides (6) + split lighting + ReSTIR (3) + light field SSBO (1) + ReSTIR GI (4)
    // + tile-jitter guide (1) for NRD pre-warp (v0.6.8+).
    // Slots 0..8 images, 9 SSBO lights, 10-11 reservoir images, 12 SSBO light field,
    // 13-16 GI reservoir images (currA, currB, prevA, prevB), 17-22 misc guides,
    // 23 tile-jitter guide (R8G8).
    private static final int GUIDE_COUNT = 24; // bindings 3..26 (guides + standard RT outputs)

    // ReSTIR Direct Illumination feature flag
    // ReSTIR DI currently loses the AMD device after the live light set grows while NRD is
    // active (VK_ERROR_DEVICE_LOST is reported later by swapchain acquire). Keep the stable
    // direct-light path as the baseline until the reservoir/resource lifetime path is rebuilt.
    private static final boolean ENABLE_RESTIR_DI = false;
    private static final boolean ENABLE_RESTIR_GI = false;
    private static final boolean ENABLE_LIGHTFIELD_GI = false;
    // Stable RT baseline: dynamic entity BLAS capture/refit is temporarily isolated from the
    // terrain-only TLAS while AMD device-loss causes are being reduced to one submission path.
    private static final boolean ENABLE_DYNAMIC_ENTITY_RT = false;
    // Stable profiling baseline: keep VRS and pseudo-async compute out of the frame until their
    // individual cost and synchronization behavior are measured in isolation.
    private static final boolean ENABLE_VRS = false;
    private static final boolean ENABLE_ASYNC_COMPUTE = false;
    private static final boolean ENABLE_DRS = false;
    private static final boolean ENABLE_ADAPTIVE_SPP = false;
    // Frames a retired per-frame TLAS must outlive before it's freed (> frames-in-flight); matches
    // RtTerrain's deferred-free horizon. The frame TLAS is built + traced this frame, then freed once
    // the composite frame counter has advanced this far past it (so no in-flight frame still reads it).
    private static final int KEEP_FRAMES = 4;

    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return 1;
    }

    private static int maxBounces() {
        return 2;
    }

    private static float maxRayDistance() {
        return Math.min(CausticaConfig.Rt.Composite.MAX_RAY_DISTANCE.value(), 128.0f);
    }

    private static boolean waterWaves() {
        return CausticaConfig.Rt.Composite.WATER_WAVES.value();
    }

    /**
     * DLSS-RR owns the full stack (raw SPP → RR). Must not run FFX/NRD/beauty-TAA before it.
     */
    private static boolean isDlssRr(Upscaler activeUpscaler) {
        return activeUpscaler != null && activeUpscaler.mode() == UpscalerSelector.Mode.DLSS_RR;
    }

    /**
     * TAAU / FSR2/3/4 / XeSS: temporal upscale <b>after</b> denoise on a clean plate
     * (Radiance-style NRD → FSR). Earlier we skipped these because raw PT + FSR swam;
     * with NRD/FFX cleaning first, they are the real super-res path on AMD/Intel.
     */
    private static boolean isSpatialUpscaler(Upscaler activeUpscaler) {
        if (activeUpscaler == null) {
            return false;
        }
        UpscalerSelector.Mode m = activeUpscaler.mode();
        return m == UpscalerSelector.Mode.TAAU
                || m == UpscalerSelector.Mode.FSR_3
                || m == UpscalerSelector.Mode.FSR_4
                || m == UpscalerSelector.Mode.XESS;
    }

    private static boolean temporalAccumEnabled(Upscaler activeUpscaler) {
        if (debugView() != 0) {
            return false;
        }
        // AMD_FIDELITYFX preset was removed in commit 1 (2026-07-20): AMD AUTO now routes
        // to NRD, so there is no FFX-history-double-up case to suppress here. NRD already
        // owns temporal; we let beauty TAA on top if the user enabled it.
        if (!CausticaConfig.Rt.Composite.TEMPORAL_ACCUM.value()) {
            return false;
        }
        // DLSS-RR and FSR/XeSS own temporal — stacking beauty TAA milks the image.
        return !isDlssRr(activeUpscaler) && !isSpatialUpscaler(activeUpscaler);
    }

    private static int activeUpscalerQuality(Upscaler activeUpscaler) {
        if (isDlssRr(activeUpscaler)) {
            return RtDlssRr.quality();
        }
        if (isSpatialUpscaler(activeUpscaler)) {
            return CausticaConfig.Rt.Upscaler.QUALITY.value();
        }
        return Integer.MIN_VALUE;
    }

    private static float temporalAlpha() {
        return CausticaConfig.Rt.Composite.TEMPORAL_ALPHA.value();
    }

    private static float temporalDisocclusion() {
        return CausticaConfig.Rt.Composite.TEMPORAL_DISOCCLUSION.value();
    }

    // Finite sun/moon angular sizes let NEE shadow rays sample the light disk (soft, contact-hardening
    // penumbrae). Radii in degrees; the real sun/moon are ~0.27°, but a touch larger reads pleasantly.
    private static final int WATER_ANCHOR_MASK = 4095;
    private static final Identifier SUN_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_IDS = createMoonIds();
    // Celestial rotation axis (the pole the sun/moon arc about): perpendicular to the east-west arc,
    // tilted by SUN_NOON_SOUTH_TILT. Pushed so the sky shader can build the sun/moon square's tangent
    // frame (right = travel direction) and wheel the starfield. = normalize(noonDir x sunriseDir).
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    private static float sunNoonTilt() {
        return CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT.value();
    }

    private static float sunNoonY() {
        return Mth.cos(sunNoonTilt());
    }

    private static float sunNoonZ() {
        return Mth.sin(sunNoonTilt());
    }

    private static float celestialAxisY() {
        return -sunNoonZ();
    }

    private static float celestialAxisZ() {
        return sunNoonY();
    }

    // Monotonic per-composite frame counter, used by RtTerrain to time frames-in-flight-safe frees.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    /** Forward vanilla block edits to the bounded light-cache updater. */
    public void markBlockLightsDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (unifiedLightManager != null) {
            unifiedLightManager.markBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private RtPipeline worldPipeline;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundAtlasHandle;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // World push data (256 B) lives in a host-visible BDA ring; only the 8-byte slot address is pushed
    // inline (256-byte NVIDIA push constant ceiling is otherwise exhausted by the world push struct).
    // One slot per in-flight frame, cycled per frame so an in-flight slot is never overwritten.
    private static final int PUSH_RING = 6;
    private RtBuffer[] pushRing;
    private int pushSlot;
    private RtDisplayPipeline displayPipeline;
    /** FidelityFX CAS-style sharpen after upscale (display-res). */
    private final dev.comfyfluffy.caustica.display.CasSharpenPass casSharpen =
            new dev.comfyfluffy.caustica.display.CasSharpenPass();
    private RtImage output;
    // Denoised path-traced color (input to the upscaler / display pipeline). The path tracer writes the
    // raw noisy color into {@code output}; the active denoise backend reads {@code output} + the guide
    // buffers and writes the denoised color into {@code denoisedColor}. The upscaler reads from
    // {@code denoisedColor} when denoise is enabled, otherwise from {@code output} (the noisy color).
    // Sized at render resolution (same as {@code output}).
    private RtImage denoisedColor;

    // Variable Rate Shading for adaptive sampling
    private RtVariableRateShading vrs;
    // Firefly-killed radiance plate. Written by the FireflyKill pass (3x3 median)
    // and read by both NRD (gBeautyRaw) and TAAU (inColorLow). Replaces the raw
    // path-tracer output as the source of truth for both temporal paths so
    // SPP=1 fireflies never reach the temporal history or the final composite.
    private RtImage fireflyKilled;
    private final FireflyKill fireflyKill = new FireflyKill();
    // Temporal-accumulation (TAA-style) output: the path-traced color reprojection-blended with the
    // previous frame's accumulated history, same size + format as {@code output}. Fed to the denoise
    // pass / upscaler in place of the raw noisy color when {@link #temporalAccumEnabled()} is on, so a
    // static camera converges to a near-noiseless image over a handful of frames. The pipeline +
    // history ring live in {@link RtTemporalAccumulation}; this image is only the visible output.
    private RtImage accumulatedColor;
    private RtTemporalAccumulation temporalAccum;
    private RtImage displayImage;
    // Parallel PQ-encoded ([0,1], ST.2084) HDR display image. Written alongside displayImage when HDR is
    // enabled. When the PQ swapchain is active, the combined UI overlay is composited over this image, then
    // this image is blitted straight to the swapchain.
    private RtImage hdrDisplayImage;
    // Set true after this frame's display dispatch wrote hdrDisplayImage (HDR enabled + RT ran); gates the
    // HDR present blit so a frame where RT did not run falls back to the vanilla SDR present.
    private boolean hdrWrittenThisFrame;
    // Per-frame observability for the in-game debug overlay. Written by recordFrame; read by
    // CausticaDebugOverlay and RtVideoOptions' upscaler setter (the latter sees stale values between
    // frames, which is fine because the overlay is a snapshot, not a frame-perfect read).
    private volatile int lastRrRc;
    private volatile boolean lastRrOk;
    private volatile boolean lastDenoiseOn;
    /** Short path label for the debug overlay (e.g. {@code nrd}, {@code ffx→nrd + beauty TAA}). */
    private volatile String lastDenoisePath = "off";
    private volatile boolean lastUpscalerPath;
    private volatile boolean lastNrdPrepareOk;
    private volatile boolean lastNrdDispatchOk;
    private volatile boolean lastNrdComposeOk;
    private volatile float lastJitterPixelsX;
    private volatile float lastJitterPixelsY;
    // DLSS-FG "hudless" resource: a copy of the main render target before the combined UI overlay
    // composites back on top. Lazily allocated (only meaningful once FG + the UI overlay redirect are both
    // active), resized on demand.
    private RtImage fgHudlessImage;
    // Same idea as fgHudlessImage but for the HDR present path: a copy of hdrDisplayImage taken in
    // presentHdr right before its own combined-UI composite dispatch overwrites it in place (see
    // captureFgHdrHudless). Already PQ-encoded (same as hdrDisplayImage), so this is a plain image copy, not
    // a format conversion — DLSS-FG requires a display-ready EOTF-encoded [0,1] signal (its programming
    // guide explicitly disallows scRGB), and PQ is exactly that.
    private RtImage fgHdrHudlessImage;
    // Step C.2: composites the combined UI overlay over hdrDisplayImage at paper white, just before present.
    private RtHdrCompositePipeline hdrCompositePipeline;
    private long hdrUiSampler;
    // Menu/non-RT present: converts the SDR main target (sRGB) to PQ-encoded at paper white so menus,
    // the title panorama and the loading screen present correctly to the PQ swapchain instead of being
    // raw-copied (misdisplayed). Lazily created; the image is sized to the swapchain.
    private RtSdrPresentPipeline sdrPresentPipeline;
    private RtImage sdrPresentImage;
    // DLSS Frame Generation: per-generated-frame interpolated output images (backbuffer size/format), and
    // the jitter-free reprojection matrices derived from the MV view-projections each frame. In HDR mode
    // these hold DLSSG's raw PQ-encoded output, which is blitted straight to the (PQ) swapchain — no decode
    // needed since the swapchain itself is PQ-native.
    private RtImage[] fgInterp = new RtImage[0];
    private int fgInterpW = -1;
    private int fgInterpH = -1;
    private int fgInterpFormat = Integer.MIN_VALUE;
    private boolean fgReset = true;
    private final Matrix4f fgClipToPrev = new Matrix4f();
    private final Matrix4f fgPrevToClip = new Matrix4f();
    private final Matrix4f fgMatTmp = new Matrix4f();
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, depth, motion,
    // specular albedo, and reflection motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    private RtImage gSpecMotion;
    /** NEE sun visibility [0,1] for FFX Shadow Denoiser (render res). */
    private RtImage gShadowHit;
    /** Diffuse + emissive + non-specular path radiance (unshadowed direct where applicable). */
    private RtImage gDiffuse;
    /** Unshadowed sun/moon NEE, composed exactly once with SIGMA's visibility output. */
    private RtImage gUnshadowedDirect;
    /** Specular / reflection-path radiance only. */
    private RtImage gReflection;
    private RtImage gClearEmission;
    private RtImage gTransmission;
    private RtImage gViewZ;
    private RtImage gConfidenceDisocclusion;
    private RtImage gMaterialFlags;
    /**
     * Per-tile sub-pixel jitter offset written by world.rgen at render res (R8G8_UNORM, encoded
     * as {@code (offset + 0.5)} so the full [-0.5, +0.5] render-pixel range maps to [0, 1]). The
     * NRD pre-warp pass (nrd_prewarp.comp) reads this and re-samples the current frame's NRD
     * input by {@code -tileJitter/size} so NRD's internal reproject math (which is told the
     * unjittered pixel position via {@code cameraJitter}) lines up with the per-tile-quantized
     * primary-ray sampling pattern used by the path tracer. See TILE_JITTER_GUIDE_DESIGN.md.
     */
    private RtImage gJitterGuide;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private final RtExposure exposure = new RtExposure();

    // ReSTIR Direct Illumination for block lights (torches, glowstone, lava) and dynamic lights (entities).
    private dev.comfyfluffy.caustica.rt.light.UnifiedLightManager unifiedLightManager;
    private dev.comfyfluffy.caustica.rt.light.BlockLightBuffer blockLightBuffer;
    private dev.comfyfluffy.caustica.rt.light.ReservoirImages reservoirImages;
    private dev.comfyfluffy.caustica.rt.light.ReservoirImagesGI giReservoirImages;
    private dev.comfyfluffy.caustica.rt.light.LightFieldVolume lightFieldVolume;

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;

    // Dynamic Resolution Scaling
    private RtDynamicResolution drs;
    private long lastFrameStartNanos = 0L;

    // Async Compute - overlap denoise with frame setup
    private RtAsyncCompute asyncCompute;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;

    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private final BlockPos.MutableBlockPos cameraBlockPos = new BlockPos.MutableBlockPos();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private long atlasSampler;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private final Matrix4f frameProjectionPrev = new Matrix4f();
    private final Matrix4f frameViewRotationPrev = new Matrix4f();
    private boolean frameCameraHasPrev;
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;
    // Last-seen dimension ResourceKey. Used to detect Overworld <-> Nether <-> End transitions
    // (see composite() — the dimension change drops the denoise + TAA history so the new
    // dimension's color palette doesn't smear from the old one's accumulated radiance).
    private ResourceKey<Level> lastDimension;
    private long celestialUvAtlasHandle;
    private int celestialUvMoonPhase = -1;
    private float sunU0;
    private float sunV0;
    private float sunU1 = 1f;
    private float sunV1 = 1f;
    private float moonU0;
    private float moonV0;
    private float moonU1 = 1f;
    private float moonV1 = 1f;

    // Per-frame TLAS resources, rebuilt in place from a small ring of persistent slots (see
    // RtAccel.TlasRing — replaces the old create-and-defer-destroy-per-frame churn whose VMA slow path
    // showed up as rare multi-ms prepareTlas spikes).
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();

    // This frame's TLAS handle, published after prepareTlas so the world-overlay pass (block outline's
    // rayQueryEXT occlusion test) can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (RtWorldOverlay's transient buffer runs later, same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    // Set true after the first composite() call has run UpscalerSelector.resolve(). Subsequent frames
    // skip the resolve (the selector is idempotent; resolve() can be called again to re-pick if the
    // user hot-reloads the config and a new device is available).
    private boolean caustica$upscalerResolvedOnce;

    private RtComposite() {
    }

    /** This frame's TLAS handle (0 if none built yet), for {@code dev.comfyfluffy.caustica.rt.overlay} occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    private static Identifier[] createMoonIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /**
     * Clear the one-shot "upscaler has been resolved for this session" latch so the next {@code composite()}
     * call re-runs {@code UpscalerSelector.resolve()} + {@code FrameGenSelector.resolve()}. Called from the
     * Video Settings upscaler-mode setter (and from {@code -Dcaustica.rt.upscaler} config reloads) so a
     * runtime change takes effect on the next frame instead of waiting for an MC restart.
     */
    public void invalidateUpscalerSelection() {
        this.caustica$upscalerResolvedOnce = false;
    }

    /**
     * Clears the resolved denoise-backend latch so the next {@code composite()} call
     * re-resolves through {@link DenoiseBackendSelector#current(long)}. Called from
     * the Video Settings {@code denoiseMode} setter.
     */
    public void invalidateDenoiseSelection() {
        DenoiseBackendSelector.invalidate();
    }

    /**
     * Clear the active denoise backend's temporal history (and the standalone TAA
     * history, if it ran) so the next frame starts with a clean slate. Called from
     * {@link #updateMotion()} on a detected hard cut (camera teleport, dimension change)
     * and from the Video Settings denoise-mode setter (so a backend swap doesn't
     * smear the old backend's accumulated color into the new backend's first frames).
     *
     * <p>Also asks the active upscaler to drop its own internal temporal history
     * (DLSS-RR's NGX accumulator, FSR / XeSS when the SDK exposes a reset path) so
     * the new scene's colour palette does not smear from the previous view's
     * accumulated color. Safe to call on a no-history backend (Bilateral, Noop) and
     * on an upscaler with no SDK reset path (default no-op on the interface).
     */
    public void invalidateHistory() {
        try {
            var device = RenderSystem.getDevice();
            if (device != null
                    && ((dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor) device).caustica$getBackend() instanceof VulkanDevice vulkanDevice) {
                var backend = DenoiseBackendSelector.current(vulkanDevice);
                if (backend != null) {
                    backend.resetHistory();
                }
            }
        } catch (Throwable t) {
            // Selector may not be initialised yet (cold start, before first composite) — silent.
        }
        if (temporalAccum != null) {
            temporalAccum.resetHistory();
        }
        // Upscaler-side reset: DLSS-RR has a per-evaluate reset flag (RtDlssRr.resetHistory);
        // FSR / XeSS use the default no-op unless their SDK exposes one. Both cases are safe.
        try {
            UpscalerSelector.current().requestResetHistory();
        } catch (Throwable t) {
            // Selector may not be initialised yet — silent.
        }
    }

    /** True iff the last frame's upscaler dispatch produced a valid display-res image. */
    public boolean getLastRrOk() {
        return lastRrOk;
    }

    /** Return code from the last frame's upscaler evaluate (0 = success, otherwise NVSDK-style rc). */
    public int getLastRrRc() {
        return lastRrRc;
    }

    /** Whether the last frame's denoise pass actually ran (mirrors {@link #caustica$denoiseEnabled()}). */
    public boolean getLastDenoiseOn() {
        return lastDenoiseOn;
    }

    /** What denoise path last ran (for the F3-style debug overlay). */
    public String getLastDenoisePath() {
        return lastDenoisePath != null ? lastDenoisePath : "off";
    }

    /** Whether the last frame intended to use an upscaler at all (active mode != OFF and not in debug view). */
    public boolean getLastUpscalerPath() {
        return lastUpscalerPath;
    }

    public boolean getLastNrdPrepareOk() { return lastNrdPrepareOk; }

    public boolean getLastNrdDispatchOk() { return lastNrdDispatchOk; }

    public boolean getLastNrdComposeOk() { return lastNrdComposeOk; }

    public float getLastJitterPixelsX() { return lastJitterPixelsX; }

    public float getLastJitterPixelsY() { return lastJitterPixelsY; }

    public int getRenderWidth() { return renderW; }

    public int getRenderHeight() { return renderH; }

    public int getDisplayWidth() { return displayW; }

    public int getDisplayHeight() { return displayH; }

    /** Snapshot of the last recorded frame as a short one-line string for the debug overlay. */
    public String debugSummary() {
        String base = String.format("%s denoise=%s %dx%d→%dx%d  fc=%d  rc=%s",
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Upscaler.MODE.value().key(),
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.MODE.get(),
                renderW, renderH, displayW, displayH,
                frameCounter,
                lastRrOk ? "ok" : String.format("0x%X", lastRrRc));

        // Append DRS info if enabled
        if (drs != null) {
            base += String.format("  drs=%.0f%%", drs.getCurrentScale() * 100);
        }

        // Note: Async compute temporarily disabled - proper dual-queue implementation needed

        return base;
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on vanilla until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            CausticaMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        if (frameCameraHasPrev) {
            frameProjectionPrev.set(frameProjection);
            frameViewRotationPrev.set(frameViewRotation);
        } else {
            frameProjectionPrev.set(projection);
            frameViewRotationPrev.set(viewRotation);
            frameCameraHasPrev = true;
        }
        frameProjection.set(projection);
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — overlay raster passes ({@code dev.comfyfluffy.caustica.rt.overlay}) reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Eye position used for this frame's RT plate (partial-tick camera from {@link #captureFrame}).
     * World-overlay geometry (block outline) must subtract this — not entity-glow rebase offsets —
     * so the wireframe lines up with the path-traced hit under the crosshair.
     */
    public double plateCamX() {
        return camX;
    }

    public double plateCamY() {
        return camY;
    }

    public double plateCamZ() {
        return camZ;
    }

    /** True after {@link #captureFrame} has run at least once this session for a world frame. */
    public boolean hasPlateCamera() {
        return frameCaptured;
    }

    /**
     * Reset per-frame present state at the very start of {@link net.minecraft.client.renderer.GameRenderer}
     * render (before any RT work). Critical for menu/no-world frames: {@link #composite()} is only called
     * while a level is rendering ({@code WorldRenderScaler} opens its window in {@code renderLevel}), so on
     * menu frames {@code composite} never runs and {@code hdrWrittenThisFrame} would otherwise keep its stale
     * {@code true} from the last world frame — presenting a black/stale HDR image behind the menu. Clearing it
     * here every frame makes {@link #isHdrPresentActive()} false on menu frames so the SDR convert-present path
     * runs instead.
     */
    public void beginFrame() {
        RtFrameStats.FRAME.beginIfInactive();
        hdrWrittenThisFrame = false;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    private static int __debugCompositeCounter;
    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // advances once per frame; RtTerrain retires resources relative to it
        hdrWrittenThisFrame = false; // set true again below once this frame's HDR display image is written

        // Dynamic Resolution Scaling - update resolution based on last frame time
        long frameStartNanos = System.nanoTime();
        if (drs != null && lastFrameStartNanos > 0L) {
            float lastFrameTimeMs = (frameStartNanos - lastFrameStartNanos) / 1_000_000.0f;
            boolean resolutionChanged = drs.update(lastFrameTimeMs);
            if (resolutionChanged) {
                // Resolution changed, renderW/renderH will be updated below in resize check
                CausticaMod.LOGGER.debug("DRS adjusted resolution: {}x{} ({}%)",
                        drs.getRenderWidth(), drs.getRenderHeight(), (int)(drs.getCurrentScale() * 100));
            }
        }
        lastFrameStartNanos = frameStartNanos;

        if (failed) {
            System.err.println("[Caustica RT] composite entry: failed=true (clearing)");
            CausticaMod.LOGGER.warn("RT failure latch is set; clearing and retrying this frame (F3+A also works)");
            failed = false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            System.err.println("[Caustica RT] composite early-return: ctx is null");
            return false;
        }

        // Async Compute - lazy init (needs ctx)
        if (ENABLE_ASYNC_COMPUTE && asyncCompute == null && RtDeviceBringup.asyncComputeAvailable()) {
            try {
                asyncCompute = RtAsyncCompute.tryCreate(ctx);
                if (asyncCompute != null) {
                    CausticaMod.LOGGER.info("Async Compute enabled - denoise can overlap with frame setup");
                }
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("Failed to initialize Async Compute", t);
            }
        }
        int dbgCount = ++__debugCompositeCounter;
        if (dbgCount <= 5 || dbgCount % 60 == 0) {
            System.err.println("[Caustica RT] composite heartbeat #" + dbgCount + " width=" + width + " height=" + height + " renderW=" + renderW + " renderH=" + renderH + " ctx=" + (ctx != null) + " level=" + (Minecraft.getInstance().level != null));
        }
        // Resolve the active upscaler on the first composite call (no-op on subsequent frames unless
        // the user hot-reloads the config). The selector probes the SDK lazily inside resolve(); a
        // missing SDK is fine — the upscaler falls through to NoopUpscaler and RT just blits 1:1.
        // Both calls are wrapped so a transient issue in the SDK-probe path (e.g. a third-party .so
        // throwing on Linux where the natives aren't bundled) cannot poison the trace path: a probe
        // failure logs at WARN and we fall through to Mode.OFF / no frame-gen for this frame, while
        // the trace + denoise pass keep running with whatever they had cached.
        if (!caustica$upscalerResolvedOnce) {
            try {
                UpscalerSelector.resolve();
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("UpscalerSelector.resolve() threw (likely missing native .so: {}); pinning to Mode.OFF for this session",
                        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            try {
                dev.comfyfluffy.caustica.framegen.FrameGenSelector.resolve(
                        dev.comfyfluffy.caustica.vendor.GpuVendor.detect(),
                        UpscalerSelector.resolvedMode());
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("FrameGenSelector.resolve() threw; falling back to frame-gen off", t);
            }
            caustica$upscalerResolvedOnce = true;
        }
        // Budgeted terrain streaming (dispatch/drain/build kick) runs here, once per render frame — before
        // the ready gate below, because it is what MAKES terrain ready during the initial fill.
        try {
            RtTerrain.frame(ctx);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT terrain streaming failed; reverting to vanilla path", t);
            return false;
        }
        if (RtTerrain.currentOrNull() == null || !frameCaptured || Minecraft.getInstance().level == null) {
            // No world this frame (incl. after quitting to the title — terrain residency + frameCaptured can
            // linger until an explicit invalidate, which would otherwise present a stale/empty HDR image as a
            // black menu background). Skip RT so the present path falls back to vanilla SDR / the PQ SDR
            // convert path, which shows the menu + panorama correctly.
            if (dbgCount <= 5 || dbgCount % 60 == 0) {
                System.err.println("[Caustica RT] composite early-return: terrain=" + (RtTerrain.currentOrNull() != null) + " frameCaptured=" + frameCaptured + " level=" + (Minecraft.getInstance().level != null));
            }
            return false;
        }
        // Dimension change (Overworld <-> Nether <-> End) is a hard cut: the previous
        // frame's accumulated radiance is from a completely different sky/lighting
        // regime, and the FFX reproject would happily sample that color into the new
        // frame as "history" — a Nether-frame smear after returning to the Overworld
        // (or vice versa). The teleport threshold in updateMotion only fires on
        // camera translation, which is near-zero for a /execute in ... 0 0 0 portal
        // crossing. Drop history explicitly on dimension key change.
        ResourceKey<Level> dim = Minecraft.getInstance().level.dimension();
        if (lastDimension != null && !lastDimension.equals(dim)) {
            invalidateHistory();
            mvHasPrev = false;
        }
        lastDimension = dim;
        try {
            if (displayPipeline == null) {
                displayPipeline = RtDisplayPipeline.create(ctx);
            }
            // A resource reload re-stitches the block atlas. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAtlasView();
                if (atlas == 0L || atlas == boundAtlasHandle) {
                    return false;
                }
            }
            ensureOutput(ctx, width, height);
            // Cheap idempotent check every frame (not just on resize): if the exposure mode is switched
            // manual -> auto at runtime (video settings), the auto-mode histogram/state/pipeline must be
            // allocated before recordFrame's exposure.record() below needs them, or it throws.
            exposure.ensureResources(ctx);
            refreshPipelineShapeIfNeeded(ctx);
            RtPipeline active = ensureWorld(ctx);
            refreshMaterialBindingsIfNeeded(ctx);
            updateMotion();
            recordFrame(ctx, active, nativeColor, dbgCount);
            if (!loggedActive) {
                loggedActive = true;
                CausticaMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT composite failed; reverting to vanilla path", t);
            return false;
        }
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so per-prim material flags ({@code hasS}/{@code
     * hasN}) resolve from the first section. That makes PBR-on-join structural rather than relying on a
     * re-extract after the fact. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public void ensureResourcesReady(RtContext ctx) {
        if (failed || worldPipeline != null || reloadRebindRequested) {
            return;
        }
        if (Minecraft.getInstance().level == null || blockAtlasView() == 0L) {
            return;
        }
        try {
            ensureWorld(ctx);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT resource bring-up failed; reverting to vanilla path", t);
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            worldPipeline = RtPipeline.create(ctx, RtDeviceBringup.worldRaygenShader(),
                    new String[]{"world.rmiss.spv", "shadow.rmiss.spv"}, "world.rchit.spv", "world.rahit.spv",
                    WORLD_PUSH_CONST_SIZE, true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
            // Per-frame push data lives in this BDA ring; the pipeline only pushes its address.
            if (pushRing == null) {
                pushRing = new RtBuffer[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i);
                }
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is rebuilt and bound per frame in recordFrame since dynamic entity content animates
        // the instance set every frame.
        return worldPipeline;
    }

    private void refreshPipelineShapeIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        int desiredBindlessCapacity = RtEntityTextures.maxTextures();
        if (desiredBindlessCapacity <= bindlessTextureCapacity) {
            return;
        }
        ctx.waitIdle();
        worldPipeline.destroy();
        worldPipeline = null;
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the LabPBR {@code _s}/{@code _n} parallel atlases (bindings 9/10). Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry and recreates the {@code _s}/{@code _n}
     * atlases at the current block-atlas size, then re-extracts all terrain ({@link RtTerrain#markAllDirty})
     * so per-prim material flags are recomputed against the (re)built atlases. On first creation this runs
     * before terrain is resident (see {@link #ensureResourcesReady}), so the re-extract is a no-op and PBR
     * is structural; on a resource reload it refreshes the flags of the already-resident terrain.
     */
    private void bindWorldTextures(RtContext ctx) {
        long sampler = atlasSampler(ctx);
        long atlasView = blockAtlasView();
        boundAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setAtlasSampler(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity);
        worldPipeline.setBindlessTexture(0, 0, atlasView, sampler); // binding 0 (albedo), slot 0 fallback
        // LabPBR _s + _n parallel atlases. Bind the (block-atlas-sized) atlases; their pixels fill
        // lazily as terrain extraction encounters sprites and refresh via flush(). Fall back to the block
        // atlas view if an atlas didn't initialize so material bindings always hold a valid descriptor —
        // the shader only samples them when a prim is flagged (mat.z/mat.w), so the fallback is never read.
        // materialBase = firstExtra(3)+GUIDE_COUNT(23)=26 → _s@26, _n@27, sky@28.
        if (worldPipeline.hasBlockMaterialAtlases()) {
            RtBlockMaterials.INSTANCE.reset();
            // Build the full _s/_n atlases now (parallel decode + blit), before terrain tessellates, so
            // ensure() is a pure lookup on the build path instead of decoding each sprite's maps lazily.
            RtBlockMaterials.INSTANCE.prepareAll();
            long specView = RtBlockMaterials.INSTANCE.viewS();
            long normalView = RtBlockMaterials.INSTANCE.viewN();
            worldPipeline.setBlockSpecAtlas(specView != 0L ? specView : atlasView, sampler);
            worldPipeline.setBlockNormalAtlas(normalView != 0L ? normalView : atlasView, sampler);
            materialBindingsReady = true;
        }
        // Sky rewrite: bind the vanilla celestials atlas (sun + moon phases) for world.rmiss. The view
        // handle is stable across frames; the shader only samples it inside the sun/moon discs (sky
        // directions), so the block-atlas fallback is never read if the celestials atlas isn't ready.
        long celView = celestialsAtlasView();
        if (worldPipeline.hasSkyAtlas()) {
            worldPipeline.setSkyAtlas(celView != 0L ? celView : atlasView, sampler);
        }
        setCelestialUvAtlas(celView);
        RtTerrain.markAllDirty();
    }

    private void refreshMaterialBindingsIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        if (!materialBindingsReady) {
            bindWorldTextures(ctx);
        }
    }

    /** Vulkan image-view of the vanilla celestials atlas (sun + moon-phase sprites), or 0 if unavailable. */
    private static long celestialsAtlasView() {
        try {
            GpuTextureView view = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS).getTextureView();
            return vkImageView(view);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Whether the resolved active denoise backend should run this frame. OFF is never
     * active. AUTO is active unless the resolved upscaler is DLSS-RR and DLSS-RR is
     * actually usable (DLSS-RR has its own denoise). FORCED (FFX / NRD) is always active.
     */
    private static boolean caustica$denoiseEnabled() {
        // Re-enabled for official FFX shadow-split path only (selector refuses
        // hand-written whole-radiance FFX / bilateral that black-screened on RADV).
        // OFF → skip. AUTO → on unless DLSS-RR owns denoise. Forced FFX → on.
        dev.comfyfluffy.caustica.CausticaConfig.DenoiserKind mode =
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Denoise.MODE.value();
        if (mode == dev.comfyfluffy.caustica.CausticaConfig.DenoiserKind.OFF) {
            return false;
        }
        if (mode != dev.comfyfluffy.caustica.CausticaConfig.DenoiserKind.AUTO) {
            // AMD_FIDELITYFX removed in commit 1 (2026-07-20) — AMD AUTO routes to NRD now.
            return mode == dev.comfyfluffy.caustica.CausticaConfig.DenoiserKind.FFX
                    || mode == dev.comfyfluffy.caustica.CausticaConfig.DenoiserKind.NRD
                    || mode == dev.comfyfluffy.caustica.CausticaConfig.DenoiserKind.HYBRID;
        }
        UpscalerSelector.Mode resolved = UpscalerSelector.resolvedMode();
        if (resolved != UpscalerSelector.Mode.DLSS_RR) {
            return true;
        }
        return !dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.dlssdProbeAvailable();
    }

    /**
     * Image-to-image 2D copy with optional Y-flip. Same-size, same-format copies use
     * {@code vkCmdCopyImage} (reliable for rgba16f on RADV). Y-flip still needs blit.
     */
    private static void copyImage(VkCommandBuffer cmd, long srcImage, long dstImage, int width, int height,
                                  boolean yFlip) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!yFlip) {
                // Blit of R16G16B16A16_SFLOAT is not reliably supported; copy is.
                org.lwjgl.vulkan.VkImageCopy.Buffer region = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.extent().width(width).height(height).depth(1);
                VK10.vkCmdCopyImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
                return;
            }
            org.lwjgl.vulkan.VkImageBlit.Buffer region = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(width, height, 1);
            region.get(0).dstOffsets(0).set(0, height, 0);
            region.get(0).dstOffsets(1).set(width, 0, 1);
            VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_NEAREST);
        }
    }

    /**
     * Hooked at the HEAD of {@link net.minecraft.client.Minecraft#reloadResourcePacks()} (mixin). A
     * resource reload re-stitches the block atlas (and reloads entity textures): MC frees the old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so MC can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). Terrain stays resident and is re-extracted via
     * {@code markAllDirty()} so material flags pick up the new pack.
     */
    public void onResourceReloadStart() {
        reloadRebindRequested = true;
        materialBindingsReady = false;
        setCelestialUvAtlas(0L);
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null && worldPipeline != null) {
            ctx.waitIdle();
            worldPipeline.destroy();
            worldPipeline = null;
            bindlessTextureCapacity = 0;
        }
        // Drop the denoise + TAA history. The reload re-stitches the block atlas, so
        // a frame's worth of old-block-texel history will be sampled against the new
        // atlas in the next world frame — even though the guides (gNormal/gAlbedo) are
        // re-extracted, the per-pixel denoiser history ring still holds the old frame's
        // accumulated radiance. Cheaper to just reset than to chase per-texel validity.
        invalidateHistory();
    }

    /** Bind the guide buffers into the world pipeline's extra storage-image slots. */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
        worldPipeline.setExtraStorageImage(5, gSpecMotion.view);
        if (gShadowHit != null) {
            worldPipeline.setExtraStorageImage(6, gShadowHit.view);
        }
        if (gDiffuse != null) {
            worldPipeline.setExtraStorageImage(7, gDiffuse.view);
        }
        if (gReflection != null) {
            worldPipeline.setExtraStorageImage(8, gReflection.view);
        }
        // ReSTIR Direct Illumination bindings (9-11) - disabled until shader integration
        if (ENABLE_RESTIR_DI && blockLightBuffer != null && blockLightBuffer.buffer() != 0L) {
            worldPipeline.setExtraStorageBuffer(9, blockLightBuffer.buffer(), blockLightBuffer.count() * 16L);
        }
        if (ENABLE_RESTIR_DI && reservoirImages != null && reservoirImages.current() != null) {
            worldPipeline.setExtraStorageImage(10, reservoirImages.current().view);
            worldPipeline.setExtraStorageImage(11, reservoirImages.previous().view);
        }
        // ReSTIR GI bindings (13-16): four rgba32f images for direction+wSum / M+age+targetPdf.
        if (ENABLE_RESTIR_GI && giReservoirImages != null
                && giReservoirImages.currentA() != null) {
            worldPipeline.setExtraStorageImage(13, giReservoirImages.currentA().view);
            worldPipeline.setExtraStorageImage(14, giReservoirImages.currentB().view);
            worldPipeline.setExtraStorageImage(15, giReservoirImages.previousA().view);
            worldPipeline.setExtraStorageImage(16, giReservoirImages.previousB().view);
        }
        worldPipeline.setExtraStorageImage(17, gClearEmission.view);
        worldPipeline.setExtraStorageImage(18, gTransmission.view);
        worldPipeline.setExtraStorageImage(19, gViewZ.view);
        worldPipeline.setExtraStorageImage(20, gConfidenceDisocclusion.view);
        worldPipeline.setExtraStorageImage(21, gMaterialFlags.view);
        worldPipeline.setExtraStorageImage(22, gUnshadowedDirect.view);
        if (gJitterGuide != null) {
            worldPipeline.setExtraStorageImage(23, gJitterGuide.view);
        }
    }

    private void destroyGuideImages() {
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (gSpecMotion != null) {
            gSpecMotion.destroy();
            gSpecMotion = null;
        }
        if (gShadowHit != null) {
            gShadowHit.destroy();
            gShadowHit = null;
        }
        if (gDiffuse != null) {
            gDiffuse.destroy();
            gDiffuse = null;
        }
        if (gUnshadowedDirect != null) {
            gUnshadowedDirect.destroy();
            gUnshadowedDirect = null;
        }
        if (gReflection != null) {
            gReflection.destroy();
            gReflection = null;
        }
        if (gClearEmission != null) { gClearEmission.destroy(); gClearEmission = null; }
        if (gTransmission != null) { gTransmission.destroy(); gTransmission = null; }
        if (gViewZ != null) { gViewZ.destroy(); gViewZ = null; }
        if (gConfidenceDisocclusion != null) { gConfidenceDisocclusion.destroy(); gConfidenceDisocclusion = null; }
        if (gMaterialFlags != null) { gMaterialFlags.destroy(); gMaterialFlags = null; }
        if (gJitterGuide != null) { gJitterGuide.destroy(); gJitterGuide = null; }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
        // ReSTIR + light-field cleanup
        if (reservoirImages != null) {
            reservoirImages.destroy();
            reservoirImages = null;
        }
        // ReSTIR GI reservoir cleanup
        if (giReservoirImages != null) {
            giReservoirImages.destroy();
            giReservoirImages = null;
        }
        if (blockLightBuffer != null) {
            RtContext ctx = RtContext.get();
            if (ctx != null) {
                blockLightBuffer.destroy(ctx);
            }
            blockLightBuffer = null;
        }
        if (lightFieldVolume != null) {
            lightFieldVolume.destroy();
            lightFieldVolume = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        Upscaler activeUpscaler = UpscalerSelector.current();
        // Reduced internal res: DLSS-RR (raw→RR) and FSR/XeSS (denoise→upscale).
        // Off / noop: full display 1:1 + beauty TAA blit.
        boolean reducedRender = isDlssRr(activeUpscaler) || isSpatialUpscaler(activeUpscaler)
                || activeUpscaler.mode() == UpscalerSelector.Mode.TAAU;
        int upscalerQuality = activeUpscalerQuality(activeUpscaler);
        if (output != null && displayImage != null && hdrDisplayImage != null && rrOutput != null && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeRrEnabled == reducedRender && renderSizeRrQuality == upscalerQuality) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        destroyGuideImages();

        displayW = width;
        displayH = height;

        // Initialize DRS if enabled and not yet created
        if (ENABLE_DRS && drs == null && CausticaConfig.Drs.ENABLED.value()) {
            try {
                drs = new RtDynamicResolution();
                drs.setDisplayResolution(width, height);
                CausticaMod.LOGGER.info("Dynamic Resolution Scaling initialized for {}x{}", width, height);
            } catch (Exception e) {
                CausticaMod.LOGGER.error("Failed to initialize DRS", e);
            }
        }

        // Update DRS display resolution on resize
        if (drs != null) {
            drs.setDisplayResolution(width, height);
        }

        // Calculate render resolution: DRS overrides upscaler's optimal size
        int targetRenderW, targetRenderH;
        if (drs != null) {
            // DRS determines render resolution
            targetRenderW = drs.getRenderWidth();
            targetRenderH = drs.getRenderHeight();
        } else {
            // Fallback: use upscaler's optimal size or native resolution
            int[] optimal = reducedRender ? activeUpscaler.queryOptimalRenderSize(width, height) : null;
            targetRenderW = optimal != null ? optimal[0] : width;
            targetRenderH = optimal != null ? optimal[1] : height;
        }

        renderW = targetRenderW;
        renderH = targetRenderH;
        renderSizeRrEnabled = reducedRender;
        renderSizeRrQuality = upscalerQuality;

        // Pure RGB beauty plates: B10G11R11_UFLOAT (32bpp) — half the bandwidth of RGBA16F while
        // still carrying HDR radiance to the display mapping seam. Alpha is unused on this path.
        // Guide buffers / NRD YCoCg packs / TAA history (depth-in-alpha) stay RGBA16F.
        // displayImage stays R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, RtContext.HDR_RADIANCE_FORMAT, "trace color " + renderW + "x" + renderH);
        // Firefly-killed radiance: 3x3 median over `output` (the path-tracer result).
        // Both NRD and TAAU read from this instead of `output` so SPP=1 fireflies
        // never enter temporal history or the final composite.
        if (fireflyKilled != null) {
            fireflyKilled.destroy();
            fireflyKilled = null;
        }
        fireflyKilled = ctx.createStorageImage(renderW, renderH, RtContext.HDR_RADIANCE_FORMAT,
                "firefly-killed radiance " + renderW + "x" + renderH);
        fireflyKill.ensureSized(ctx, renderW, renderH, fireflyKilled);
        // Denoised color: same size + format as output, lives at render res. The active backend owns its
        // own intermediate textures; this single output is what the upscaler reads.
        if (denoisedColor != null) {
            denoisedColor.destroy();
        }
        denoisedColor = ctx.createStorageImage(renderW, renderH, RtContext.HDR_RADIANCE_FORMAT,
                "denoise color " + renderW + "x" + renderH);
        // Temporal-accumulation visible output: same size + format as the noisy trace so it can
        // drop straight into the denoise/upscaler input slot. Allocated unconditionally (the
        // temporal pass is gated per-frame by temporalAccumEnabled()); the history ring lives in
        // RtTemporalAccumulation (still RGBA16F — alpha carries depth) and is sized lazily on its ensureSized().
        if (accumulatedColor != null) {
            accumulatedColor.destroy();
        }
        accumulatedColor = ctx.createStorageImage(renderW, renderH, RtContext.HDR_RADIANCE_FORMAT,
                "temporal accum color " + renderW + "x" + renderH);
        if (temporalAccum != null) {
            temporalAccum.ensureSized(renderW, renderH);
        }
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // PQ-encoded ([0,1], ST.2084) HDR display image, written in parallel by display.comp when HDR mode is active.
        hdrDisplayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + width + "x" + height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderW + "x" + renderH);
        // RGBA: xy = screen MV (pixels), z = viewZprev - viewZ (NRD 2.5D), w unused.
        // RG-only made REBLUR miss forward/back motion → history stuck on entities / water.
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderW + "x" + renderH);
        // Split lighting for official FFX Denoiser (P1): shadow hit + diffuse + reflection.
        gShadowHit = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R8_UNORM, "split shadow hit " + renderW + "x" + renderH);
        gDiffuse = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "split diffuse " + renderW + "x" + renderH);
        gUnshadowedDirect = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "unshadowed direct " + renderW + "x" + renderH);
        gReflection = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "split reflection " + renderW + "x" + renderH);
        gClearEmission = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "clear emission " + renderW + "x" + renderH);
        gTransmission = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "transmission radiance " + renderW + "x" + renderH);
        gViewZ = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "view z " + renderW + "x" + renderH);
        gConfidenceDisocclusion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "confidence disocclusion " + renderW + "x" + renderH);
        gMaterialFlags = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_UINT, "material flags " + renderW + "x" + renderH);
        // Tile-jitter guide (v0.6.8+): per-tile sub-pixel offset written by world.rgen, read by
        // nrd_prewarp.comp. R8G8_UNORM is the minimum precision that fits the ±0.5 render-pixel
        // range with 1/256 quantisation (current 1/8 step uses ~0.055 px, so 1/256 is ample).
        gJitterGuide = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R8G8_UNORM, "tile jitter guide " + renderW + "x" + renderH);
        // Display-res beauty the display mapper / exposure histogram read. Same pure-RGB HDR format as the
        // render-res beauty chain (B10G11R11). Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, RtContext.HDR_RADIANCE_FORMAT, "DLSS-RR output " + width + "x" + height);
        exposure.ensureResources(ctx);

        // ReSTIR Direct Illumination: reservoir images + light buffer. Real emissive blocks are
        // harvested each frame by UnifiedLightManager.rescanAround() — no fake test lights.
        if (ENABLE_RESTIR_DI) {
            if (unifiedLightManager == null) {
                unifiedLightManager = new dev.comfyfluffy.caustica.rt.light.UnifiedLightManager();
            }
            if (blockLightBuffer == null) {
                blockLightBuffer = new dev.comfyfluffy.caustica.rt.light.BlockLightBuffer();
            }
            if (reservoirImages == null) {
                reservoirImages = new dev.comfyfluffy.caustica.rt.light.ReservoirImages();
            }
            reservoirImages.ensureSized(ctx, renderW, renderH);
        }
        // ReSTIR GI: direction-based reservoir images (4 rgba32f).
        if (ENABLE_RESTIR_GI) {
            if (giReservoirImages == null) {
                giReservoirImages = new dev.comfyfluffy.caustica.rt.light.ReservoirImagesGI();
            }
            giReservoirImages.ensureSized(ctx, renderW, renderH);
        }
        if (ENABLE_LIGHTFIELD_GI && lightFieldVolume == null) {
            lightFieldVolume = new dev.comfyfluffy.caustica.rt.light.LightFieldVolume();
        }

        mvHasPrev = false; // recreated images -> first MV frame is zero
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view);
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion() {
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        if (mvHasPrev) {
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (camX - mvPrevCamX);
            mvCamDeltaY = (float) (camY - mvPrevCamY);
            mvCamDeltaZ = (float) (camZ - mvPrevCamZ);
            // Hard-cut detection: a per-frame camera translation larger than 8 blocks is a
            // teleport (chunk teleport, /tp, Nether portal, respawn). The motion vector
            // reprojection will read 32+ screen pixels of MV and try to fetch a history sample
            // from a frame that was taken in a completely different world region — the
            // disocclusion threshold catches a chunk of it, but the depth+normal guide
            // agreement is what really tells the denoiser "this is new", and that test is
            // currently weak (see shaders/display/denoise_ffx/ffx_reproject.comp). The cheap
            // + safe fix: just drop the history. 8 blocks is well above any normal walking,
            // sprinting, Elytra boost, or boat speed (sprint+jump is ~7 m/s = 0.45 blocks per
            // 60 fps frame, Elyta ~30 m/s = ~2 blocks/frame).
            //
            // NaN/Inf safety: if camX / camY / camZ are NaN (a bug in the camera capture
            // path, or a position read during world unload), mvCamDeltaX becomes NaN.
            // The squared sum is NaN, and `NaN > 64.0f` evaluates to false in IEEE 754,
            // so the teleport would silently NOT fire and the denoiser would happily
            // sample garbage. Be explicit: if any of the deltas is non-finite, force the
            // reset. The next frame's mvHasPrev=false will write a clean prevViewProj and
            // the rgen will compute a finite MV from there.
            float deltaSq = mvCamDeltaX * mvCamDeltaX + mvCamDeltaY * mvCamDeltaY + mvCamDeltaZ * mvCamDeltaZ;
            boolean deltaNaN = !Float.isFinite(mvCamDeltaX) || !Float.isFinite(mvCamDeltaY) || !Float.isFinite(mvCamDeltaZ);
            if (deltaNaN || deltaSq > 64.0f /* 8 blocks squared */) {
                invalidateHistory();
                mvHasPrev = false; // also reset the prev-frame capture so the very next frame's
                                   // MV is zero (the rgen writes prevViewProj as the current
                                   // view-projection when mvHasPrev is false, see recordFrame).
            }
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = camX;
        mvPrevCamY = camY;
        mvPrevCamZ = camZ;
        mvHasPrev = true;
    }

    private void recordFrame(RtContext ctx, RtPipeline active, GpuTexture nativeColor, int dbgCount) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // Post stack:
            //   DLSS-RR: raw + jitter → RR evaluate
            //   FSR/XeSS: denoise (FFX/NRD) at render res + jitter → FSR evaluate
            //   Off: denoise → beauty TAA → 1:1 blit
            int debugView = debugView();
            Upscaler activeUpscaler = UpscalerSelector.current();
            boolean vendorTemporal = isDlssRr(activeUpscaler) && debugView == 0;
            boolean spatialUpscale = isSpatialUpscaler(activeUpscaler) && debugView == 0 && !vendorTemporal;
            boolean playablePath = !vendorTemporal && debugView == 0;
            float jitterX = 0f;
            float jitterY = 0f;
            // 2026-07-20: the playable path (FFX denoise + 1:1 blit) was excluded from jitter
            // generation because it was assumed to be raw + post-TAA. But FFX's whole-radiance
            // temporal accumulator (reproject + resolve + atrous) needs per-frame sub-pixel
            // input variance to smooth SPP-2 noise — without jitter, every frame samples the
            // same sub-pixel, history == current, and the output is just the raw radiance
            // (static scene looks identical frame-to-frame). Adding playablePath to the
            // jitter branch so FFX on a FSR=off path actually has variance to smooth.
            if (vendorTemporal || spatialUpscale || playablePath) {
                CausticaJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }
            // Render-pixel units, signed exactly as applied to the primary ray. An evaluate API
            // that asks for de-jitter receives the inverse of these values at its call site.
            lastJitterPixelsX = jitterX;
            lastJitterPixelsY = jitterY;

            boolean rrDone = false;
            RtTerrain terrain = RtTerrain.currentOrNull();
            // Write this frame's push data into the next BDA ring slot (cycled so an in-flight slot is
            // never overwritten). The std430 WorldPush layout matches these byte offsets exactly.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            RtBuffer pushBuf = pushRing[pushSlot];
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert().get(0, push);
            mvCurProjView.get(352, push); // forward camera-relative view-projection: HW depth guide + water MV
            push.putFloat(64, (float) (camX - terrain.blockX));
            push.putFloat(68, (float) (camY - terrain.blockY));
            push.putFloat(72, (float) (camZ - terrain.blockZ));
            push.putLong(80, terrain.tableAddress());
            push.putInt(88, debugView);
            push.putInt(92, (int) frameCounter); // per-frame RNG variation for the denoiser
            mvPushMatrix.get(96, push);
            push.putFloat(160, mvCamDeltaX);
            push.putFloat(164, mvCamDeltaY);
            push.putFloat(168, mvCamDeltaZ);
            push.putInt(172, spp());
            push.putFloat(176, jitterX);
            push.putFloat(180, jitterY);
            // flags: PBR BRDF (bit 1, always on) + camera-in-water (so the path tracer starts in the water
            // medium when the eye is submerged, fixing the air→water first-segment orientation).
            int flags = 0b10;
            var level = Minecraft.getInstance().level;
            if (level != null) {
                cameraBlockPos.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
                // Height-aware, mirroring vanilla Camera.getFluidInCamera(): a plain block-granular
                // test wrongly flags the eye submerged anywhere in a water column's top block.
                net.minecraft.world.level.material.FluidState fs = level.getFluidState(cameraBlockPos);
                if (fs.is(FluidTags.WATER) && camY < cameraBlockPos.getY() + fs.getHeight(level, cameraBlockPos)) {
                    flags |= 0b01;
                }
            }
            if (waterWaves()) {
                flags |= 0b10000; // W1: animated water wave normals
            }
            // bit 2 = secondary moon NEE user-disable gate (1 = disabled). Default 0 = moon is also
            // sampled as a second NEE light when below the horizon — eliminates the SPP-1 "black abyss"
            // under tree cover at night for free (one extra shadow ray / pixel).
            if (CausticaConfig.Rt.Composite.SECONDARY_MOON_NEE.value()) {
                flags |= 0b100;
            }
            // bit 3 = adaptive SPP master switch (default 0 = on). world.rgen bumps SPP to 2 for
            // transparent / water / emissive-adjacent pixels and leaves sky at SPP 1.
            if (ENABLE_ADAPTIVE_SPP && CausticaConfig.Rt.Composite.ADAPTIVE_SPP.value()) {
                flags |= 0b1000;
            }
            push.putInt(192, flags);
            push.putInt(196, maxBounces());
            push.putFloat(200, maxRayDistance());
            writeSky(push);

            // W1/W2 water params @320 (sunUv@288 / moonUv@304 belong to the sky push above): xyz = the
            // camera biome's water tint (drives absorption when the eye starts submerged, before any water
            // surface is hit); w = wave animation time (seconds, wrapped to keep float precision). Per-
            // water-body tint comes from the prim; this is only the fallback.
            float wtr = 0.25f, wtg = 0.46f, wtb = 0.9f; // neutral ocean-ish default if no level/biome
            if (level != null) {
                int wc = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
                wtr = ((wc >> 16) & 0xFF) / 255f;
                wtg = ((wc >> 8) & 0xFF) / 255f;
                wtb = (wc & 0xFF) / 255f;
            }
            push.putFloat(320, wtr);
            push.putFloat(324, wtg);
            push.putFloat(328, wtb);
            push.putFloat(332, (float) (System.nanoTime() / 1.0e9 % 3600.0));
            // W1 wave-domain anchor @336: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so the
            // ripple pattern stays fixed in the world as the player moves and the rebase origin shifts.
            push.putFloat(336, terrain.blockX & WATER_ANCHOR_MASK);
            push.putFloat(340, terrain.blockZ & WATER_ANCHOR_MASK);

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired KEEP_FRAMES later.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. geomTableAddr
            // feeds the hit shader entity path (per-prim normal/tint) and motion vectors.
            RtEntities.FrameEntities fe = ENABLE_DYNAMIC_ENTITY_RT
                    ? RtEntities.INSTANCE.beginFrame(ctx, terrain.staticInstances(),
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ,
                    frameProjection, frameViewRotation)
                    : new RtEntities.FrameEntities(terrain.staticInstances(), java.util.List.of(), 0L);
            push.putLong(184, fe.geomTableAddr());
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            writeBreaking(push, terrain);

            // ReSTIR DI: harvest nearby emissives, rebase into terrain space, upload, then write
            // push constants from the *actual* GPU buffer count (not the pre-upload estimate).
            // Rebind the SSBO every frame so buffer growth / first-light frames stay valid.
            int restirOffset = BREAKING_OFFSET + MAX_BREAKING * 16;
            if (ENABLE_RESTIR_DI && unifiedLightManager != null && blockLightBuffer != null) {
                unifiedLightManager.updateFrame(level, camX, camY, camZ, frameCounter);
                var lights = unifiedLightManager.getAllLights(
                        (float) terrain.blockX, (float) terrain.blockY, (float) terrain.blockZ);
                boolean lightUploadChanged = blockLightBuffer.upload(
                        ctx, lights, unifiedLightManager.revision());
                if (lightUploadChanged && worldPipeline != null
                        && blockLightBuffer.buffer() != 0L && blockLightBuffer.count() > 0) {
                    worldPipeline.setExtraStorageBuffer(9, blockLightBuffer.buffer(),
                            Math.max(16L, (long) blockLightBuffer.count() * 16L));
                }
                if (worldPipeline != null && reservoirImages != null && reservoirImages.current() != null) {
                    worldPipeline.setExtraStorageImage(10, reservoirImages.current().view);
                    worldPipeline.setExtraStorageImage(11, reservoirImages.previous().view);
                }
                push.putInt(restirOffset, blockLightBuffer.count());
                push.putInt(restirOffset + 4, 4);        // bounded ReSTIR DI candidates
                push.putFloat(restirOffset + 8, 12.0f);  // bounded temporal reservoir weight
                push.putFloat(restirOffset + 12, 24.0f); // bounded spatial reservoir weight
                if (frameCounter % 60 == 0) {
                    CausticaMod.LOGGER.info("ReSTIR DI: {} lights ({} static + {} dynamic)",
                            blockLightBuffer.count(),
                            unifiedLightManager.getBlockLights().getLightCount(),
                            unifiedLightManager.getDynamicLights().getLightCount());
                }
            } else {
                push.putInt(restirOffset, 0);
                push.putInt(restirOffset + 4, 0);
                push.putFloat(restirOffset + 8, 0.0f);
                push.putFloat(restirOffset + 12, 0.0f);
            }

            // Vanilla light-field GI base: packed sky/block levels around the camera.
            // Origin is rebased into terrain space so shader hitPos can index it directly.
            int lightFieldOffset = restirOffset + RESTIR_PUSH_BYTES;
            if (ENABLE_LIGHTFIELD_GI && lightFieldVolume != null && level != null) {
                lightFieldVolume.update(level, Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ), (int) frameCounter);
                lightFieldVolume.upload(ctx);
                if (worldPipeline != null && lightFieldVolume.valid()) {
                    worldPipeline.setExtraStorageBuffer(12, lightFieldVolume.buffer(), lightFieldVolume.byteSize());
                }
                // World-space origin → rebased (same frame as hitPos / camOffset).
                push.putInt(lightFieldOffset, lightFieldVolume.originX() - terrain.blockX);
                push.putInt(lightFieldOffset + 4, lightFieldVolume.originY() - terrain.blockY);
                push.putInt(lightFieldOffset + 8, lightFieldVolume.originZ() - terrain.blockZ);
                push.putInt(lightFieldOffset + 12, lightFieldVolume.valid() ? lightFieldVolume.size() : 0);
            } else {
                push.putInt(lightFieldOffset, 0);
                push.putInt(lightFieldOffset + 4, 0);
                push.putInt(lightFieldOffset + 8, 0);
                push.putInt(lightFieldOffset + 12, 0);
            }

            // ReSTIR GI: direction-based reservoir push constants (@592, 32 bytes total).
            int giOffset = lightFieldOffset + LIGHTFIELD_PUSH_BYTES;
            if (ENABLE_RESTIR_GI) {
                push.putInt(giOffset + 0, CausticaConfig.Rt.Gi.ENABLED.value() ? 1 : 0);
                push.putInt(giOffset + 4, CausticaConfig.Rt.Gi.CANDIDATES.value());
                push.putFloat(giOffset + 8, CausticaConfig.Rt.Gi.MAX_M_TEMPORAL.value());
                push.putFloat(giOffset + 12, CausticaConfig.Rt.Gi.MAX_M_SPATIAL.value());
                push.putFloat(giOffset + 16, CausticaConfig.Rt.Gi.HEMI_SKY_SCALE.value());
                push.putFloat(giOffset + 20, CausticaConfig.Rt.Gi.HEMI_GROUND_SCALE.value());
                push.putFloat(giOffset + 24, CausticaConfig.Rt.Gi.LIGHTFIELD_BLEND.value());
                push.putFloat(giOffset + 28, 0.0f);
                if (worldPipeline != null && giReservoirImages != null && giReservoirImages.currentA() != null) {
                    worldPipeline.setExtraStorageImage(13, giReservoirImages.currentA().view);
                    worldPipeline.setExtraStorageImage(14, giReservoirImages.currentB().view);
                    worldPipeline.setExtraStorageImage(15, giReservoirImages.previousA().view);
                    worldPipeline.setExtraStorageImage(16, giReservoirImages.previousB().view);
                }
            } else {
                push.putInt(giOffset + 0, 0);
                push.putInt(giOffset + 4, 0);
                push.putFloat(giOffset + 8, 0.0f);
                push.putFloat(giOffset + 12, 0.0f);
                push.putFloat(giOffset + 16, 0.0f);
                push.putFloat(giOffset + 20, 0.0f);
                push.putFloat(giOffset + 24, 0.0f);
                push.putFloat(giOffset + 28, 0.0f);
            }

            // Hybrid rendering fast-path push constants (@624, 16 bytes total).
            int hybridOffset = giOffset + GI_PUSH_BYTES;
            push.putInt(hybridOffset + 0, CausticaConfig.Rt.Hybrid.ENABLED.value() ? 1 : 0);
            push.putFloat(hybridOffset + 4, CausticaConfig.Rt.Hybrid.ROUGH_THRESHOLD.value());
            push.putFloat(hybridOffset + 8, CausticaConfig.Rt.Hybrid.LIGHTFIELD_THRESHOLD.value());
            push.putFloat(hybridOffset + 12, 0.0f);

            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // Re-upload the LabPBR _s atlas if extraction added sprites since the last frame (the
            // view handle is stable, so no re-bind needed). Before the trace records, like uploadPending.
            RtBlockMaterials.INSTANCE.flush();
            RtEntityMaterials.INSTANCE.flushAll(); // block-entity parallel _s/_n blitted during capture
            // Build the entity BLAS this frame, then the TLAS that references them (+ the already-built
            // terrain BLAS), then the trace — each separated by a barrier. The frame TLAS is retired
            // KEEP_FRAMES later (entity meshes/BLAS are retired by RtEntities on the same horizon).
            if (!fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blasRecord")) {
                    RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = RtAccel.prepareTlas(ctx, fe.instances(), tlasRing);
            }
            active.setTlas(frameTlas.accel.handle);
            currentTlasHandle = frameTlas.accel.handle;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // Initialize VRS on first use (lazy init)
            if (ENABLE_VRS && vrs == null && RtDeviceBringup.vrsEnabled()) {
                try {
                    vrs = new RtVariableRateShading(ctx);
                    CausticaMod.LOGGER.info("Variable Rate Shading lazy initialized");
                } catch (Exception e) {
                    CausticaMod.LOGGER.error("Failed to lazy init VRS", e);
                }
            }

            // Generate shading rate before raygen (if VRS enabled and resources created)
            if (vrs != null && gDepth != null && gAlbedo != null) {
                try {
                    // Create/resize VRS resources if needed
                    if (vrs.getShadingRateImageView() == 0L) {
                        vrs.createResources(renderW, renderH);
                    }
                    // Generate shading rate from depth and albedo
                    vrs.generateShadingRate(cmd, gDepth, gAlbedo, renderW, renderH);
                } catch (Exception e) {
                    CausticaMod.LOGGER.error("VRS generation failed", e);
                }
            }

            ByteBuffer pushAddr = stack.malloc(WORLD_PUSH_CONST_SIZE)
                    .putLong(0, pushBuf.deviceAddress)
                    .putLong(8, terrain.tableAddress())
                    .putLong(16, fe.geomTableAddr())
                    .putInt(24, (int) frameCounter);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.trace")) {
                if (active == null) {
                    System.err.println("[Caustica RT] ALARM: active pipeline is null in recordFrame");
                } else if (renderW <= 0 || renderH <= 0) {
                    System.err.println("[Caustica RT] ALARM: trace dispatched with zero size " + renderW + "x" + renderH);
                }
                active.trace(cmd, renderW, renderH, pushAddr);
                if (dbgCount <= 5 || dbgCount % 60 == 0) {
                    System.err.println("[Caustica RT] trace dispatched #" + dbgCount + " size=" + renderW + "x" + renderH);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to denoise + DLSS reads

            // Firefly-kill pass: disabled by default (v0.6 revert). The 3x3 median outlier rejection
            // was correctly killing *isolated* bright pixels (real SPP=1 fireflies) but it ALSO
            // killed legitimate light-source contributions in enclosed rooms: a single bright
            // pixel from a sea-lantern NEE hit is statistically indistinguishable from a firefly
            // (centre > 4x all 8 neighbours) so the pass clobbered the light contribution, and
            // the rest of the room (which depends on that contribution for GI propagation) went
            // dark. Disabled by default -- can be re-enabled via a future `firefly_kill.enabled`
            // config flag once the discrimination heuristic improves (likely needs a depth/normal
            // guard around the median). Per-denoiser backends (e.g. AmdFidelityFxDenoiseBackend)
            // can opt in to running firefly_kill as their pre-pass step inside dispatch().
            //
            // (Kept the dispatch point + image allocation + pipeline alive in case the future
            // flag lands; just not invoked here.)
            //if (fireflyKilled != null && output != null && fireflyKill.isReady()) {
            //    try (...) { fireflyKill.dispatch(cmd.address(), ctx, output, fireflyKilled); }
            //    VulkanCommandEncoder.memoryBarrier(cmd, stack);
            //}

            // --- Playable stack: Official FFX (shadow+reflection) THEN beauty TAA ---
            // Order matters: denoise cleans noisy shadow/spec layers; TAA stabilizes residual GI/sky.
            lastDenoiseOn = false;
            lastDenoisePath = "off";
            RtImage beautyAfterDenoise = output;
            // Any denoise (NRD REBLUR or FFX temporal) already owns temporal accumulation — stacking the
            // standalone beauty TAA on top makes the image milky/soft and produces ghost trails along
            // motion vectors. Used to be gated only on NRD (`nrdOwnsTemporal`) which let FFX-only paths
            // stack TAA on top — the "noise turns into a smearing trail" symptom.
            boolean denoiseWillRun = false;
            lastNrdPrepareOk = false;
            lastNrdDispatchOk = false;
            lastNrdComposeOk = false;
            if (playablePath && caustica$denoiseEnabled() && denoisedColor != null) {
                CausticaDenoiseBackend backend = null;
                try {
                    backend = DenoiseBackendSelector.current(ctx.device());
                } catch (Throwable t) {
                    CausticaMod.LOGGER.warn("DenoiseBackendSelector.current() threw; denoise=OFF this frame", t);
                    try { DenoiseBackendSelector.invalidate(); } catch (Throwable t2) { }
                }
                if (backend != null && !(backend instanceof dev.comfyfluffy.caustica.denoise.NoopDenoiseBackend)) {
                    try {
                        backend.ensureSized(renderW, renderH);
                    } catch (Throwable t) {
                        CausticaMod.LOGGER.warn("Denoise backend '{}' ensureSized() threw", backend.name(), t);
                    }
                    try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "denoise:" + backend.name());
                         RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.denoise")) {
                        if (gShadowHit != null && gDiffuse != null && gReflection != null) {
                            if (backend instanceof dev.comfyfluffy.caustica.denoise.AmdFidelityFxDenoiseBackend amd) {
                                amd.setSplitBuffers(gShadowHit, gDiffuse, gReflection);
                                if (gUnshadowedDirect != null) {
                                    amd.setUnshadowedDirect(gUnshadowedDirect);
                                }
                                if (gSpecMotion != null) {
                                    amd.setSpecMotion(gSpecMotion);
                                }
                            } else if (backend instanceof dev.comfyfluffy.caustica.denoise.OfficialFfxDenoiseBackend official) {
                                official.setSplitBuffers(gShadowHit, gDiffuse, gReflection);
                                if (gUnshadowedDirect != null) {
                                    official.setUnshadowedDirect(gUnshadowedDirect);
                                }
                                if (gSpecMotion != null) {
                                    official.setSpecMotion(gSpecMotion);
                                }
                            } else if (backend instanceof dev.comfyfluffy.caustica.denoise.HybridFfxNrdBackend hybrid) {
                                hybrid.setSplitBuffers(gShadowHit, gDiffuse, gReflection);
                                hybrid.setUnshadowedDirectGuide(gUnshadowedDirect);
                                if (gSpecMotion != null) {
                                    hybrid.setSpecMotion(gSpecMotion);
                                }
                                if (gAlbedo != null && gSpecAlbedo != null) {
                                    hybrid.setAlbedoGuides(gAlbedo, gSpecAlbedo);
                                }
                                if (gViewZ != null) {
                                    hybrid.setViewZGuide(gViewZ);
                                }
                                if (gMaterialFlags != null) {
                                    hybrid.setMaterialFlagsGuide(gMaterialFlags);
                                }
                                if (gClearEmission != null) {
                                    hybrid.setClearEmissionGuide(gClearEmission);
                                }
                                if (gTransmission != null) {
                                    hybrid.setTransmissionGuide(gTransmission);
                                }
                                if (gConfidenceDisocclusion != null) {
                                    hybrid.setConfidenceDisocclusionGuide(gConfidenceDisocclusion);
                                }
                                // Tile-jitter guide for NRD pre-warp (v0.6.8+). Without this,
                                // NRD's reproject samples previous history at the wrong sub-pixel
                                // offset (NRD sees only the global jitter, not the per-tile one).
                                if (gJitterGuide != null) {
                                    hybrid.setJitterGuide(gJitterGuide);
                                }
                                // Same guide for the TAAU upscaler (if active). TAAU's reproject
                                // reads this and adds the per-tile offset to prevUV so the temporal
                                // blend lines up with the path tracer's actual sampling. Default
                                // no-op on upscalers that don't use the guide (DLSS-RR, FSR, XeSS).
                                if (activeUpscaler != null && gJitterGuide != null) {
                                    activeUpscaler.setJitterGuide(gJitterGuide);
                                }
                                hybrid.setLightDirection(push.getFloat(224), push.getFloat(228), push.getFloat(232));
                                // NRD needs camera-relative worldToView + camDelta so walking
                                // does not leave milky trails; jitter must be UV not pixels.
                                hybrid.setCameraFrame(frameViewRotation, frameProjection,
                                        frameViewRotationPrev, frameProjectionPrev,
                                        mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ,
                                        jitterX, jitterY, renderW, renderH);
                            }
                        }
                        // Denoise dispatch
                        // Note: True async compute (dual-queue overlap) requires fundamental refactoring
                        // of composite() to split submissions. For now, use single-queue path.
                        boolean denoiseSucceeded = backend.dispatch(stack, cmd, output, gNormal, gDepth, gMotion,
                                1.0f / Math.max(1, renderW), 1.0f / Math.max(1, renderH),
                                denoisedColor);

                        if (asyncCompute != null) {
                            asyncCompute.recordFallbackFrame();
                        }

                        lastDenoiseOn = denoiseSucceeded;
                        beautyAfterDenoise = denoiseSucceeded ? denoisedColor : output;
                        lastDenoisePath = backend.name();
                        // Both NRD and FFX paths own their own temporal accumulation — flag both so the
                        // standalone beauty TAA below correctly skips regardless of which backend ran.
                        denoiseWillRun = denoiseSucceeded;
                        if (backend instanceof dev.comfyfluffy.caustica.denoise.HybridFfxNrdBackend hybrid) {
                            lastNrdPrepareOk = hybrid.lastPrepareOk();
                            lastNrdDispatchOk = hybrid.lastDispatchOk();
                            lastNrdComposeOk = hybrid.lastComposeOk();
                            lastDenoisePath = hybrid.lastPathLabel();
                        } else if (backend instanceof dev.comfyfluffy.caustica.denoise.AmdFidelityFxDenoiseBackend amd) {
                            lastDenoisePath = amd.lastPathLabel();
                        } else if (backend instanceof dev.comfyfluffy.caustica.denoise.OfficialFfxDenoiseBackend) {
                            lastDenoisePath = "ffx";
                        }
                    } catch (Throwable t) {
                        CausticaMod.LOGGER.warn("Denoise backend '{}' threw; raw beauty this frame", backend.name(), t);
                        try { DenoiseBackendSelector.invalidate(); } catch (Throwable t2) { }
                        lastDenoiseOn = false;
                        lastDenoisePath = "off";
                        beautyAfterDenoise = output;
                    }
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                }
            }

            boolean temporalAccumRan = false;
            // Beauty TAA: NEVER stack on top of any denoise. REBLUR / FFX temporal already own history;
            // a second TAA reprojects bright sky/leaves onto new pixels → dark outline ghosts (exactly
            // the "sky silhouette smear" with NRD + light TAA, and the equivalent FFX + TAA smear).
            // Keep TAA only for raw / denoise-off paths.
            if (playablePath && temporalAccumEnabled(activeUpscaler) && accumulatedColor != null
                    && !denoiseWillRun) {
                try {
                    if (temporalAccum == null) {
                        temporalAccum = RtTemporalAccumulation.create(ctx);
                    }
                    temporalAccum.ensureSized(renderW, renderH);
                    try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "beauty temporal (TAA)");
                         RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.temporalAccum")) {
                        float taaAlpha = Math.min(temporalAlpha(), lastDenoiseOn ? 0.40f : 0.45f);
                        if (lastDenoiseOn) {
                            lastDenoisePath = lastDenoisePath + " + beauty TAA";
                        }
                        temporalAccum.dispatch(stack, cmd, beautyAfterDenoise, gNormal, gDepth, gMotion,
                                1.0f / Math.max(1, renderW), 1.0f / Math.max(1, renderH),
                                taaAlpha, temporalDisocclusion(), accumulatedColor);
                        temporalAccumRan = true;
                    }
                } catch (Throwable t) {
                    CausticaMod.LOGGER.warn("Beauty TAA threw; using pre-TAA beauty this frame", t);
                    temporalAccumRan = false;
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }

            // Display plate: TAA → denoise beauty → raw (FSR/XeSS/RR read this at render res)
            RtImage displayPlate = temporalAccumRan ? accumulatedColor
                    : (lastDenoiseOn ? denoisedColor : output);

            int upscalerQuality = activeUpscalerQuality(activeUpscaler);
            if (vendorTemporal && activeUpscaler.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH,
                    upscalerQuality, 0)) {
                // DLSS-RR: raw path-traced samples (RR is the denoise).
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, activeUpscaler.mode().key() + " evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscaler")) {
                    rrDone = activeUpscaler.evaluate(cmd.address(), output, gDepth, gMotion,
                            gAlbedo, gSpecAlbedo, gNormal, gSpecMotion, null,
                            rrOutput, renderW, renderH, displayW, displayH,
                            -jitterX, -jitterY, frameViewRotation, frameProjection);
                }
            } else if (spatialUpscale && activeUpscaler.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH,
                    upscalerQuality, 0)) {
                // FSR2/XeSS: upscale the *denoised* plate (Radiance denoise → temporal upscale).
                // Denoising does not undo the primary-ray sample position: depth, motion and
                // colour remain on this frame's jittered render grid. FSR must therefore receive
                // non-zero jitter every frame, including after denoise, or its history locks
                // never see sub-pixel coverage.
                //
                // DLSS-RR receives (-jitterX, -jitterY) as de-jitter (= camera jitter). FSR2/XeSS
                // also expect camera jitter semantics: the offset applied to the projection matrix,
                // where +X shifts the camera right → objects appear left.  Caustica applies jitter
                // to the RAY direction instead: +X shifts the ray right → the sample at pixel (x)
                // reads from (x + jitterX).  This is the OPPOSITE effect of camera jitter, so the
                // camera-equivalent jitter for all temporal upscalers is (-jitterX, -jitterY).
                // TAAU is the exception: its shader knows the raw ray offset in render pixels.
                float fsrJx;
                float fsrJy;
                if (activeUpscaler.mode() == UpscalerSelector.Mode.TAAU) {
                    fsrJx = jitterX;
                    fsrJy = jitterY;
                } else {
                    fsrJx = -jitterX;
                    fsrJy = -jitterY;
                }
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, activeUpscaler.mode().key() + " evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscaler")) {
                    rrDone = activeUpscaler.evaluate(cmd.address(), displayPlate, gDepth, gMotion,
                            gAlbedo, gSpecAlbedo, gNormal, gSpecMotion, null,
                            rrOutput, renderW, renderH, displayW, displayH,
                            fsrJx, fsrJy, frameViewRotation, frameProjection);
                }
                // Fail-open: FSR2 can return true/rc=0 while writing a pure-black plate
                // (e.g. bad exposure flags / format mismatch). Detect and force blit fallback.
                if (rrDone && activeUpscaler instanceof dev.comfyfluffy.caustica.fsr.Fsr2ClassicUpscaler fsr
                        && fsr.consumeBlackoutFailOpen()) {
                    rrDone = false;
                    CausticaMod.LOGGER.warn(
                            "FSR2 blackout detected — falling back to 1:1 blit this frame (and subsequent until healthy)");
                }
            }
            lastUpscalerPath = (vendorTemporal || spatialUpscale) && rrDone;
            if (isDlssRr(activeUpscaler)) {
                lastRrOk = rrDone;
                lastRrRc = RtDlssRr.INSTANCE.lastEvaluateRc();
            } else if (spatialUpscale) {
                lastRrOk = rrDone;
                lastRrRc = rrDone ? 0 : -1;
            } else {
                lastRrOk = true;
                lastRrRc = 0;
            }

            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "display blit");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, displayPlate, rrOutput);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // rrOutput visible to exposure histogram

            // Optional CAS polish after upscale. Classic FSR2 already runs SDK RCAS at the
            // configured strength; applying this second CAS pass amplified residual Monte Carlo
            // grain (0.8 RCAS followed by 0.4 CAS in the reported log) and wasted a display-res
            // compute dispatch. Keep the external pass for non-FSR implementations only.
            if (playablePath && debugView == 0
                    && CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    && rrOutput != null
                    && lastUpscalerPath
                    && !(activeUpscaler instanceof dev.comfyfluffy.caustica.fsr.Fsr2ClassicUpscaler)) {
                try {
                    casSharpen.ensureSized(displayW, displayH);
                    float sharpness = Math.min(0.45f, CausticaConfig.Rt.Upscaler.SHARPNESS.value() * 0.5f);
                    if (casSharpen.dispatchInPlace(stack, cmd, rrOutput, sharpness)) {
                        VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    }
                } catch (Throwable t) {
                    CausticaMod.LOGGER.warn("CAS sharpen threw; continuing without sharpen", t);
                }
            }

            // Auto-exposure meters rrOutput (the post-RR, denoised/converged image), not the raw
            // pre-RR trace: RR has no notion of exposure (DLSS-RR Integration Guide §3.7 — ignore
            // exposure/auto-exposure/sharpness entirely for RR), so this is purely our own metering
            // choice, independent of RR's pipeline placement. Metering the noisy pre-RR buffer made
            // the histogram's log-luminance average biased by Monte-Carlo noise (Jensen's inequality
            // on the concave log()), so the computed exposure drifted with SPP; rrOutput is stable
            // regardless of SPP, keeping exposure consistent.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                exposure.record(ctx, cmd, stack, rrOutput);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // exposure image visible to the display mapper

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                displayPipeline.dispatch(cmd, displayW, displayH, CausticaConfig.Rt.Hdr.enabled(),
                        CausticaConfig.Rt.Hdr.paperWhiteNits(), CausticaConfig.Rt.Hdr.headroom());
            }
            hdrWrittenThisFrame = CausticaConfig.Rt.Hdr.enabled();
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VkImageMemoryBarrier2.Buffer toGeneral = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
                toGeneral.get(0).srcStageMask(0L).srcAccessMask(0L)
                        .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
                toGeneral.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                VkMemoryBarrier2.Buffer displayVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                displayVis.get(0).srcStageMask(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
                VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default()
                        .pImageMemoryBarriers(toGeneral).pMemoryBarriers(displayVis);
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
                VkImageMemoryBarrier2.Buffer toSrc = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
                toSrc.get(0).srcStageMask(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT).dstAccessMask(0L)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
                toSrc.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                VkDependencyInfo postDep = VkDependencyInfo.calloc(stack).sType$Default()
                        .pImageMemoryBarriers(toSrc);
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, postDep);
            }
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work

        // ReSTIR DI: swap reservoir images for next frame's temporal reuse
        if (ENABLE_RESTIR_DI && reservoirImages != null) {
            reservoirImages.swap();
        }
        // ReSTIR GI: swap direction-reservoir images for next frame's temporal reuse.
        if (ENABLE_RESTIR_GI && giReservoirImages != null) {
            giReservoirImages.swap();
        }
    }

    /**
     * Derive the celestial light from Minecraft's time of day and write it into the world push constant
     * (three 16-byte-aligned vec4s at offsets 208/224/240):
     * <ul>
     *   <li>{@code sunDir.xyz} — the true sun direction (for the sky glow/disc), {@code .w} = dayFactor
     *       (0 night .. 1 day), used to cross-fade the sky gradient.</li>
     *   <li>{@code lightDir.xyz} — the active NEE light direction: the sun while it is above the horizon,
     *       otherwise the moon (so surfaces still get soft moonlight at night); {@code .w} = the light's
     *       angular radius in radians.</li>
     *   <li>{@code lightRadiance.xyz} — that light's HDR colour: warm + dim near the horizon, white +
     *       bright when high; dim cool moonlight at night.</li>
     * </ul>
     * Celestial angles come from the camera's {@link EnvironmentAttributeProbe} (partial-tick
     * interpolated). {@code caustica.rt.sunNoonSouthDeg} tilts the east-west arc toward south (+Z) at
     * noon.
     */
    /**
     * Block-breaking overlay: mirrors vanilla's {@code ClientLevel.destructionProgress()} (populated
     * by network packets, independent of the cancelled {@code LevelRenderer.render()} — see
     * [[rt-native-overlay-tier1]]) into the push's {@code breaking[]} list, so {@code world.rchit} can blend
     * the matching destroy-stage crack texture into a hit terrain block's albedo. Each block's own
     * destroy-stage texture ({@code minecraft:textures/block/destroy_stage_N.png}, resolved via
     * {@link ModelBakery#DESTROY_TYPES}) is a standalone {@code Sampler0} texture, not a block-atlas sprite,
     * so it rides the same bindless entity-texture array as entity textures ({@link RtEntityTextures}).
     */
    private void writeBreaking(ByteBuffer push, RtTerrain terrain) {
        int count = 0;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entry : level.destructionProgress().long2ObjectEntrySet()) {
                if (count >= MAX_BREAKING) {
                    break;
                }
                var progresses = entry.getValue();
                if (progresses == null || progresses.isEmpty()) {
                    continue;
                }
                int stage = Mth.clamp(progresses.last().getProgress(), 0, 9);
                BlockPos pos = BlockPos.of(entry.getLongKey());
                int slot = RtEntityTextures.INSTANCE.slotFor(ModelBakery.DESTROY_TYPES.get(stage));
                int off = BREAKING_OFFSET + count * 16;
                push.putInt(off, pos.getX() - terrain.blockX);
                push.putInt(off + 4, pos.getY() - terrain.blockY);
                push.putInt(off + 8, pos.getZ() - terrain.blockZ);
                push.putInt(off + 12, slot);
                count++;
            }
        }
        push.putInt(416, count);
    }

    private void writeSky(ByteBuffer push) {
        float sunX, sunY, sunZ, dayFactor, lx, ly, lz, rr, rg, rb, lightRadius;
        float moonX, moonY, moonZ, moonPhase, starAngle, starBrightness;
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * (float) (Math.PI / 180.0);
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * (float) (Math.PI / 180.0);
        float sunNoon = Mth.cos(sunAngle);
        sunX = -Mth.sin(sunAngle); sunY = sunNoonY() * sunNoon; sunZ = sunNoonZ() * sunNoon;
        float moonNoon = Mth.cos(moonAngle);
        moonX = -Mth.sin(moonAngle); moonY = sunNoonY() * moonNoon; moonZ = sunNoonZ() * moonNoon;
        moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(); // 0 full .. 4 new
        // Stars: use Minecraft's actual celestial rotation + brightness (the same values vanilla's
        // SkyRenderer uses), so the starfield wheels about the celestial pole tied to world time and
        // fades in/out at dusk/dawn exactly like vanilla. STAR_ANGLE is in degrees -> radians.
        starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * (float) (Math.PI / 180.0);
        starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        dayFactor = smoothstep(-0.08f, 0.10f, sunY);
        float[] trans = new float[3];
        if (sunY > -0.05f) {
            // Sun stays the NEE light through the whole sunset: its colour/intensity is the atmosphere's
            // own transmittance (same Rayleigh+Mie+ozone march as the sky shader — see
            // atmosphereTransmittance), so it whitens overhead and reddens+dims into the horizon on
            // exactly the curve the visible sky follows. The old hand-tuned warmth ramp switched to the
            // moon at sunY == 0 while the sun was still at ~16% strength, which read as a hard light pop
            // at sunset/sunrise; transmittance is already near zero at the horizon, and the short
            // smoothstep below carries the remainder to exactly zero before the moon takes over.
            atmosphereTransmittance(sunX, sunY, sunZ, trans);
            float fade = smoothstep(-0.05f, 0.005f, sunY);
            float sunPeak = 21.0f;
            lx = sunX; ly = sunY; lz = sunZ;
            rr = sunPeak * trans[0] * fade;
            rg = sunPeak * trans[1] * fade;
            rb = sunPeak * trans[2] * fade;
            lightRadius = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value();
        } else {
            // Moon: dim cool light, ramping up from zero at the sun→moon handoff (sunY = -0.05, where
            // the sun fade also reaches zero) so the switch is invisible. Scaled by the lit fraction so
            // a new moon gives near-zero moonlight, and tinted by the same transmittance so a low moon
            // is warm amber, silver once high (or zero while it is below the horizon).
            atmosphereTransmittance(moonX, moonY, moonZ, trans);
            float moonStrength = smoothstep(0.04f, 0.22f, -sunY);
            float litFraction = 1.0f - Math.abs(moonPhase - 4.0f) / 4.0f; // 0 new .. 1 full
            float moonPeak = 0.20f * (0.15f + 0.85f * litFraction);
            lx = moonX; ly = moonY; lz = moonZ;
            rr = 0.30f * moonPeak * moonStrength * trans[0];
            rg = 0.36f * moonPeak * moonStrength * trans[1];
            rb = 0.55f * moonPeak * moonStrength * trans[2];
            lightRadius = CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.value();
        }
        push.putFloat(208, sunX); push.putFloat(212, sunY); push.putFloat(216, sunZ); push.putFloat(220, dayFactor);
        push.putFloat(224, lx); push.putFloat(228, ly); push.putFloat(232, lz); push.putFloat(236, lightRadius);
        push.putFloat(240, rr); push.putFloat(244, rg); push.putFloat(248, rb); push.putFloat(252, starBrightness);
        // Sky rewrite: moon direction + phase, celestial axis + star rotation angle (real world time).
        push.putFloat(256, moonX); push.putFloat(260, moonY); push.putFloat(264, moonZ); push.putFloat(268, moonPhase);
        push.putFloat(272, 0f); push.putFloat(276, celestialAxisY()); push.putFloat(280, celestialAxisZ()); push.putFloat(284, starAngle);
        writeCelestialUv(push, moonPhase); // sunUv@288 + moonUv@304 (vanilla celestials-atlas sprite rects)
    }

    /**
     * Push the celestials-atlas UV rects (u0,v0,u1,v1) for the sun sprite and the current moon-phase
     * sprite, so world.rmiss can sample the real vanilla textures on the discs. Atlas-not-ready (early
     * boot / no resources) leaves full-range UVs and the shader's block-atlas fallback covers it.
     */
    private void writeCelestialUv(ByteBuffer push, float moonPhaseIndex) {
        if (celestialUvAtlasHandle == 0L) {
            setCelestialUvAtlas(celestialsAtlasView());
        }
        int phase = Math.clamp((int) moonPhaseIndex, 0, MOON_IDS.length - 1);
        if (phase != celestialUvMoonPhase) {
            refreshCelestialUvCache(phase);
        }
        push.putFloat(288, sunU0); push.putFloat(292, sunV0); push.putFloat(296, sunU1); push.putFloat(300, sunV1);
        push.putFloat(304, moonU0); push.putFloat(308, moonV0); push.putFloat(312, moonU1); push.putFloat(316, moonV1);
    }

    private void setCelestialUvAtlas(long atlasHandle) {
        if (celestialUvAtlasHandle == atlasHandle) {
            return;
        }
        celestialUvAtlasHandle = atlasHandle;
        celestialUvMoonPhase = -1;
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
    }

    private void refreshCelestialUvCache(int moonPhase) {
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
        try {
            if (celestialUvAtlasHandle != 0L) {
                TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
                TextureAtlasSprite sun = atlas.getSprite(SUN_ID);
                sunU0 = sun.getU0(); sunV0 = sun.getV0(); sunU1 = sun.getU1(); sunV1 = sun.getV1();
                TextureAtlasSprite moon = atlas.getSprite(MOON_IDS[moonPhase]);
                moonU0 = moon.getU0(); moonV0 = moon.getV0(); moonU1 = moon.getU1(); moonV1 = moon.getV1();
            }
        } catch (Exception ignored) {
            // celestials atlas not yet loaded — keep full-range UVs (fallback texture is the block atlas)
        }
        celestialUvMoonPhase = moonPhase;
    }

    /** Hermite smoothstep matching GLSL semantics (0 below edge0, 1 above edge1). */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * RGB transmittance from the camera to space along {@code dir} — a verbatim port of
     * {@code world.rmiss}'s {@code transmittanceToSpace} (Rayleigh + Mie + ozone optical depth, 8-step
     * march from 2 km altitude; constants must stay in lock-step with the shader). This is what colours
     * the NEE sun/moonlight: because the sky shader tints its visible discs with the identical function,
     * the light on terrain and the sky's sunset can never disagree. A direction below the geometric
     * horizon accumulates enormous optical depth, so the result rolls to zero smoothly on its own —
     * no explicit planet-shadow test needed.
     */
    private static void atmosphereTransmittance(float dx, float dy, float dz, float[] out) {
        final double planetR = 6371000.0, atmosR = 6471000.0;
        final double[] rayBeta = {5.5e-6, 13.0e-6, 22.4e-6};
        final double mieBeta = 21.0e-6 * 1.1;
        final double[] ozoneBeta = {0.650e-6, 1.881e-6, 0.085e-6};
        final double oy = planetR + 2000.0;
        // Larger root of ray vs atmosphere sphere, origin (0, oy, 0).
        double b = oy * dy;
        double tEnd = -b + Math.sqrt(Math.max(b * b - (oy * oy - atmosR * atmosR), 0.0));
        double seg = tEnd / 8.0;
        double odR = 0.0, odM = 0.0, odO = 0.0;
        for (int i = 0; i < 8; i++) {
            double t = seg * (i + 0.5);
            double px = dx * t, py = oy + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - planetR;
            odR += Math.exp(-h / 8000.0) * seg;
            odM += Math.exp(-h / 1200.0) * seg;
            odO += Math.max(0.0, 1.0 - Math.abs(h - 25000.0) / 15000.0) * seg;
        }
        for (int i = 0; i < 3; i++) {
            out[i] = (float) Math.exp(-(rayBeta[i] * odR + mieBeta * odM + ozoneBeta[i] * odO));
        }
    }

    public void destroy() {
        // Teardown runs after the device is idle (CLIENT_STOPPING waits), so the TLAS ring's slots are no
        // longer in flight and can be freed immediately.
        tlasRing.destroy();
        UpscalerSelector.shutdown();
        if (asyncCompute != null) {
            asyncCompute.destroy();
            asyncCompute = null;
        }
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
            hdrDisplayImage = null;
        }
        if (fgHudlessImage != null) {
            fgHudlessImage.destroy();
            fgHudlessImage = null;
        }
        if (fgHdrHudlessImage != null) {
            fgHdrHudlessImage.destroy();
            fgHdrHudlessImage = null;
        }
        RtWorldOverlay.INSTANCE.destroy(); // overlay features/pipelines/scratch live on the same device lifetime
        if (vrs != null) {
            vrs.destroy();
            vrs = null;
        }
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (denoisedColor != null) {
            denoisedColor.destroy();
            denoisedColor = null;
        }
        if (temporalAccum != null) {
            temporalAccum.destroy();
            temporalAccum = null;
        }
        if (accumulatedColor != null) {
            accumulatedColor.destroy();
            accumulatedColor = null;
        }
        DenoiseBackendSelector.invalidate();
        destroyGuideImages();
        exposure.destroy();
        casSharpen.destroy();
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (hdrCompositePipeline != null) {
            hdrCompositePipeline.destroy();
            hdrCompositePipeline = null;
        }
        if (hdrUiSampler != 0L) {
            RtContext hdrCtx = RtContext.currentOrNull();
            if (hdrCtx != null) {
                VK10.vkDestroySampler(hdrCtx.vk(), hdrUiSampler, null);
            }
            hdrUiSampler = 0L;
        }
        if (sdrPresentPipeline != null) {
            sdrPresentPipeline.destroy();
            sdrPresentPipeline = null;
        }
        if (sdrPresentImage != null) {
            sdrPresentImage.destroy();
            sdrPresentImage = null;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[0];
        fgInterpW = -1;
        fgInterpH = -1;
        fgInterpFormat = Integer.MIN_VALUE;
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        if (pushRing != null) {
            for (RtBuffer b : pushRing) {
                if (b != null) {
                    b.destroy();
                }
            }
            pushRing = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }

    private static long blockAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    /** Whether the HDR present path (HDR image + combined UI -> PQ swapchain) should replace the vanilla SDR blit. */
    public boolean isHdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && hdrWrittenThisFrame
                && hdrDisplayImage != null;
    }

    /**
     * DLSS-FG: the PQ-encoded HDR backbuffer (view/image), valid only right after {@link #presentHdr} has run
     * this frame (it's the same image {@code presentHdr} just composited UI into and blitted to the
     * swapchain) — used as the interpolation source for HDR frame generation instead of the SDR main target.
     * Already display-ready PQ, so it's fed to DLSSG directly with no extra encode step. 0 if HDR isn't
     * active this frame.
     */
    public long hdrBackbufferView() {
        return hdrDisplayImage != null ? hdrDisplayImage.view : 0L;
    }

    public long hdrBackbufferImage() {
        return hdrDisplayImage != null ? hdrDisplayImage.image : 0L;
    }

    /**
     * Blit this frame's PQ-encoded HDR image straight into the swapchain image, replacing Minecraft's SDR
     * blit. Replicates {@code VulkanGpuSurface.blitFromTexture}'s barrier + acquire-wait/present-signal
     * sequence with the HDR {@link RtImage} as the (GENERAL-layout) source; an added memory barrier makes the
     * display-compute writes visible to the blit read. The SDR main target is bypassed; the combined UI image
     * is blended over the HDR image here at paper white before the swapchain blit. The magic stage/access
     * values mirror vanilla {@code blitFromTexture} exactly. Y is flipped to match the vanilla swapchain blit.
     */
    public void presentHdr(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH, long acquireSem, long presentSem) {
        RtImage src = hdrDisplayImage;
        int copyW = Math.min(swapW, src.width);
        int copyH = Math.min(swapH, src.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // DLSS-FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer.
            if (RtDlssFg.enabled()) {
                captureFgHdrHudless(cmd, stack, src);
            }

            // Step C.2: composite the combined UI overlay over the HDR world image (in place) at paper white,
            // before the swapchain blit. The overlay is an MC render target kept in GENERAL layout, sampled by
            // the compute pass. A memory barrier first makes the overlay writes + the world HDR writes visible
            // to the compute; the dep1 barrier below (ALL writes -> transfer read) then covers the compute's
            // HDR write for the blit.
            long overlayView = RtUiOverlay.populatedThisFrame() ? RtUiOverlay.overlayColorView() : 0L;
            if (overlayView != 0L) {
                ensureHdrUiResources();
                if (hdrCompositePipeline != null) {
                    VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
                    VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                    hdrCompositePipeline.setImages(hdrDisplayImage.view, overlayView, hdrUiSampler);
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, CausticaConfig.Rt.Hdr.paperWhiteNits());
                }
                RtUiOverlay.markConsumed();
            }
            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit HDR (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
    }

    /** Lazily create the HDR UI-composite compute pipeline + its nearest/clamp sampler (first HDR present). */
    private void ensureHdrUiResources() {
        if (hdrCompositePipeline != null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return;
        }
        hdrCompositePipeline = RtHdrCompositePipeline.create(ctx);
    }

    /** Ensure the shared nearest/clamp sampler used to sample SDR/overlay targets in the present compute. */
    private boolean ensureUiSampler(RtContext ctx) {
        if (hdrUiSampler != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                return false;
            }
            hdrUiSampler = p.get(0);
        }
        return true;
    }

    /**
     * Whether a non-RT frame (menu, title panorama, loading screen) should be SDR-&gt;PQ converted for
     * present instead of vanilla's raw SDR blit. True when the PQ swapchain is active but this frame did
     * not produce an HDR image ({@link #isHdrPresentActive()} false).
     */
    public boolean isPqSdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && !isHdrPresentActive();
    }

    /**
     * Present a non-RT (menu/loading) frame to the PQ swapchain: convert the SDR main target (sRGB-encoded
     * rgba8, GENERAL layout, already holding the composited panorama + UI) to PQ-encoded at paper white via
     * a compute pass into {@link #sdrPresentImage}, then blit that into the swapchain. Mirrors
     * {@link #presentHdr} barrier-for-barrier; returns false (keep vanilla SDR blit) if resources are
     * unavailable.
     */
    public boolean presentSdrToPq(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH,
            long sdrMainView, long acquireSem, long presentSem) {
        if (sdrMainView == 0L || failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return false;
        }
        if (sdrPresentPipeline == null) {
            sdrPresentPipeline = RtSdrPresentPipeline.create(ctx);
        }
        if (sdrPresentImage == null || sdrPresentImage.width != swapW || sdrPresentImage.height != swapH) {
            if (sdrPresentImage != null) {
                sdrPresentImage.destroy();
            }
            sdrPresentImage = ctx.createStorageImage(swapW, swapH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapW + "x" + swapH);
        }
        RtImage dst = sdrPresentImage;
        int copyW = Math.min(swapW, dst.width);
        int copyH = Math.min(swapH, dst.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // Make the prior GUI/overlay writes to the SDR main target visible to the compute sample.
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            sdrPresentPipeline.setImages(dst.view, sdrMainView, hdrUiSampler);
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, CausticaConfig.Rt.Hdr.paperWhiteNits());

            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the compute write visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit converted PQ image (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
        return true;
    }

    /**
     * Linear-filtered blit of the full render-res image into the full display-res image. Used as the
     * non-RR / fallback upscale so display mapping always sees a display-res RT image; a no-op stretch when
     * the two are the same size (RR disabled -> render == display).
     */
    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }

    /**
     * DLSS Frame Generation quality: capture a copy of {@code main} (the main render target) into
     * {@link #fgHudlessImage} for {@link #fgInterpolate} to feed DLSSG as the "hudless" resource. Call from
     * {@code GameRendererMixin} right after {@code GuiRenderer.render()} but BEFORE
     * {@link RtUiOverlay#compositeIfUsed()} — at that point, when the UI overlay redirect is active, {@code
     * main} still has no combined UI baked in (world overlays, hand/screen effects and GUI went to the
     * overlay target instead). No-op (and {@link #fgInterpolate} passes 0/0/0 for hudless, same as always)
     * unless both FG and the UI overlay redirect are active — capturing this without the redirect would just
     * copy the ALREADY-composited backbuffer, which is useless as a distinct hudless input.
     */
    public void captureFgHudless(RenderTarget main) {
        if (!RtDlssFg.enabled() || !RtUiOverlay.enabled() || main == null || main.getColorTexture() == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        long srcImage;
        try {
            srcImage = vkImage(main.getColorTexture());
        } catch (IllegalStateException e) {
            return; // not a Vulkan-backed texture (shouldn't happen on this backend)
        }
        if (fgHudlessImage == null || fgHudlessImage.width != main.width || fgHudlessImage.height != main.height) {
            if (fgHudlessImage != null) {
                fgHudlessImage.destroy();
            }
            fgHudlessImage = ctx.createStorageImage(main.width, main.height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                    "FG hudless capture " + main.width + "x" + main.height);
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make writes into `main` visible to the copy (the combined UI has not touched `main` yet this
            // frame — it went to the UI overlay target instead).
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            VK10.vkCmdCopyImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    fgHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, main.width, main.height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        encoder.execute(cmd);
    }

    /**
     * HDR counterpart of {@link #captureFgHudless} — copies {@code src} (this frame's {@code hdrDisplayImage},
     * before the combined UI overlay is blended in) into {@link #fgHdrHudlessImage} for {@link
     * #fgInterpolate}'s HDR path to feed DLSSG as the "hudless" resource. A plain copy, not a format
     * conversion: both images are
     * already PQ-encoded (the display-ready EOTF-encoded [0,1] signal DLSS-FG's programming guide requires),
     * so no encode step is needed. Called from {@link #presentHdr} using its already-open {@code cmd}/
     * {@code stack}, right before that method's own combined-UI composite dispatch overwrites
     * {@code hdrDisplayImage} in place — same "capture before the UI gets baked back in" timing as the SDR
     * version, just within a single method instead of split across a mixin hook.
     */
    private void captureFgHdrHudless(VkCommandBuffer cmd, MemoryStack stack, RtImage src) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        if (fgHdrHudlessImage == null || fgHdrHudlessImage.width != src.width || fgHdrHudlessImage.height != src.height) {
            if (fgHdrHudlessImage != null) {
                fgHdrHudlessImage.destroy();
            }
            fgHdrHudlessImage = ctx.createStorageImage(src.width, src.height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + src.width + "x" + src.height);
        }
        // Make composite()'s writes to hdrDisplayImage (an earlier submit this frame) visible to this copy;
        // the copy's write is then made visible to the UI-composite dispatch that follows (and to DLSSG's
        // read, in a later command buffer) by the same idiom.
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                fgHdrHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, src.width, src.height));
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    /**
     * DLSS Frame Generation: record the DLSSG evaluate for generated frame {@code index} of {@code count}
     * (backbuffer = the final frame; HW depth = {@code gDepth}; motion = {@code gMotion}) into Minecraft's
     * command encoder, returning the interpolated output image (backbuffer size) for {@link RtFramePresenter}
     * to blit into a generated swapchain image. On {@code index == 1} it ensures the feature (created in its
     * own synchronous submit), the per-index output images, and the jitter-free reprojection matrices.
     * Returns {@code null} (caller falls back to duplicating the real frame for this one frame, no session
     * impact) when there's simply no captured RT frame to interpolate from right now — routine and expected
     * on menu/loading/transition frames, since {@link RtFramePresenter#isActive} only gates on being in a
     * world, not on RT having actually produced a frame this tick. Throws instead for failures that should
     * never happen once RT is actively producing frames (DLSSG feature creation failing, an out-of-range
     * index, the evaluate itself failing) — the caller treats those as fatal and disables FG for the
     * session, same as any other FG present-record failure, rather than silently degrading to duplicated
     * (non-interpolated) frames forever with no visible sign anything is wrong. Rotation-only matrices;
     * camera translation is carried by the mvecs (cameraMotionIncluded).
     *
     * <p>{@code hdrBackbuffer} selects the HDR path. Per the DLSS-FG programming guide's HDR section, scRGB is
     * explicitly unsupported as a DLSS-FG input ("not suitable as inputs to DLSS-FG" — it wants a
     * display-ready, EOTF-encoded [0,1] signal, recommending HDR10/ST.2084) — since the renderer's whole HDR
     * pipeline is natively PQ-encoded, every image fed to {@code RtDlssFg.evaluate} in HDR mode is already in
     * that format with no extra conversion needed: the backbuffer is the raw {@code backbufferView}/
     * {@code backbufferImage} the caller passed in ({@link #hdrBackbufferView()}, already PQ + UI-composited
     * by {@link #presentHdr}); the hudless resource is {@link #fgHdrHudlessImage} (copied by {@link
     * #presentHdr} <em>before</em> its own UI composite ran, mirroring {@link #captureFgHudless}'s pre-UI
     * timing); and DLSSG's own (also PQ-encoded) output is returned as-is, since the swapchain itself is
     * PQ-native and can blit it directly. The UI resource itself needs no HDR-specific handling — it's the
     * same combined {@link RtUiOverlay} texture used by both present paths (only the *compositing* math that
     * consumes it differs, done separately by {@code presentHdr}/{@code RtUiOverlay}, not here).
     */
    public RtImage fgInterpolate(VulkanCommandEncoder enc, long backbufferView, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer) {
        if (failed || gDepth == null || gMotion == null || !frameCaptured) {
            return null;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        final int fmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (index == 1) {
            if (!ensureFgFeature(ctx, swapW, swapH, renderW, renderH, fmt)) {
                throw new IllegalStateException("DLSSG feature not ready (ensureFgFeature failed)");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            // clipToPrevClip = prevVP * inverse(curVP); prevClipToClip = curVP * inverse(prevVP). Both from
            // the (rotation-only, camera-relative) MV view-projections, so jitter-free.
            fgMatTmp.set(mvCurProjView).invert();
            fgClipToPrev.set(mvPrevProjView).mul(fgMatTmp);
            fgMatTmp.set(mvPrevProjView).invert();
            fgPrevToClip.set(mvCurProjView).mul(fgMatTmp);
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        RtImage out = fgInterp[index - 1];
        // Only feed hudless/ui when they exist AND match this frame's backbuffer size — a stale or mismatched
        // size (e.g. mid-resize) is worse than skipping, so fall back to 0/0/0 (DLSSG just does without).
        RtImage hudlessSrc = hdrBackbuffer ? fgHdrHudlessImage : fgHudlessImage;
        boolean hudlessReady = hudlessSrc != null && hudlessSrc.width == swapW && hudlessSrc.height == swapH;
        long hudlessView = hudlessReady ? hudlessSrc.view : 0L;
        long hudlessImg = hudlessReady ? hudlessSrc.image : 0L;
        int hudlessFmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        boolean uiReady = RtUiOverlay.overlayWidth() == swapW && RtUiOverlay.overlayHeight() == swapH
                && RtUiOverlay.overlayColorView() != 0L && RtUiOverlay.overlayColorImage() != 0L;
        long uiView = uiReady ? RtUiOverlay.overlayColorView() : 0L;
        long uiImg = uiReady ? RtUiOverlay.overlayColorImage() : 0L;

        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        // Active FrameGen. Currently only the noop stub ships in this build (TAAU-only refactor);
        // the DLSSG direct call through RtDlssFg below stays compiled but is unreachable while
        // FrameGenSelector resolves to NOOP. The duplicate-frame blit is the fallthrough when
        // fg.interpolate(...) returns false.
        dev.comfyfluffy.caustica.framegen.FrameGen fg = dev.comfyfluffy.caustica.framegen.FrameGenSelector.current();
        boolean ok = false;
        if (fg.isAvailable() && fg.sourceMode() == dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode.DLSS_RR) {
            ok = RtDlssFg.INSTANCE.evaluate(cmd.address(),
                    backbufferView, backbufferImage, fmt,
                    gDepth.view, gDepth.image, VK10.VK_FORMAT_R32_SFLOAT,
                    gMotion.view, gMotion.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    hudlessView, hudlessImg, hudlessReady ? hudlessFmt : 0,
                    uiView, uiImg, uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                    out.view, out.image, fmt,
                    swapW, swapH, renderW, renderH, count, index, 1.0f, 1.0f,
                    true /* depthInverted (reversed-Z) */, hdrBackbuffer /* colorBuffersHDR */,
                    true /* cameraMotionIncluded (in mvecs) */, fgReset,
                    fgClipToPrev, fgPrevToClip);
            if (!ok) {
                throw new IllegalStateException("ngxshim_evaluate_dlssg failed (RtDlssFg.evaluate returned false)");
            }
        } else if (fg.isAvailable() && fg.isReady()) {
            // FSR / XeSS frame gen go through the abstract FrameGen.interpolate(...) call.
            ok = fg.interpolate(cmd.address(),
                    backbufferView, backbufferImage, fmt,
                    gDepth.view, gDepth.image, VK10.VK_FORMAT_R32_SFLOAT,
                    gMotion.view, gMotion.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    hudlessView, hudlessImg, hudlessReady ? hudlessFmt : 0,
                    uiView, uiImg, uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                    out.view, out.image, fmt,
                    backbufferView, backbufferImage, fmt, // prevColor = current backbuffer (single-frame history)
                    swapW, swapH, index, count, hdrBackbuffer,
                    mvCurProjView, mvCurProjView, fgClipToPrev, fgPrevToClip);
            // Adapter failure (e.g. backend stub): fall through to the duplicate-frame blit below.
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg interpolate) failed");
        }
        fgReset = false;
        enc.execute(cmd);
        // If the active FrameGen was a no-op (mode == OFF, or the backend stubbed interpolate),
        // return null so the caller falls back to a duplicate-frame blit. The DlssFg path always
        // returns a non-null RtImage (or throws) above.
        if (ok) {
            return out;
        }
        return null;
    }

    private boolean ensureFgFeature(RtContext ctx, int w, int h, int rw, int rh, int fmt) {
        if (RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt)) {
            return true;
        }
        // Create the feature in its own submit + wait (not folded into MC's frame submit).
        ctx.submitSync(c -> RtDlssFg.INSTANCE.ensureFeature(c.address(), w, h, rw, rh, fmt));
        fgReset = true; // fresh feature has no temporal history
        return RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt);
    }

    private void ensureFgInterp(RtContext ctx, int count, int w, int h, int fmt) {
        if (fgInterp.length == count && fgInterpW == w && fgInterpH == h && fgInterpFormat == fmt
                && (count == 0 || fgInterp[0] != null)) {
            return;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[count];
        for (int i = 0; i < count; i++) {
            fgInterp[i] = ctx.createStorageImage(w, h, fmt, "FG interp " + i + " " + w + "x" + h);
        }
        fgInterpW = w;
        fgInterpH = h;
        fgInterpFormat = fmt;
    }
}
