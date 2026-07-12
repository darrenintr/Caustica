package dev.comfyfluffy.caustica.xess;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings to the Intel XeSS SDK runtime DLL
 * ({@code libxess.dll} on Windows, {@code libxess.so} on Linux). The XeSS SDK ships
 * a single runtime that hosts both the XMX path (Arc / Xe-LPG discrete GPUs) and the
 * DP4a fallback (cross-vendor on SM 6.4+); the SDK picks the path at runtime via
 * {@code xessSetConfig} (or a config passed at context creation).
 *
 * <p>The public Intel XeSS SDK 2.0+ exposes a C ABI; the 2.1+ extensions (frame
 * generation, the {@code xess2_*} family of entry points) are loaded through the same
 * {@code libxess} and only resolved if present (older 2.0 SDKs simply lack them and
 * XeSS frame gen then falls back to off). All Caustica-side structs are built on the
 * Java side against the Intel public C layouts; the SDK is small and stable across
 * 2.0 / 2.1 / 2.x.
 */
public final class XeSsLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    // ---- XeSS 2.0+ C API enums (from xess_types.h / xess_vk.h) ----

    /** XeSS config / execution-path enum. */
    public static final int XESS_CONFIG_NONE = 0;
    public static final int XESS_CONFIG_PERF_XMX = 1;
    public static final int XESS_CONFIG_PERF_DP4A = 2;

    /** XeSS quality settings (input / render res as a fraction of display res). */
    public static final int XESS_QUALITY_SETTING_PERFORMANCE = 0;
    public static final int XESS_QUALITY_SETTING_BALANCED = 1;
    public static final int XESS_QUALITY_SETTING_QUALITY = 2;
    public static final int XESS_QUALITY_SETTING_ULTRA_PERFORMANCE = 3;
    public static final int XESS_QUALITY_SETTING_ULTRA_QUALITY = 4; // 1:1, no upscale

    /** XeSS color formats (subset; we only need HDR10 and 8-bit). */
    public static final int XESS_COLOR_FORMAT_RGBA8_UNORM = 0;
    public static final int XESS_COLOR_FORMAT_RGBA8_SRGB = 1;
    public static final int XESS_COLOR_FORMAT_RGBA16_FLOAT = 2;
    public static final int XESS_COLOR_FORMAT_RGBA16_SFLOAT = 2;  // alias

    private final MethodHandle vkCreateContext;
    private final MethodHandle destroyContext;
    private final MethodHandle setConfig;
    private final MethodHandle vkGetInitParams;
    private final MethodHandle vkExecute;
    private final MethodHandle getVersion;
    private final MethodHandle getInputUV;
    private final MethodHandle lastError;

    private XeSsLibrary(SymbolLookup lookup) {
        // xess_result_t xessVkCreateContext(xess_context_handle_t* outHandle, VkDevice device)
        this.vkCreateContext = handle(lookup, "xessVkCreateContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        // xess_result_t xessDestroyContext(xess_context_handle_t handle)
        this.destroyContext = handle(lookup, "xessDestroyContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // xess_result_t xessSetConfig(xess_context_handle_t handle, xess_config_t config)
        this.setConfig = handle(lookup, "xessSetConfig",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        // xess_result_t xessVkGetInitParams(xess_vk_init_params_t* outParams)
        this.vkGetInitParams = handle(lookup, "xessVkGetInitParams",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // xess_result_t xessVkExecute(xess_context_handle_t handle, const xess_vk_execute_params_t* params)
        this.vkExecute = handle(lookup, "xessVkExecute",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // const char* xessGetVersion()
        this.getVersion = handle(lookup, "xessGetVersion",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        // xess_result_t xessGetInputUV(xess_context_handle_t handle, float* outOffsetX, float* outOffsetY)
        this.getInputUV = handle(lookup, "xessGetInputUV",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.lastError = handle(lookup, "xessGetLastError",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
    }

    public static XeSsLibrary load(Path dll) {
        return new XeSsLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("libxess missing export " + name)),
                desc);
    }

    public int vkCreateContext(MemorySegment outHandle, long device) {
        try {
            return (int) vkCreateContext.invokeExact(outHandle, device);
        } catch (Throwable t) {
            throw new RuntimeException("xessVkCreateContext failed", t);
        }
    }

    public int destroyContext(MemorySegment handle) {
        try {
            return (int) destroyContext.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("xessDestroyContext failed", t);
        }
    }

    public int setConfig(MemorySegment handle, int config) {
        try {
            return (int) setConfig.invokeExact(handle, config);
        } catch (Throwable t) {
            throw new RuntimeException("xessSetConfig failed", t);
        }
    }

    public int vkGetInitParams(MemorySegment outParams) {
        try {
            return (int) vkGetInitParams.invokeExact(outParams);
        } catch (Throwable t) {
            throw new RuntimeException("xessVkGetInitParams failed", t);
        }
    }

    public int vkExecute(MemorySegment handle, MemorySegment params) {
        try {
            return (int) vkExecute.invokeExact(handle, params);
        } catch (Throwable t) {
            throw new RuntimeException("xessVkExecute failed", t);
        }
    }

    public String getVersion() {
        try {
            MemorySegment seg = (MemorySegment) getVersion.invokeExact();
            return seg == null || seg.equals(MemorySegment.NULL) ? null : seg.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return null;
        }
    }

    public int getInputUV(MemorySegment handle, MemorySegment outX, MemorySegment outY) {
        try {
            return (int) getInputUV.invokeExact(handle, outX, outY);
        } catch (Throwable t) {
            throw new RuntimeException("xessGetInputUV failed", t);
        }
    }

    public String lastError() {
        try {
            MemorySegment seg = (MemorySegment) lastError.invokeExact();
            return seg == null || seg.equals(MemorySegment.NULL) ? null : seg.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return null;
        }
    }
}
