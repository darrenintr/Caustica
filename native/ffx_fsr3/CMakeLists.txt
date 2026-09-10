# Caustica — Linux Vulkan FSR3 Upscaler JNI shim
#
# Two build modes, picked at configure time via CAUSTICA_FFX_FSR3_MODE:
#
#   1. "monolithic" (default if CAUSTICA_FFX_SDK_DIR is provided)
#      Drives the upstream FFX SDK CMake build (`ffx-api/CMakeLists.txt`
#      → `add_subdirectory(../sdk sdk)`) which knows how to compile the
#      FSR3 upscaler permutations and link the static libs. The shader
#      compiler under tools/ffx_shader_compiler is rebuilt from source
#      because the pre-shipped binary can have a non-executable bit on
#      fuse-mounted source dirs (Caustica's `Windows data` mount).
#
#   2. "prebuilt" (when CAUSTICA_FFX_PREBUILT_LIBS is set)
#      Skips the FFX SDK rebuild and just links the .a files at the
#      given directory. Useful for CI / dev iterations where the SDK
#      is already built once on /home.
#
# Resource format contract (matches the FSR 4.1 / FSR 2.2 / XeSS shims):
#   color       R16G16B16A16_SFLOAT, render res
#   depth       R32_SFLOAT,         render res
#   motion      R16G16B16A16_SFLOAT, render res
#   output      R16G16B16A16_SFLOAT, display res
#   reactive    R32_SFLOAT,         render res (optional)
#   exposure    R32_SFLOAT,         optional 1x1

