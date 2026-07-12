package dev.comfyfluffy.caustica.fsr;

import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Builds the FFX API 2.1 frame-generation descriptor structs
 * ({@code ffxCreateContextDescFrameGenerationVulkan} and
 * {@code ffxDispatchDescFrameGenerationVulkan}) consumed by
 * {@code ffxCreateContext} / {@code ffxDispatch} for the FSR Frame Generation
 * feature.
 *
 * <p>Struct layout is from the public FFX 2.1 header
 * {@code ffx_framegeneration.h}. FFX reserves trailing fields for future SDK
 * versions; we allocate a buffer that comfortably covers the 2.1 struct (with
 * room for the 2 FFX-generated output colors + optional HUDLess variants) and
 * only write the fields Caustica actually uses. The buffer is passed by
 * reference; FFX reads it during the call and does not retain it.
 *
 * <p>Like {@link FsrDescriptors}, the offsets are documented in
 * {@link Layouts} so a future SDK struct reorder can be patched by editing only
 * the offset constants.
 */
public final class FsrFgDescriptors {
    private FsrFgDescriptors() {
    }

    /** Reuse the same surface-format / resource-type constants from {@link FsrDescriptors}. */
    public static final int FFX_API_SURFACE_FORMAT_UNKNOWN = FsrDescriptors.FFX_API_SURFACE_FORMAT_UNKNOWN;
    public static final int FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM = FsrDescriptors.FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM;
    public static final int FFX_API_SURFACE_FORMAT_R8G8B8A8_SRGB = FsrDescriptors.FFX_API_SURFACE_FORMAT_R8G8B8A8_SRGB;
    public static final int FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT = FsrDescriptors.FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT;
    public static final int FFX_API_SURFACE_FORMAT_R16G16_FLOAT = FsrDescriptors.FFX_API_SURFACE_FORMAT_R16G16_FLOAT;
    public static final int FFX_API_SURFACE_FORMAT_R32_FLOAT = FsrDescriptors.FFX_API_SURFACE_FORMAT_R32_FLOAT;
    public static final int FFX_API_RESOURCE_TYPE_TEXTURE = FsrDescriptors.FFX_API_RESOURCE_TYPE_TEXTURE;
    public static final int FFX_API_RESOURCE_STATE_UNORDERED_ACCESS = FsrDescriptors.FFX_API_RESOURCE_STATE_UNORDERED_ACCESS;
    public static final int FFX_API_RESOURCE_STATE_COMMON = FsrDescriptors.FFX_API_RESOURCE_STATE_COMMON;

    /** FFX API 2.1 struct-type discriminators for the frame-generation context / dispatch descriptors. */
    public static final int FFX_API_STRUCT_TYPE_CREATE_CONTEXT_DESC_FRAMEGENERATION = 13;
    public static final int FFX_API_STRUCT_TYPE_DISPATCH_DESC_FRAMEGENERATION = 14;

    /** FFX frame-generation flags (bit set in {@code FfxApiCreateContextDescFrameGeneration.flags}). */
    public static final int FFX_FRAMEGENERATION_FLAG_NONE = 0;
    public static final int FFX_FRAMEGENERATION_FLAG_ALLOW_HUDRESS = 1 << 0;
    public static final int FFX_FRAMEGENERATION_FLAG_HIGH_HDR = 1 << 1;
    public static final int FFX_FRAMEGENERATION_FLAG_USE_BACKBUFFER_IMAGE = 1 << 2;
    public static final int FFX_FRAMEGENERATION_FLAG_USE_TEXTURE_ARRAY_FOR_BACKBUFFER = 1 << 3;

    /**
     * Pre-computed field offsets for the FFX 2.1 frame-generation struct. We follow
     * the same alignment rules as the upscaler struct (x64 C default alignment, no
     * {@code #pragma pack}). The 2.1 frame-generation dispatch struct is large (~700
     * bytes) because of the 2 output color resources, the 2 HUDLess backbuffer
     * variants, and the 5 view-space matrices; we allocate 1 KiB to be safe.
     *
     * <pre>
     * struct FfxApiCreateContextDescFrameGeneration {       // 56 B
     *     FfxApiCreateContextDescHeader header;            //  0..24
     *     FfxApiDimensions2D displaySize;                  // 24..32
     *     FfxApiDimensions2D renderSize;                   // 32..40
     *     FfxApiBoolean   allowAsyncCompute;               // 40
     *     FfxApiBoolean   allowHudless;                    // 44
     *     FfxApiBoolean   enableAsyncWorkload;             // 48
     *     uint32_t        flags;                           // 52 (FfxApiFrameGenerationFlags)
     * };
     * </pre>
     *
     * For the dispatch struct we only document the offsets we write; the rest of the
     * 1 KiB buffer is zero. FFX defaults to identity matrices / null resources, which
     * is acceptable for the swapchain path we drive.
     */
    public static final class Layouts {
        public static final long CREATE_TOTAL_SIZE = 56;

