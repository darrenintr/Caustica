package dev.comfyfluffy.caustica.ffx.denoiser;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings to {@code libffx_denoiser_caustica.so} (official FidelityFX Denoiser C ABI).
 *
 * @see native/ffx_denoiser/ffx_denoiser_shim.h
 */
public final class FfxDenoiserLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle probe;
    private final MethodHandle create;
    private final MethodHandle destroy;

    private FfxDenoiserLibrary(SymbolLookup lookup) {
        this.probe = handle(lookup, "caustica_ffx_denoiser_probe",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.create = handle(lookup, "caustica_ffx_denoiser_create",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.destroy = handle(lookup, "caustica_ffx_denoiser_destroy",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    public static FfxDenoiserLibrary load(Path so) {
        return new FfxDenoiserLibrary(SymbolLookup.libraryLookup(so, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() ->
                        new IllegalStateException("ffx_denoiser_caustica missing export " + name)),
                desc);
    }

    /** Packed version major*10000+minor*100+patch, or throws on native error. */
    public int probe() {
        try {
            return (int) probe.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_denoiser_probe failed", t);
        }
    }

    public int create(long vkDevice, long vkPhysical, int flags, int width, int height,
                      int normalsFormat, java.lang.foreign.MemorySegment outCtxPtr) {
        try {
            return (int) create.invokeExact(vkDevice, vkPhysical, flags, width, height,
                    normalsFormat, outCtxPtr);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_denoiser_create failed", t);
        }
    }

    public int destroy(java.lang.foreign.MemorySegment ctx) {
        try {
            return (int) destroy.invokeExact(ctx);
        } catch (Throwable t) {
            throw new IllegalStateException("caustica_ffx_denoiser_destroy failed", t);
        }
    }
}