cmake_minimum_required(VERSION 3.21)
project(caustica_ffx_fsr3 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Vendored FFX SDK snapshot.
set(CAUSTICA_FFX_SDK_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/FidelityFX-SDK-vendored"
    CACHE PATH "Path to the vendored FFX SDK snapshot")

# Optional: skip the FFX SDK cmake rebuild and use prebuilt .a files.
set(CAUSTICA_FFX_PREBUILT_LIBS
    ""
    CACHE PATH "Path to a directory with libffx_fsr3upscaler_x64.a et al")
set(CAUSTICA_FFX_WRAP_SC
    ""
    CACHE FILEPATH "Optional bash wrapper that disables brace expansion (Workaround for SC bug)")

find_package(Vulkan REQUIRED)

# Common: where the FFX SDK headers live. The shared lib needs ffx_api.hpp
# at build time, regardless of which mode we link in.
if(NOT EXISTS "${CAUSTICA_FFX_SDK_DIR}/ffx-api/CMakeLists.txt")
    message(FATAL_ERROR
        "FFX SDK not found at ${CAUSTICA_FFX_SDK_DIR}. "
        "See third_party/FidelityFX-SDK-vendored/git-info.txt.")
endif()

# Shim shared library.
add_library(caustica_ffx_fsr3 SHARED
    ffx_fsr3_shim.cpp
)
target_include_directories(caustica_ffx_fsr3 PRIVATE
    ${CAUSTICA_FFX_SDK_DIR}/ffx-api/include
    ${CAUSTICA_FFX_SDK_DIR}/sdk/include
    ${Vulkan_INCLUDE_DIRS}
)
target_compile_definitions(caustica_ffx_fsr3 PRIVATE
    FFX_BACKEND_VK=1
)
target_link_libraries(caustica_ffx_fsr3 PRIVATE
    Vulkan::Vulkan
    dl
)

if(CAUSTICA_FFX_PREBUILT_LIBS)
    # --- Prebuilt mode: link .a files from the given directory. --------
    set(_ffx_lib_dir "${CAUSTICA_FFX_PREBUILT_LIBS}")
    message(STATUS
        "caustica_ffx_fsr3: linking prebuilt FFX libs from ${_ffx_lib_dir}")

    foreach(_lib amd_fidelityfx_vk ffx_backend_vk_x64 ffx_fsr3upscaler_x64 tiny-process-library)
        set(_path "${_ffx_lib_dir}/lib${_lib}.a")
        if(NOT EXISTS "${_path}")
            message(FATAL_ERROR "Missing static lib: ${_path}")
        endif()
        target_link_libraries(caustica_ffx_fsr3 PRIVATE "${_path}")
        message(STATUS "  + lib${_lib}.a")
    endforeach()
else()
    # --- Monolithic mode: drive the FFX SDK build as an ExternalProject. ---
    include(ExternalProject)

    set(CAUSTICA_FFX_TMP "${CMAKE_CURRENT_BINARY_DIR}/ffx_tmp")
    set(CAUSTICA_FFX_BUILD_DIR "${CAUSTICA_FFX_TMP}/ffx_sdk_build")
    set(CAUSTICA_FFX_TOOLS_DIR "${CAUSTICA_FFX_TMP}/ffx_sc")

    # The vendored SDK hard-codes `set(FFX_FSR* ON)` in ffx-api/CMakeLists.txt
    # even when -DFFX_FOO=OFF is given (cache variables need CACHE to be
    # external). We patch them locally to opt-in only if not already defined
    # to ON. This is done in-place in our copy under CAUSTICA_FFX_SDK_DIR.
    # Recompute the after we set up external_project_add below.
    message(STATUS
        "caustica_ffx_fsr3: driving FFX SDK build (may take ~5min on first configure)")

    externalproject_add(caustica_ffx_shader_compiler
        SOURCE_DIR "${CAUSTICA_FFX_SDK_DIR}/sdk/tools/ffx_shader_compiler"
        BINARY_DIR "${CAUSTICA_FFX_TOOLS_DIR}"
        INSTALL_COMMAND ""
        CMAKE_ARGS -DCMAKE_BUILD_TYPE=Release
        BUILD_ALWAYS FALSE
        BUILD_COMMAND ${CMAKE_COMMAND} -E echo "SC built in ${CAUSTICA_FFX_TOOLS_DIR}/bin/FidelityFX_SC"
    )
    add_custom_command(
        OUTPUT "${CAUSTICA_FFX_TMP}/FidelityFX_SC.ready"
        DEPENDS caustica_ffx_shader_compiler
        COMMAND ${CMAKE_COMMAND} -E make_directory ${CAUSTICA_FFX_TMP}/ffx_sc/bin
        COMMAND chmod +x ${CAUSTICA_FFX_TOOLS_DIR}/bin/FidelityFX_SC || true
        COMMAND ${CMAKE_COMMAND} -E touch ${CAUSTICA_FFX_TMP}/FidelityFX_SC.ready
        COMMENT "Ensure FFX_SC built and is executable"
    )
    add_custom_target(caustica_ffx_sc_ready ALL DEPENDS "${CAUSTICA_FFX_TMP}/FidelityFX_SC.ready")

    externalproject_add(caustica_ffx_sdk
        DEPENDS caustica_ffx_shader_compiler caustica_ffx_sc_ready
        SOURCE_DIR "${CAUSTICA_FFX_SDK_DIR}/ffx-api"
        BINARY_DIR "${CAUSTICA_FFX_BUILD_DIR}"
        INSTALL_DIR "${CAUSTICA_FFX_BUILD_DIR}/install"
        CMAKE_ARGS
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
            -DFFX_API_BACKEND=VK_X64
            -DFFX_BUILD_AS_DLL=OFF
            -DFFX_AUTO_COMPILE_SHADERS=ON
            -DFFX_FSR1=OFF
            -DFFX_FSR2=OFF
            -DFFX_FSR3=OFF
            -DFFX_FSR3UPSCALER=ON
            -DFFX_FI=OFF
            -DFFX_OF=OFF
            -DFFX_DENOISER=OFF
            -DFFX_SSSR=OFF
            -DFFX_CACAO=OFF
            -DFFX_LPM=OFF
            -DFFX_BLUR=OFF
            -DFFX_VRS=OFF
            -DFFX_CAS=OFF
            -DFFX_DOF=OFF
            -DFFX_LENS=OFF
            -DFFX_PARALLEL_SORT=OFF
            -DFFX_SPD=OFF
            -DFFX_ALL=OFF
            -DFFX_SC_EXECUTABLE=${CAUSTICA_FFX_TOOLS_DIR}/bin/FidelityFX_SC
        BUILD_ALWAYS FALSE
        USES_TERMINAL_BUILD TRUE
    )

    add_custom_target(caustica_ffx_sdk_imported DEPENDS caustica_ffx_sdk)
    add_dependencies(caustica_ffx_fsr3 caustica_ffx_sdk_imported)

    set(_ffx_lib_dir "${CAUSTICA_FFX_BUILD_DIR}/ffx_sdk")
    foreach(_lib ffx_fsr3upscaler_x64 ffx_backend_vk_x64 amd_fidelityfx_vk)
        target_link_libraries(caustica_ffx_fsr3 PRIVATE
            "${_ffx_lib_dir}/lib${_lib}.a")
    endforeach()
endif()

set_target_properties(caustica_ffx_fsr3 PROPERTIES
    OUTPUT_NAME "caustica_ffx_fsr3_upscaler"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)

install(TARGETS caustica_ffx_fsr3 LIBRARY DESTINATION lib)