        // Create-context offsets.
        public static final long CTX_HDR = 0;
        public static final long CTX_DISPLAY_W = 24;
        public static final long CTX_DISPLAY_H = 28;
        public static final long CTX_RENDER_W = 32;
        public static final long CTX_RENDER_H = 36;
        public static final long CTX_ALLOW_ASYNC_COMPUTE = 40;
        public static final long CTX_ALLOW_HUDLESS = 44;
        public static final long CTX_ENABLE_ASYNC_WORKLOAD = 48;
        public static final long CTX_FLAGS = 52;

        // Dispatch struct — larger. Total size = 1024 B (1 KiB, well over the 2.1 struct's
        // ~720 B — leaves room for a small struct bump between 2.1 and 2.2).
        public static final long DSP_TOTAL_SIZE = 1024;
        public static final long DSP_HEADER = 0;                  // FfxApiHeader (type + pNext) at struct start
        public static final long DSP_PRESENT_COLOR = 16;          // FfxApiResource 40 B
        public static final long DSP_OUTPUT_COLOR_0 = 56;         // FfxApiResource 40 B
        public static final long DSP_OUTPUT_COLOR_1 = 96;         // FfxApiResource 40 B
        public static final long DSP_CURRENT_BACKBUFFER = 136;    // FfxApiResource 40 B
        public static final long DSP_CURRENT_HUDLESS = 176;       // FfxApiResource 40 B
        public static final long DSP_PREV_BACKBUFFER = 216;       // FfxApiResource 40 B
        public static final long DSP_PREV_HUDLESS = 256;          // FfxApiResource 40 B
        public static final long DSP_MOTION = 296;                // FfxApiResource 40 B
        public static final long DSP_MOTION_HUDLESS = 336;        // FfxApiResource 40 B
        public static final long DSP_DEPTH = 376;                 // FfxApiResource 40 B
        public static final long DSP_DEPTH_HUDLESS = 416;         // FfxApiResource 40 B
        public static final long DSP_RENDER_W = 456;
        public static final long DSP_RENDER_H = 460;
        public static final long DSP_DISPLAY_W = 464;
        public static final long DSP_DISPLAY_H = 468;
        public static final long DSP_VS_TO_CLIP = 472;            // 64 B
        public static final long DSP_CLIP_TO_VS = 536;            // 64 B
        public static final long DSP_CLIP_TO_PREV_CLIP = 600;     // 64 B
        public static final long DSP_PREV_CLIP_TO_CLIP = 664;     // 64 B
        public static final long DSP_VS_TO_WORLD = 728;           // 64 B (optional)
        public static final long DSP_CAMERA_NEAR = 792;
        public static final long DSP_CAMERA_FAR = 796;
        public static final long DSP_CAMERA_FOV = 800;
        public static final long DSP_VIEW_TO_PREV_VIEW_PAD = 804; // FFX-internal, leave 0
        public static final long DSP_FRAME_ID = 808;              // uint64
        public static final long DSP_FRAME_GEN_INDEX = 816;       // uint32
        public static final long DSP_FRAME_GEN_COUNT = 820;       // uint32
        public static final long DSP_MIN_DEPTH = 824;
        public static final long DSP_MAX_DEPTH = 828;
        public static final long DSP_RESET = 832;
        public static final long DSP_ALLOW_ASYNC_WORKLOAD = 836;
        public static final long DSP_BACKBUFFER_FORMAT = 840;
        public static final long DSP_HDR = 844;

        private Layouts() {
        }
    }

