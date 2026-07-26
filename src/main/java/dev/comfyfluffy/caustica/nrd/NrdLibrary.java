package dev.comfyfluffy.caustica.nrd;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** FFM bindings to the platform NRD shim. */
public final class NrdLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle probe;
    private final MethodHandle abiVersion;
    private final MethodHandle normalEncoding;
    private final MethodHandle roughnessEncoding;
    private final MethodHandle create;
    private final MethodHandle destroy;
    private final MethodHandle resize;
    private final MethodHandle dispatch;
    private final MethodHandle createRelax;
    private final MethodHandle dispatchRelax;
    private final MethodHandle setMaxAccumulatedFrameNum;
    private final MethodHandle setRelaxMaxAccumulatedFrameNum;

    private NrdLibrary(SymbolLookup lookup) {
        this.probe = req(lookup, "caustica_nrd_probe", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.abiVersion = req(lookup, "caustica_nrd_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.normalEncoding = req(lookup, "caustica_nrd_normal_encoding", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.roughnessEncoding = req(lookup, "caustica_nrd_roughness_encoding", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.create = req(lookup, "caustica_nrd_create_v2",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.destroy = req(lookup, "caustica_nrd_destroy",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.resize = req(lookup, "caustica_nrd_resize",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.dispatch = req(lookup, "caustica_nrd_dispatch_v2",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // RELAX variant — same signature as create_v2/dispatch_v2, separate context per kind.
        // Symbols are OPTIONAL: if the underlying shim predates RELAX support, these stay null
        // and supportsRelax() returns false. dispatchRelax will fail-fast in that case.
        this.createRelax = optReq(lookup, "caustica_nrd_create_relax_v2",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.dispatchRelax = optReq(lookup, "caustica_nrd_dispatch_relax_v2",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // Optional — only present when the bundled NRD shim has been rebuilt with the v3 ABI
        // setter hooks. Older .so builds without these symbols keep the handles null and the
        // setters below become no-ops (logged once). NRD's REBLUR / RELAX settings struct fields
        // can be re-tuned at runtime via nrd::SetDenoiserSettings without re-creating the
        // instance — useful for letting users cap history length from caustica.toml.
        this.setMaxAccumulatedFrameNum = optReq(lookup, "caustica_nrd_set_max_accumulated_frame_num",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.setRelaxMaxAccumulatedFrameNum = optReq(lookup, "caustica_nrd_set_relax_max_accumulated_frame_num",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    public static NrdLibrary load(Path so) {
        return new NrdLibrary(SymbolLookup.libraryLookup(so, Arena.global()));
    }

    /** True iff the underlying shim was built with RELAX support. */
    public boolean supportsRelax() {
        return createRelax != null && dispatchRelax != null;
    }

    private static MethodHandle req(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("nrd missing " + name)),
                desc);
    }

    private static MethodHandle optReq(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return lookup.find(name).map(addr -> LINKER.downcallHandle(addr, desc)).orElse(null);
    }

    public int createRelax(long device, long physical, long getDeviceProcAddr, int w, int h,
                           int graphicsQueueFamily, int computeQueueFamily, MemorySegment outCtx) {
        if (createRelax == null) {
            throw new IllegalStateException("NRD shim predates RELAX support");
        }
        try {
            return (int) createRelax.invokeExact(device, physical, getDeviceProcAddr, w, h,
                    graphicsQueueFamily, computeQueueFamily, outCtx);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int dispatchRelax(MemorySegment ctx, long cmd,
                             long inDiffImg, long inDiffView,
                             long inSpecImg, long inSpecView,
                             long inMvImg, long inMvView,
                             long inNormImg, long inNormView,
                             long inVzImg, long inVzView,
                             long inShadowImg, long inShadowView,
                             long inDiffConfImg, long inDiffConfView,
                             long inSpecConfImg, long inSpecConfView,
                             long inDisocclusionImg, long inDisocclusionView,
                             long outDiffImg, long outDiffView,
                             long outSpecImg, long outSpecView,
                             long outShadowImg, long outShadowView,
                             MemorySegment viewToClip, MemorySegment viewToClipPrev,
                             MemorySegment worldToView, MemorySegment worldToViewPrev,
                             float jx, float jy, float jxPrev, float jyPrev,
                             float lightDirX, float lightDirY, float lightDirZ,
                             int frameIndex, int reset) {
        if (dispatchRelax == null) {
            throw new IllegalStateException("NRD shim predates RELAX support");
        }
        try {
            return (int) dispatchRelax.invokeExact(ctx, cmd,
                    inDiffImg, inDiffView, inSpecImg, inSpecView,
                    inMvImg, inMvView, inNormImg, inNormView, inVzImg, inVzView,
                    inShadowImg, inShadowView, inDiffConfImg, inDiffConfView,
                    inSpecConfImg, inSpecConfView, inDisocclusionImg, inDisocclusionView,
                    outDiffImg, outDiffView, outSpecImg, outSpecView, outShadowImg, outShadowView,
                    viewToClip, viewToClipPrev, worldToView, worldToViewPrev,
                    jx, jy, jxPrev, jyPrev, lightDirX, lightDirY, lightDirZ, frameIndex, reset);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int probe() {
        try {
            return (int) probe.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int abiVersion() {
        return invokeInt(abiVersion, "caustica_nrd_abi_version");
    }

    public int normalEncoding() {
        return invokeInt(normalEncoding, "caustica_nrd_normal_encoding");
    }

    public int roughnessEncoding() {
        return invokeInt(roughnessEncoding, "caustica_nrd_roughness_encoding");
    }

    private static int invokeInt(MethodHandle handle, String name) {
        try {
            return (int) handle.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(name + " failed", t);
        }
    }

    public int create(long device, long physical, long getDeviceProcAddr, int w, int h,
                      int graphicsQueueFamily, int computeQueueFamily, MemorySegment outCtx) {
        try {
            return (int) create.invokeExact(device, physical, getDeviceProcAddr, w, h,
                    graphicsQueueFamily, computeQueueFamily, outCtx);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int destroy(MemorySegment ctx) {
        try {
            return (int) destroy.invokeExact(ctx);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int resize(MemorySegment ctx, int w, int h) {
        try {
            return (int) resize.invokeExact(ctx, w, h);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int dispatch(MemorySegment ctx, long cmd,
                        long inDiffImg, long inDiffView,
                        long inSpecImg, long inSpecView,
                        long inMvImg, long inMvView,
                        long inNormImg, long inNormView,
                        long inVzImg, long inVzView,
                        long inShadowImg, long inShadowView,
                        long inDiffConfImg, long inDiffConfView,
                        long inSpecConfImg, long inSpecConfView,
                        long inDisocclusionImg, long inDisocclusionView,
                        long outDiffImg, long outDiffView,
                        long outSpecImg, long outSpecView,
                        long outShadowImg, long outShadowView,
                        MemorySegment viewToClip, MemorySegment viewToClipPrev,
                        MemorySegment worldToView, MemorySegment worldToViewPrev,
                        float jx, float jy, float jxPrev, float jyPrev,
                        float lightDirX, float lightDirY, float lightDirZ,
                        int frameIndex, int reset) {
        try {
            return (int) dispatch.invokeExact(ctx, cmd,
                    inDiffImg, inDiffView, inSpecImg, inSpecView,
                    inMvImg, inMvView, inNormImg, inNormView, inVzImg, inVzView,
                    inShadowImg, inShadowView, inDiffConfImg, inDiffConfView,
                    inSpecConfImg, inSpecConfView, inDisocclusionImg, inDisocclusionView,
                    outDiffImg, outDiffView, outSpecImg, outSpecView, outShadowImg, outShadowView,
                    viewToClip, viewToClipPrev, worldToView, worldToViewPrev,
                    jx, jy, jxPrev, jyPrev, lightDirX, lightDirY, lightDirZ, frameIndex, reset);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Update REBLUR's {@code maxAccumulatedFrameNum} at runtime. Returns 0 on success, or
     * a non-zero error if the symbol is missing (the bundled NRD shim predates the setter).
     */
    public int setMaxAccumulatedFrameNum(MemorySegment ctx, int frameNum) {
        if (setMaxAccumulatedFrameNum == null) {
            return -1;
        }
        try {
            return (int) setMaxAccumulatedFrameNum.invokeExact(ctx, frameNum);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Update RELAX's diffuse + specular maxAccumulatedFrameNum. Same return contract. */
    public int setRelaxMaxAccumulatedFrameNum(MemorySegment ctx, int frameNum) {
        if (setRelaxMaxAccumulatedFrameNum == null) {
            return -1;
        }
        try {
            return (int) setRelaxMaxAccumulatedFrameNum.invokeExact(ctx, frameNum);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public boolean supportsMaxAccumulatedFrameNumSetter() {
        return setMaxAccumulatedFrameNum != null;
    }

    public boolean supportsRelaxMaxAccumulatedFrameNumSetter() {
        return setRelaxMaxAccumulatedFrameNum != null;
    }
}
