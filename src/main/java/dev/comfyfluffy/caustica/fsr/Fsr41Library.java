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
 * FFM bindings to the FSR 4.1 / FFX 4.x modular shim
 * ({@code libffx_fsr41_caustica.so}).
 *
 * <p>The shim is a C ABI wrapper around the FFX API
 * ({@code ffxCreateContext}, {@code ffxDispatch}, …). Probe-only mode
 * (the default until the FSR 4.1 Linux port's Vulkan backend is
 * shipped) returns the FFX 4.1.1 version number from
 * {@link #probe()} and {@code -100} from create/dispatch — the Java
 * side uses those negative returns to latch {@code consumeFailOpen()}
 * so the renderer falls back to TAAU / FSR2 instead of presenting a
 * black frame.
 *
 * <p>ABI: Vulkan handles (VkDevice, VkPhysicalDevice, VkCommandBuffer,
 * VkImage, VkImageView) pass through as uint64_t opaque addresses.
 * Resource format contract: color / output
 * {@code R16G16B16A16_SFLOAT}, depth {@code R32_SFLOAT}, motion
 * {@code R16G16B16A16_SFLOAT}, reactive {@code R32_SFLOAT} optional.
 *
 * @see native/ffx_fsr41/ffx_fsr41_shim.h
 */
public final class Fsr41Library {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle probe;
    private final MethodHandle queryUp;
    private final MethodHandle queryJpc;
    private final MethodHandle queryJoff;
    private final MethodHandle create;
    private final MethodHandle destroy;
    private final MethodHandle dispatch;

    private Fsr41Library(SymbolLookup lookup) {
        this.probe = req(lookup, "caustica_ffx_fsr41_probe",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.queryUp = req(lookup, "caustica_ffx_fsr41_query_upscale_ratio",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.queryJpc = req(lookup, "caustica_ffx_fsr41_query_jitter_phase_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.queryJoff = req(lookup, "caustica_ffx_fsr41_query_jitter_offset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.create = req(lookup, "caustica_ffx_fsr41_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));
        this.destroy = req(lookup, "caustica_ffx_fsr41_destroy",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // ctx, cmd, color img/view, depth img/view, motion img/view, output img/view,
        // reactive img/view, render_w, render_h, upscale_w, upscale_h,
        // jx, jy, dtMs, preExp, near, far, fov, reset
        this.dispatch = req(lookup, "caustica_ffx_fsr41_dispatch",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT));
    }

    public static Fsr41Library load(Path so) {
        return new Fsr41Library(SymbolLookup.libraryLookup(so, Arena.global()));
    }

    private static MethodHandle req(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() ->
                        new IllegalStateException("ffx_fsr41_caustica missing export " + name)),
                desc);
    }

    /** Packed version (major*10000 + minor*100 + patch), e.g. 40101. */
    public int probe() {
        try {
            return (int) probe.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr41_probe failed", t);
        }
    }

    /** Quality mode → per-axis upscale ratio. Returns 0 if not FULL / unknown. */
    public int queryUpscaleRatio(int qualityMode) {
        try {
            return (int) queryUp.invokeExact(qualityMode);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr41_query_upscale_ratio failed", t);
        }
    }

    public int queryJitterPhaseCount(int renderW, int displayW) {
        try {
            return (int) queryJpc.invokeExact(renderW, displayW);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr41_query_jitter_phase_count failed", t);
        }
    }

    public void queryJitterOffset(int index, int phaseCount, float[] xy) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment x = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment y = arena.allocate(ValueLayout.JAVA_FLOAT);
            int rc = (int) queryJoff.invokeExact(index, phaseCount, x, y);
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

    public int create(long vkDevice, long vkPhysical, int flags,
                      int maxRw, int maxRh, int displayW, int displayH,
                      MemorySegment outCtx) {
        try {
            return (int) create.invokeExact(vkDevice, vkPhysical, flags,
                    maxRw, maxRh, displayW, displayH, outCtx);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr41_create failed", t);
        }
    }

    public int destroy(MemorySegment ctx) {
        try {
            return (int) destroy.invokeExact(ctx);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr41_destroy failed", t);
        }
    }

    public int dispatch(MemorySegment ctx, long cmd,
                        long colorImg, long colorView,
                        long depthImg, long depthView,
                        long motionImg, long motionView,
                        long outputImg, long outputView,
                        long reactiveImg, long reactiveView,
                        int renderW, int renderH,
                        int displayW, int displayH,
                        float jx, float jy, float dtMs, float preExposure,
                        float camNear, float camFar, float fovY, int reset) {
        try {
            return (int) dispatch.invokeExact(ctx, cmd,
                    colorImg, colorView, depthImg, depthView,
                    motionImg, motionView, outputImg, outputView,
                    reactiveImg, reactiveView,
                    renderW, renderH, displayW, displayH,
                    jx, jy, dtMs, preExposure,
                    camNear, camFar, fovY, reset);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_fsr41_dispatch failed", t);
        }
    }
}
