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
target_include_directories(caustica_native PRIVATE
    ${JNI_INCLUDE_DIR}
    ${JNI_PLATFORM_INCLUDE_DIR})

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
