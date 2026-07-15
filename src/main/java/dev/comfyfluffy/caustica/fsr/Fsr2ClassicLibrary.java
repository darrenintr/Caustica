package dev.comfyfluffy.caustica.fsr;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings to classic FSR2 Vulkan ({@code libffx_fsr2_caustica.so}).
 */
public final class Fsr2ClassicLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle probe;
    private final MethodHandle create;
    private final MethodHandle destroy;
    private final MethodHandle dispatch;
    private final MethodHandle upscaleRatio;
    private final MethodHandle jitterPhase;
    private final MethodHandle jitterOffset;

    private Fsr2ClassicLibrary(SymbolLookup lookup) {
        this.probe = req(lookup, "caustica_ffx_fsr2_probe",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.create = req(lookup, "caustica_ffx_fsr2_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));
        this.destroy = req(lookup, "caustica_ffx_fsr2_destroy",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // ctx, cmd, colorImg, colorView, depthImg, depthView, mvImg, mvView, outImg, outView,
        // renderW, renderH, jx, jy, dt, preExp, near, far, fov, reset
        this.dispatch = req(lookup, "caustica_ffx_fsr2_dispatch",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT));
        this.upscaleRatio = req(lookup, "caustica_ffx_fsr2_get_upscale_ratio",
                FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT));
        this.jitterPhase = req(lookup, "caustica_ffx_fsr2_get_jitter_phase_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.jitterOffset = req(lookup, "caustica_ffx_fsr2_get_jitter_offset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    public static Fsr2ClassicLibrary load(Path so) {
        return new Fsr2ClassicLibrary(SymbolLookup.libraryLookup(so, Arena.global()));
    }

    private static MethodHandle req(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("ffx_fsr2 missing " + name)),
                desc);
    }

    public int probe() {
        try {
            return (int) probe.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int create(long device, long physical, int flags, int maxRw, int maxRh, int dw, int dh,
                      MemorySegment outCtx) {
        try {
            return (int) create.invokeExact(device, physical, flags, maxRw, maxRh, dw, dh, outCtx);
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

    public int dispatch(MemorySegment ctx, long cmd,
                        long colorImg, long colorView,
                        long depthImg, long depthView,
                        long mvImg, long mvView,
                        long outImg, long outView,
                        int rw, int rh,
                        float jx, float jy, float dtMs, float preExposure,
                        float camNear, float camFar, float fovY, int reset) {
        try {
            return (int) dispatch.invokeExact(ctx, cmd,
                    colorImg, colorView, depthImg, depthView, mvImg, mvView, outImg, outView,
                    rw, rh, jx, jy, dtMs, preExposure, camNear, camFar, fovY, reset);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public float upscaleRatio(int qualityMode) {
        try {
            return (float) upscaleRatio.invokeExact(qualityMode);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int jitterPhaseCount(int renderW, int displayW) {
        try {
            return (int) jitterPhase.invokeExact(renderW, displayW);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public void jitterOffset(int index, int phaseCount, float[] xy) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment x = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment y = arena.allocate(ValueLayout.JAVA_FLOAT);
            int rc = (int) jitterOffset.invokeExact(x, y, index, phaseCount);
            if (rc != 0) {
                xy[0] = 0;
                xy[1] = 0;
                return;
            }
            xy[0] = x.get(ValueLayout.JAVA_FLOAT, 0);
            xy[1] = y.get(ValueLayout.JAVA_FLOAT, 0);
        } catch (Throwable t) {
            xy[0] = 0;
            xy[1] = 0;
        }
    }
}
