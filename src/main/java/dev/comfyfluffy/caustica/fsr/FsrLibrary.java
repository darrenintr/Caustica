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
 * FFM bindings to the AMD FidelityFX SDK runtime DLLs. The SDK ships native libraries per feature
 * ({@code amd_fidelityfx_upscaler.dll}, {@code amd_fidelityfx_framegeneration.dll}, both loaded through
 * {@code amd_fidelityfx_loader.dll}); the {@code loader} abstracts the model-DLL selection so a single
 * set of {@code ffx_*} entry points serves FSR 3 <em>and</em> FSR 4 (4.x ships the FSR-4 model in the
 * upscaler DLL; the loader picks the right one per device).
 *
 * <p>All Caustica-side structs and FFX parameter blocks live in the native side or are described inline
 * here against the {@code ffx_api.h} SDK header. Java only passes primitives and raw Vulkan handles
 * (as {@code long} addresses).
 *
 * <h2>FFX API (SDK 2.1+) entry points we bind</h2>
 * <ul>
 *   <li>{@code ffxCreateContext} / {@code ffxDestroyContext} — per-feature opaque context.</li>
 *   <li>{@code ffxDispatch} — per-frame work submission (upscale or frame generation, type-switched).</li>
 *   <li>{@code ffxQuery} — capability / version queries (returns {@code ffxQueryDescUpscaleGetProviderVersion}).</li>
 *   <li>{@code ffxConfigure} — runtime key/value tweaks (e.g. {@code FFX_UPSCALER_KEYVALUE_SHARPNESS}).</li>
 * </ul>
 *
 * <p>Java-only flags here are kept minimal; the heavy descriptor structs are passed as opaque
 * {@link MemorySegment} blobs built on the FSR / frame-gen call sites from
 * {@link dev.comfyfluffy.caustica.fsr.FsrRuntime} native helpers, so the FFX header changes between
 * SDK minor versions do not require Java-side rework.
 */
public final class FsrLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle createContext;
    private final MethodHandle destroyContext;
    private final MethodHandle dispatch;
    private final MethodHandle query;
    private final MethodHandle configure;
    private final MethodHandle lastError;
    private final MethodHandle providerVersion;

    private FsrLibrary(SymbolLookup lookup) {
        // All four are C ABI: ffxReturnCode_t (uint32) return + 1 or 2 pointer args. Bound symbols are
        // resolved through the loader; AMD guarantees the names in the loader export table.
        this.createContext = handle(lookup, "ffxCreateContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.destroyContext = handle(lookup, "ffxDestroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.dispatch = handle(lookup, "ffxDispatch",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.query = handle(lookup, "ffxQuery",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.configure = handle(lookup, "ffxConfigure",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.lastError = optionalHandle(lookup, "ffxGetLastError",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        // Provider version returns the version of the loaded upscaler DLL (FSR 3 vs FSR 4): the major
        // version is the FFX SDK major; the patch encodes the model version (e.g. FSR 3.1.4). Always
        // available when the loader resolves, but allow missing for safety.
        this.providerVersion = optionalHandle(lookup, "ffxQueryGetProviderVersion",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
    }

    public static FsrLibrary load(Path dll) {
        return new FsrLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("amd_fidelityfx_loader missing export " + name)),
                desc);
    }

    private static MethodHandle optionalHandle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return lookup.find(name).map(sym -> LINKER.downcallHandle(sym, desc)).orElse(null);
    }

    public int createContext(MemorySegment contextOut, MemorySegment desc, MemorySegment memCb) {
        try {
            return (int) this.createContext.invokeExact(contextOut, desc, memCb);
        } catch (Throwable t) {
            throw new RuntimeException("ffxCreateContext failed", t);
        }
    }

    public int destroyContext(MemorySegment context, MemorySegment memCb) {
        try {
            return (int) this.destroyContext.invokeExact(context, memCb);
        } catch (Throwable t) {
            throw new RuntimeException("ffxDestroyContext failed", t);
        }
    }

    public int dispatch(MemorySegment context, MemorySegment desc) {
        try {
            return (int) this.dispatch.invokeExact(context, desc);
        } catch (Throwable t) {
            throw new RuntimeException("ffxDispatch failed", t);
        }
    }

    public int query(MemorySegment context, MemorySegment desc) {
        try {
            return (int) this.query.invokeExact(context, desc);
        } catch (Throwable t) {
            throw new RuntimeException("ffxQuery failed", t);
        }
    }

    public int configure(MemorySegment context, MemorySegment desc) {
        try {
            return (int) this.configure.invokeExact(context, desc);
        } catch (Throwable t) {
            throw new RuntimeException("ffxConfigure failed", t);
        }
    }

    public boolean hasProviderVersion() {
        return providerVersion != null;
    }

    /**
     * Reads the provider version out struct (4 uint32 fields: major, minor, patch, reserved). Returns
     * {-1, -1, -1, -1} when the symbol is missing. Caller should treat negative major as "version unknown".
     */
    public int[] readProviderVersion() {
        if (providerVersion == null) {
            return new int[] { -1, -1, -1, -1 };
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment versionOut = arena.allocate(ValueLayout.JAVA_INT, 4);
            int rc = (int) this.providerVersion.invokeExact(versionOut);
            if (rc != 0) {
                return new int[] { -1, -1, -1, -1 };
            }
            return new int[] {
                    versionOut.getAtIndex(ValueLayout.JAVA_INT, 0),
                    versionOut.getAtIndex(ValueLayout.JAVA_INT, 1),
                    versionOut.getAtIndex(ValueLayout.JAVA_INT, 2),
                    versionOut.getAtIndex(ValueLayout.JAVA_INT, 3)
            };
        } catch (Throwable t) {
            return new int[] { -1, -1, -1, -1 };
        }
    }
}