    /**
     * Build an {@code ffxCreateContextDescFrameGenerationVulkan} descriptor. {@code displaySize} and
     * {@code renderSize} are the swapchain and the present source's display / render resolutions
     * (the FG context needs to know both because it writes generated frames at the swapchain size).
     */
    public static MemorySegment buildCreateContextDescFrameGeneration(Arena arena, int displayWidth, int displayHeight,
                                                                       int renderWidth, int renderHeight,
                                                                       boolean allowHudless, boolean allowAsyncCompute,
                                                                       boolean hdrBackbuffer) {
        MemorySegment desc = arena.allocate(Layouts.CREATE_TOTAL_SIZE);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_HDR + FsrDescriptors.Layouts.CREATE_HDR_TYPE,
                FFX_API_STRUCT_TYPE_CREATE_CONTEXT_DESC_FRAMEGENERATION);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_DISPLAY_W, displayWidth);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_DISPLAY_H, displayHeight);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_RENDER_W, renderWidth);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_RENDER_H, renderHeight);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_ALLOW_ASYNC_COMPUTE, allowAsyncCompute ? 1 : 0);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_ALLOW_HUDLESS, allowHudless ? 1 : 0);
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_ENABLE_ASYNC_WORKLOAD, allowAsyncCompute ? 1 : 0);
        int flags = FFX_FRAMEGENERATION_FLAG_NONE;
        if (hdrBackbuffer) {
            flags |= FFX_FRAMEGENERATION_FLAG_HIGH_HDR;
        }
        desc.set(ValueLayout.JAVA_INT, Layouts.CTX_FLAGS, flags);
        return desc;
    }

    /**
     * Build an {@code ffxDispatchDescFrameGenerationVulkan} descriptor. The Caustica-side caller
     * supplies the current / previous backbuffers (both at swapchain size + format), the depth + MVs
     * (both at render res), and a single output resource (FFX writes the {@code index}-th generated
     * frame into it). For multi-frame generation the caller dispatches once per generated frame with
     * the same descriptor except {@code frameGenIndex} incremented.
     */
    public static MemorySegment buildDispatchDescFrameGeneration(Arena arena,
                                                                   long currentBackbuffer, int backbufferFormat,
                                                                   long currentDepth, int depthFormat,
                                                                   long currentMv, int mvFormat,
                                                                   long hudlessBackbuffer, int hudlessFormat,
                                                                   long prevBackbuffer, int prevFormat,
                                                                   long prevHudless, int prevHudlessFormat,
                                                                   long output, int outputFormat,
                                                                   int renderWidth, int renderHeight,
                                                                   int displayWidth, int displayHeight,
                                                                   int index, int multiFrameCount,
                                                                   long frameId, boolean reset, boolean hdr,
                                                                   org.joml.Matrix4fc viewToClip,
                                                                   org.joml.Matrix4fc clipToView,
                                                                   org.joml.Matrix4fc clipToPrevClip,
                                                                   org.joml.Matrix4fc prevClipToClip) {
        MemorySegment desc = arena.allocate(Layouts.DSP_TOTAL_SIZE);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_HEADER + FsrDescriptors.Layouts.DISPATCH_HDR_TYPE,
                FFX_API_STRUCT_TYPE_DISPATCH_DESC_FRAMEGENERATION);

        writeResourceImage(desc, Layouts.DSP_PRESENT_COLOR, currentBackbuffer, backbufferFormat,
                displayWidth, displayHeight);
        // outputColor[0] is the generated frame FFX writes into. For multi-frame generation, the
        // caller passes the right slot here.
        writeResourceImage(desc, Layouts.DSP_OUTPUT_COLOR_0, output, outputFormat,
                displayWidth, displayHeight);
        // outputColor[1] — leave empty.
        writeEmptyResource(desc, Layouts.DSP_OUTPUT_COLOR_1);

        writeResourceImage(desc, Layouts.DSP_CURRENT_BACKBUFFER, currentBackbuffer, backbufferFormat,
                displayWidth, displayHeight);
        writeResourceImage(desc, Layouts.DSP_CURRENT_HUDLESS, hudlessBackbuffer, hudlessFormat,
                displayWidth, displayHeight);
        writeResourceImage(desc, Layouts.DSP_PREV_BACKBUFFER, prevBackbuffer, prevFormat,
                displayWidth, displayHeight);
        writeResourceImage(desc, Layouts.DSP_PREV_HUDLESS, prevHudless, prevHudlessFormat,
                displayWidth, displayHeight);
        writeResourceImage(desc, Layouts.DSP_MOTION, currentMv, mvFormat, renderWidth, renderHeight);
        writeEmptyResource(desc, Layouts.DSP_MOTION_HUDLESS);
        writeResourceImage(desc, Layouts.DSP_DEPTH, currentDepth, depthFormat, renderWidth, renderHeight);
        writeEmptyResource(desc, Layouts.DSP_DEPTH_HUDLESS);

        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_RENDER_W, renderWidth);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_RENDER_H, renderHeight);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_DISPLAY_W, displayWidth);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_DISPLAY_H, displayHeight);

        writeMatrix4(desc, Layouts.DSP_VS_TO_CLIP, viewToClip);
        writeMatrix4(desc, Layouts.DSP_CLIP_TO_VS, clipToView);
        writeMatrix4(desc, Layouts.DSP_CLIP_TO_PREV_CLIP, clipToPrevClip);
        writeMatrix4(desc, Layouts.DSP_PREV_CLIP_TO_CLIP, prevClipToClip);
        // viewToWorld left zero — FFX defaults to identity.

        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_CAMERA_NEAR, 0.05f);
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_CAMERA_FAR, 1024.0f);
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_CAMERA_FOV, (float) Math.toRadians(70.0));

        desc.set(ValueLayout.JAVA_LONG, Layouts.DSP_FRAME_ID, frameId);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_FRAME_GEN_INDEX, index);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_FRAME_GEN_COUNT, multiFrameCount);
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_MIN_DEPTH, 0.0f);
        desc.set(ValueLayout.JAVA_FLOAT, Layouts.DSP_MAX_DEPTH, 1.0f);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_RESET, reset ? 1 : 0);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_ALLOW_ASYNC_WORKLOAD, 0);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_BACKBUFFER_FORMAT, backbufferFormat);
        desc.set(ValueLayout.JAVA_INT, Layouts.DSP_HDR, hdr ? 1 : 0);
        return desc;
    }

    // ---- Resource writers (mirrors FsrDescriptors but writes the FG-layout offsets) ----

    private static void writeResourceImage(MemorySegment base, long offset, long imageHandle, int format,
                                          int width, int height) {
        if (imageHandle == 0L) {
            writeEmptyResource(base, offset);
            return;
        }
        base.set(ValueLayout.JAVA_INT, offset + FsrDescriptors.Layouts.RES_TYPE, FFX_API_RESOURCE_TYPE_TEXTURE);
        base.set(ValueLayout.ADDRESS, offset + FsrDescriptors.Layouts.RES_RESOURCE, MemorySegment.ofAddress(imageHandle));
        base.set(ValueLayout.JAVA_INT, offset + FsrDescriptors.Layouts.RES_STATE,
                FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
        long descOff = offset + FsrDescriptors.Layouts.RES_DESC_OFFSET;
        base.set(ValueLayout.JAVA_INT, descOff + FsrDescriptors.Layouts.DESC_TYPE, FFX_API_RESOURCE_TYPE_TEXTURE);
        base.set(ValueLayout.JAVA_INT, descOff + FsrDescriptors.Layouts.DESC_FORMAT, format);
        base.set(ValueLayout.JAVA_INT, descOff + FsrDescriptors.Layouts.DESC_TEX_WIDTH, width);
        base.set(ValueLayout.JAVA_INT, descOff + FsrDescriptors.Layouts.DESC_TEX_HEIGHT, height);
    }

    private static void writeEmptyResource(MemorySegment base, long offset) {
        for (long i = 0; i < FsrDescriptors.Layouts.RESOURCE_SIZE; i += 4) {
            base.set(ValueLayout.JAVA_INT, offset + i, 0);
        }
    }

    private static void writeMatrix4(MemorySegment base, long offset, org.joml.Matrix4fc m) {
        // Row-major float[16] — matches JOML's Matrix4fc.get(byte[] / float[]) layout.
        float[] tmp = new float[16];
        if (m != null) {
            m.get(tmp);
        } else {
            java.util.Arrays.fill(tmp, 0f);
            tmp[0] = tmp[5] = tmp[10] = tmp[15] = 1f; // identity
        }
        for (int i = 0; i < 16; i++) {
            base.set(ValueLayout.JAVA_FLOAT, offset + i * 4L, tmp[i]);
        }
    }
}
