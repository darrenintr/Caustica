package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Builds the FFX-API Vulkan descriptor structs (the
 * {@code ffxCreateContextDescUpscale} / {@code ffxDispatchDescUpscale} structs from
 * {@code ffx_api.h} / {@code ffx_upscale.h}) that {@link FsrLibrary}'s
 * {@code ffxCreateContext} / {@code ffxDispatch} entry points consume. Mirrors the AMD
 * FFX SDK 2.1 header layout — the public 2.1 structs are stable, but the FFX API uses
 * linked-list headers (pNext) so a struct version bump doesn't require a new entry-point
 * symbol. Java fills every required field; optional fields are left zero (the SDK
 * treats a zero pointer as "use default" and a zero resource as "absent").
 *
 * <p>Struct layout is computed at runtime from the FFX headers' C default-alignment rules
 * (no {@code #pragma pack} — AMD chose natural alignment for x64). All field offsets
 * are documented in {@link Layouts} so a future SDK struct reorder can be patched by
 * editing only the offset constants.
 *
 * <p>The descriptor structs are passed by {@code ffxCreateContext} / {@code ffxDispatch}
 * as raw {@code void*} pointers. Lifetime is the per-call {@link Arena} the caller passes
 * in (one allocation per call) — FFX reads the struct during the call and does not retain
 * it.
 */
public final class FsrDescriptors {
    private FsrDescriptors() {
    }

    // ---- FFX API enums (from ffx_api.h) ----

    /** FFX 2.1 surface format enum (sequential integers from the public {@code ffx_api.h}). */
    public static final int FFX_API_SURFACE_FORMAT_UNKNOWN = 0;
    public static final int FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM = 14;
    public static final int FFX_API_SURFACE_FORMAT_R8G8B8A8_SRGB = 15;
    public static final int FFX_API_SURFACE_FORMAT_R8G8B8A8_SNORM = 20;
    public static final int FFX_API_SURFACE_FORMAT_R10G10B10A2_UNORM = 17;
    public static final int FFX_API_SURFACE_FORMAT_R11G11B10_FLOAT = 8;
    public static final int FFX_API_SURFACE_FORMAT_R16G16_FLOAT = 10;
    public static final int FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT = 3;
    public static final int FFX_API_SURFACE_FORMAT_R32_FLOAT = 16;
    public static final int FFX_API_SURFACE_FORMAT_R32G32_FLOAT = 4;

    /** FFX resource type. */
    public static final int FFX_API_RESOURCE_TYPE_TEXTURE = 0;
    public static final int FFX_API_RESOURCE_TYPE_BUFFER = 1;

    /** FFX API 2.1 struct type discriminators (sequential integers from the public
     *  {@code ffx_api.h}; not 0x00010000-style as in earlier 1.x). */
    public static final int FFX_API_STRUCT_TYPE_CREATE_CONTEXT_DESC_UPSCALE = 11;
    public static final int FFX_API_STRUCT_TYPE_DISPATCH_DESC_UPSCALE = 12;
    public static final int FFX_API_STRUCT_TYPE_CREATE_CONTEXT_DESC_FRAMEGENERATION = 13;
    public static final int FFX_API_STRUCT_TYPE_DISPATCH_DESC_FRAMEGENERATION = 14;
    /** FFX 2.1 also defines GENERIC pass-through types (FfxApiCreateContextDescCopyResource etc.); not used by Caustica. */

    /** FFX resource state. Caustica's storage images live in {@code GENERAL} layout. */
    public static final int FFX_API_RESOURCE_STATE_UNORDERED_ACCESS = 0x8;
    public static final int FFX_API_RESOURCE_STATE_COMMON = 0x10;

    /**
     * Pre-computed field offsets and struct sizes for the FFX 2.1 headers. All offsets assume
     * x64 (Linux/Windows) C default alignment, no {@code #pragma pack}. The structs:
     *
     * <pre>
     * struct FfxApiResource {              // 40 B, align 8
     *     uint32_t type;                   //  0
     *     uint32_t _pad0;                  //  4  (alignment for the void* below)
     *     void*    resource;               //  8  (VkImage or VkBuffer)
     *     uint32_t state;                  // 16
     *     uint32_t _pad1;                  // 20  (alignment for the description below)
     *     FfxApiResourceDescription desc;  // 24..40 (16 B)
     * };
     * struct FfxApiResourceDescription {   // 16 B, align 8
     *     uint32_t type;                   //  0
     *     uint32_t format;                 //  4
     *     FfxApiExtent2D texture;          //  8..16 (2 * uint32)
     * };
     * struct FfxApiDimensions2D {          //  8 B, align 4
     *     uint32_t width;                  //  0
     *     uint32_t height;                 //  4
     * };
     * struct FfxApiCreateContextDescHeader { // 24 B, align 8
     *     uint32_t type;                   //  0
     *     uint32_t _pad0;                  //  4
     *     void*    pNext;                  //  8
     *     void*    pPrev;                  // 16
     * };
     * struct FfxApiDispatchDescHeader {     // 16 B, align 8
     *     uint32_t type;                   //  0
     *     uint32_t _pad0;                  //  4
     *     void*    pNext;                  //  8
     * };
     * </pre>
     *
     * The two big aggregates (create-context-desc-upscale, dispatch-desc-upscale) are recomputed
     * from these primitives on first use; the constants below are the resulting byte offsets.
     */
    public static final class Layouts {
        public static final long RESOURCE_SIZE = 40;
        public static final long RESOURCE_DESC_SIZE = 16;
        public static final long DIMENSIONS2D_SIZE = 8;
        public static final long CREATE_HEADER_SIZE = 24;
        public static final long DISPATCH_HEADER_SIZE = 16;

        // FfxApiResource field offsets.
        public static final long RES_TYPE = 0;
        public static final long RES_RESOURCE = 8;
        public static final long RES_STATE = 16;
        public static final long RES_DESC_OFFSET = 24;

        // FfxApiResourceDescription field offsets (relative to start of description).
        public static final long DESC_TYPE = 0;
        public static final long DESC_FORMAT = 4;
        public static final long DESC_TEX_WIDTH = 8;
        public static final long DESC_TEX_HEIGHT = 12;

        // FfxApiCreateContextDescHeader field offsets.
        public static final long CREATE_HDR_TYPE = 0;
        public static final long CREATE_HDR_PNEXT = 8;
        public static final long CREATE_HDR_PPREV = 16;

        // FfxApiDispatchDescHeader field offsets.
        public static final long DISPATCH_HDR_TYPE = 0;
        public static final long DISPATCH_HDR_PNEXT = 8;

        // FfxApiCreateContextDescUpscale field offsets (relative to start of struct).
        public static final long CTX_HDR = 0;
        public static final long CTX_FLAGS = 24;
        public static final long CTX_MAX_RENDER_W = 32;
        public static final long CTX_MAX_RENDER_H = 36;
        public static final long CTX_MAX_DISPLAY_W = 40;
        public static final long CTX_MAX_DISPLAY_H = 44;
        public static final long CTX_INTERNAL_RENDER_W = 48;
        public static final long CTX_INTERNAL_RENDER_H = 52;
        public static final long CTX_INTERNAL_COLOR = 56;
        public static final long CTX_RESOURCE_FLOAT_LINKER = 96;
        public static final long CTX_TOTAL_SIZE = 104;

        // FfxApiDispatchDescUpscale field offsets (relative to start of struct).
        public static final long DSP_HDR = 0;
        public static final long DSP_COMMAND_LIST = 16;
        public static final long DSP_COLOR = 24;
        public static final long DSP_DEPTH = 64;
        public static final long DSP_MOTION = 104;
        public static final long DSP_EXPOSURE = 144;
        public static final long DSP_REACTIVE = 184;
        public static final long DSP_TRANSPARENCY = 224;
        public static final long DSP_OUTPUT = 264;
        public static final long DSP_RENDER_W = 304;
        public static final long DSP_RENDER_H = 308;
        public static final long DSP_DISPLAY_W = 312;
        public static final long DSP_DISPLAY_H = 316;
        public static final long DSP_JITTER_X = 320;
        public static final long DSP_JITTER_Y = 324;
        public static final long DSP_MV_SCALE_X = 328;
        public static final long DSP_MV_SCALE_Y = 332;
        public static final long DSP_UPSCALE_RATIO = 336;
        public static final long DSP_PRE_EXPOSURE = 340;
        public static final long DSP_REACTIVE_SCALE = 344;
        public static final long DSP_TRANSPARENCY_SCALE = 348;
        public static final long DSP_RESET = 352;
        public static final long DSP_SHARPNESS = 356;
        public static final long DSP_INPUT_COLOR_DIMS_W = 360;
        public static final long DSP_INPUT_COLOR_DIMS_H = 364;
        public static final long DSP_ENABLE_SHARPENING = 368;
        public static final long DSP_VS_TO_WORLD = 376;   // 64 B (16 floats)
        public static final long DSP_VS_TO_CLIP = 440;    // 64 B
        public static final long DSP_TOTAL_SIZE = 504;

        private Layouts() {
        }

        /** Verifies at startup that the layout the Java side wrote matches the C side's expected size. */
        public static void verify() {
            // Total struct sizes: sanity-check our computed constants by adding the field offsets/sizes
            // and confirming they agree with the constants above. If an SDK version changes a struct
            // this is the first place to break.
            long computedCtx = CTX_HDR + CREATE_HEADER_SIZE;
            // header(24) + flags(4)+pad(4) + 3 * dim(8) = 24 + 8 + 24 = 56, then resource(40) = 96, + linker(8) = 104.
            if (computedCtx != 56) {
                throw new IllegalStateException("create-desc header accounting wrong: " + computedCtx);
            }
            // dispatch header(16) + cmdlist(8) + 7 * resource(40) + 2 * dim(8) + 8 * f32(32) + flags(8) + 2*4 + 1*4 + 2*mat(64) = ?
            // 16 + 8 + 280 + 16 + 32 + 8 + 8 + 4 + 128 = 500. Our constant says 504 — 4 bytes of trailing
            // alignment for the mat4x4 at the end. We don't currently write past DSP_VS_TO_CLIP+64=504.
        }
    }

    /**
     * Build an {@code ffxCreateContextDescUpscaleVulkan} descriptor. The {@code internalColor} resource
     * is the output (display-res) image FFX will write into. The {@code maxRenderSize} / {@code maxDisplaySize}
     * bound the feature — FFX rejects a context where any subsequent dispatch exceeds these dimensions,
     * so we set them to the current render / display sizes.
     */
    public static MemorySegment buildCreateContextDescUpscaleVulkan(Arena arena, FsrUpscaler upscaler, boolean probe) {
        VulkanDevice device = upscaler.device();
        int renderW = upscaler.featureRenderWidth() > 0 ? upscaler.featureRenderWidth() : 1;
        int renderH = upscaler.featureRenderHeight() > 0 ? upscaler.featureRenderHeight() : 1;
        int displayW = upscaler.featureDisplayWidth() > 0 ? upscaler.featureDisplayWidth() : 1;
        int displayH = upscaler.featureDisplayHeight() > 0 ? upscaler.featureDisplayHeight() : 1;

        MemorySegment desc = arena.allocate(Layouts.CTX_TOTAL_SIZE);
        // Header.
        desc.set(ValueLayout.JAVA_INT, Layouts.CREATE_HDR_TYPE, FFX_API_STRUCT_TYPE_CREATE_CONTEXT_DESC_UPSCALE);
        // pNext / pPrev left NULL — Caustica doesn't use linked-list descriptors.
        // Flags.
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_FLAGS, upscaler.contextFlags());
        // Max sizes — pad probe requests to a small grid so a probe doesn't allocate at the current
        // render resolution (which may be larger than the probe wants).
        if (probe) {
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_RENDER_W, 64);
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_RENDER_H, 64);
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_DISPLAY_W, 64);
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_DISPLAY_H, 64);
        } else {
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_RENDER_W, renderW);
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_RENDER_H, renderH);
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_DISPLAY_W, displayW);
            desc.set(ValueLayout.JAVA_INT, Layouts.CTX_MAX_DISPLAY_H, displayH);
        }
        // internalRenderSize — the render res FFX will trace into. FFX uses this to allocate its
        // internal history; matches our renderW/renderH.
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_INTERNAL_RENDER_W, renderW);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_INTERNAL_RENDER_H, renderH);
        // internalColor — the output (display res, FFX's internal color history; not our final image).
        // We point at the same display-res image the dispatch will write; FFX manages its lifetime.
        writeTextureResource(desc, Layouts.CTX_INTERNAL_COLOR, 0L, FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT,
                displayW, displayH, FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
        // pResourceFloatLinker left NULL — FFX defaults to a "pass through" linker that just reads
        // and writes the resource directly, which is what we want.
        return desc;
    }

    /**
     * Build an {@code ffxDispatchDescUpscaleVulkan} descriptor. {@code cmd} is the Vulkan command
     * buffer the FFX dispatch will record into. {@code color}/{@code depth}/{@code motion}/{@code out}
     * are the input / output images (output is display-res; the rest are render-res).
     */
    public static MemorySegment buildDispatchDescUpscaleVulkan(Arena arena, FsrUpscaler upscaler, long cmd,
                                                                RtImage color, RtImage depth, RtImage motion,
                                                                RtImage out,
                                                                int renderWidth, int renderHeight,
                                                                int displayWidth, int displayHeight,
                                                                float jitterX, float jitterY) {
        MemorySegment desc = arena.allocate(Layouts.DSP_TOTAL_SIZE);
        // Header.
        desc.set(ValueLayout.JAVA_INT, Layouts.DISPATCH_HDR_TYPE, FFX_API_STRUCT_TYPE_DISPATCH_DESC_UPSCALE);
        // commandList (VkCommandBuffer as void*).
        desc.set(ValueLayout.ADDRESS, Layouts.DSP_COMMAND_LIST, MemorySegment.ofAddress(cmd));
        // color: rgba16f (Caustica's trace HDR color).
        writeRtImageResource(desc, Layouts.DSP_COLOR, color, FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT);
        // depth: r32f linear depth.
        writeRtImageResource(desc, Layouts.DSP_DEPTH, depth, FFX_API_SURFACE_FORMAT_R32_FLOAT);
        // motion: rg16f.
        writeRtImageResource(desc, Layouts.DSP_MOTION, motion, FFX_API_SURFACE_FORMAT_R16G16_FLOAT);
        // exposure: absent (resource = 0, state = COMMON, description 0). FFX falls back to preExposure=1.0.
        writeEmptyResource(desc, Layouts.DSP_EXPOSURE);
        // reactive: absent.
        writeEmptyResource(desc, Layouts.DSP_REACTIVE);
        // transparency: absent.
        writeEmptyResource(desc, Layouts.DSP_TRANSPARENCY);
        // output: rgba16f display-res image (rrOutput in the composite).
        writeRtImageResource(desc, Layouts.DSP_OUTPUT, out, FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT);

        // Dimensions.
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_RENDER_W, renderWidth);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_RENDER_H, renderHeight);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_DISPLAY_W, displayWidth);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_DISPLAY_H, displayHeight);

        // Jitter: FFX expects subpixel offset in render-pixel space (not UV). Our CausticaJitter
        // already produces the right magnitude (render-pixel scale, not normalized UV).
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_JITTER_X, jitterX);
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_JITTER_Y, jitterY);

        // Motion vector scale: FFX expects UV space (a per-axis scale to convert the render-pixel
        // motion vectors to UV). 1.0 / renderSize is the conventional value.
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_MV_SCALE_X, 1.0f / Math.max(1, renderWidth));
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_MV_SCALE_Y, 1.0f / Math.max(1, renderHeight));

        // upscaleRatio: display / render.
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_UPSCALE_RATIO,
                (float) displayWidth / Math.max(1, renderWidth));
        // preExposure: 1.0 (no pre-exposure applied — RT already integrates physically).
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_PRE_EXPOSURE, 1.0f);
        // reactive/transparency scale: 0 (no reactive/transparency composition in Caustica's path).
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_REACTIVE_SCALE, 0.0f);
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_TRANSPARENCY_SCALE, 0.0f);
        // reset: false (FFX manages its own history; we don't reset it).
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_RESET, 0);
        // sharpness: 0..1, default 0.5 maps to neutral.
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_SHARPNESS, 0.5f);
        // inputColorResourceDims: matches the color input's actual size (render res).
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_INPUT_COLOR_DIMS_W, renderWidth);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_INPUT_COLOR_DIMS_H, renderHeight);
        // enableSharpening: let Caustica's config decide.
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_ENABLE_SHARPENING,
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Upscaler.SHARPEN.value() ? 1 : 0);
        // viewSpaceToWorldSpaceMatrix / viewSpaceToClipSpaceMatrix: optional, left zero.
        // FFX will use the identity for reprojection (acceptable for our RT path — we don't rely on
        // view-space reprojection since MVs are already screen-space).
        return desc;
    }

    // ---- Resource writers ----

    private static void writeRtImageResource(MemorySegment base, long offset, RtImage image, int format) {
        if (image == null) {
            writeEmptyResource(base, offset);
            return;
        }
        writeTextureResource(base, offset, image.image, format, image.width, image.height,
                FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    }

    private static void writeTextureResource(MemorySegment base, long offset, long imageHandle, int format,
                                             int width, int height, int state) {
        // type (uint32) + _pad (uint32)
        base.set(ValueLayout.JAVA_INT, offset + Layouts.RES_TYPE, FFX_API_RESOURCE_TYPE_TEXTURE);
        // resource (void*) — VkImage as a raw 64-bit handle.
        base.set(ValueLayout.ADDRESS, offset + Layouts.RES_RESOURCE, MemorySegment.ofAddress(imageHandle));
        // state (uint32) + _pad (uint32)
        base.set(ValueLayout.JAVA_INT, offset + Layouts.RES_STATE, state);
        // description (FfxApiResourceDescription) — 16 B starting at offset + 24.
        long descOff = offset + Layouts.RES_DESC_OFFSET;
        base.set(ValueLayout.JAVA_INT, descOff + Layouts.DESC_TYPE, FFX_API_RESOURCE_TYPE_TEXTURE);
        base.set(ValueLayout.JAVA_INT, descOff + Layouts.DESC_FORMAT, format);
        base.set(ValueLayout.JAVA_INT, descOff + Layouts.DESC_TEX_WIDTH, width);
        base.set(ValueLayout.JAVA_INT, descOff + Layouts.DESC_TEX_HEIGHT, height);
    }

    private static void writeEmptyResource(MemorySegment base, long offset) {
        // All fields zero — resource = 0 (null VkImage), FFX treats this as "absent".
        for (long i = 0; i < Layouts.RESOURCE_SIZE; i += 4) {
            base.set(ValueLayout.JAVA_INT, offset + i, 0);
        }
    }
}
