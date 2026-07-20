# Phase 1: libcaustica_native — JNI scaffold only, no SDK calls.
# Real AMD FFX / NRD / Vulkan SDK calls are deferred until headers are present.
# The library is a thin Java↔C++ bridge so Caustica can prove the toolchain end-to-end
# (cmake → ninja → gradle copy → System.loadLibrary) without changing any GLSL denoise path.

cmake_minimum_required(VERSION 3.20)
project(caustica_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Position-independent code is required for shared objects on Linux.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Single source file. All Phase 1 functionality is "ping" + a startup log line.
add_library(caustica_native SHARED
    src/caustica_native.cpp
)

# JNI headers live in the JDK. JAVA_HOME is the convention Caustica's build already uses.
# `if(NOT DEFINED …)` is not valid CMake syntax (DEFINED only works un-negated), so we test
# the value directly — an unset or empty JAVA_HOME both fall through to the FATAL.
if(JAVA_HOME STREQUAL "")
    message(FATAL_ERROR "JAVA_HOME must be set so the JDK's jni.h is reachable for the C++ build.")
endif()
find_path(JNI_INCLUDE_DIR jni.h
    HINTS "${JAVA_HOME}/include"
    REQUIRED)
# jni.h #includes "jni_md.h" — a per-platform header. On Linux/Zulu the actual file
# is at $JAVA_HOME/include/linux/jni_md.h (lowercase). CMAKE_SYSTEM_NAME is "Linux"
# (mixed case) on this machine, so we find_path the platform header explicitly instead
# of relying on the system name. The two directories are added as separate include
# paths so the relative #include in jni.h resolves.
find_path(JNI_PLATFORM_INCLUDE_DIR jni_md.h
    HINTS "${JAVA_HOME}/include/linux"
    HINTS "${JAVA_HOME}/include/win32"
    HINTS "${JAVA_HOME}/include/bsd"
    HINTS "${JAVA_HOME}/include/aix"
    REQUIRED)

# Phase 2 (2026-07-20): wire the AMD FidelityFX SDK headers. We are NOT linking the SDK
# runtime in Phase 2 — that needs prebuilt static libs (libffx_denoiser_x64.a,
# libffx_backend_vk_x64.a) and SPIR-V shader blobs which aren't on this machine yet.
# What we DO: add the header path so caustica_native.cpp can #include <ffx_denoiser.h>
# and report the SDK's compile-time version constant. That single fact — that the
# header compiles into the .so — proves the SDK is reachable. Actual dispatch stays
# a Phase 3 problem (would require linking the SDK runtime, see
# native/ffx_denoiser/CMakeLists.txt for the full link block).
#
# The SDK repo layout is third_party/FidelityFX-SDK/Kits/FidelityFX/{denoisers,api}.
# ffx_denoiser.h transitively #includes "../../api/include/ffx_api.h" — a relative
# path that resolves from denoisers/include/. We add BOTH directories so the
# transitive include resolves whether the compiler's cwd-based include lookup
# fires (relative) or the absolute path is used.
set(FFX_DENOISER_INCLUDE "${CMAKE_SOURCE_DIR}/../third_party/FidelityFX-SDK/Kits/FidelityFX/denoisers/include")
set(FFX_API_INCLUDE     "${CMAKE_SOURCE_DIR}/../third_party/FidelityFX-SDK/Kits/FidelityFX/api/include")
# Optional: skip FFX header addition if the SDK isn't checked out. We still succeed
# the build, and caustica_native.cpp guards its #include on a FFX_SDK_PRESENT macro.
if(NOT EXISTS "${FFX_DENOISER_INCLUDE}/ffx_denoiser.h")
    message(STATUS "caustica_native: FFX SDK headers not found at ${FFX_DENOISER_INCLUDE}; "
                    "ffxDenoiserVersion() will be a stub. Full SDK dispatch is Phase 3.")
    set(FFX_DENOISER_INCLUDE "")
    set(FFX_API_INCLUDE "")
    add_compile_definitions(FFX_SDK_PRESENT=0)
else()
    message(STATUS "caustica_native: FFX SDK headers present at ${FFX_DENOISER_INCLUDE}")
    add_compile_definitions(FFX_SDK_PRESENT=1)
endif()

target_include_directories(caustica_native PRIVATE
    ${JNI_INCLUDE_DIR}
    ${JNI_PLATFORM_INCLUDE_DIR}
    ${FFX_DENOISER_INCLUDE}
    ${FFX_API_INCLUDE})

# Linux: only export JNI_OnLoad and the bridge's JNIEXPORT functions. Hides internal
# symbols and shrinks the .so. Future Phase 2+ (FFX/NRD/Vulkan) will add explicit
# -fvisibility=default on whatever wrappers the SDK pulls in.
set_target_properties(caustica_native PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    POSITION_INDEPENDENT_CODE ON)

# Pkg-config-free link line. The JDK doesn't ship a .pc file for jni.h, but on Linux the
# only requirement is that libjvm.so is on the runtime loader path. Caustica's existing
# natives (libffx_*.so, libamd_*.so) already bring their own runtimes; we leave link line
# to just the default shared object set.

# Output a Caustica-style versioned name. strip-debug strips the heavy DWARF info that
# CMakeDefaults sometimes leaves behind — we keep a debug variant at -dbg if needed.
set_target_properties(caustica_native PROPERTIES OUTPUT_NAME "caustica_native")
