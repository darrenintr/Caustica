package dev.comfyfluffy.caustica.nrd;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** FFM bindings to {@code libnrd_caustica.so}. */
public final class NrdLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle probe;
    private final MethodHandle create;
    private final MethodHandle destroy;
    private final MethodHandle resize;
    private final MethodHandle dispatch;

    private NrdLibrary(SymbolLookup lookup) {
        this.probe = req(lookup, "caustica_nrd_probe", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.create = req(lookup, "caustica_nrd_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
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
    }

    public static NrdLibrary load(Path so) {
        return new NrdLibrary(SymbolLookup.libraryLookup(so, Arena.global()));
    }

    private static MethodHandle req(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("nrd missing " + name)),
                desc);
    }

    public int probe() {
        try {
            return (int) probe.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int create(long device, long physical, long getDeviceProcAddr, int w, int h, MemorySegment outCtx) {
        try {
            return (int) create.invokeExact(device, physical, getDeviceProcAddr, w, h, outCtx);
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
}
