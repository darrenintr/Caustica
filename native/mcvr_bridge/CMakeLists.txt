# Caustica — MCVR-style C++ Vulkan spike
#
# Independent spike: builds a Vulkan cube demo that exercises the same
# dependency toolchain we'll use for any future C++ rendering work
# (RADV, glslc, FFX SDK optional). It does NOT link against Java, does
# NOT modify the Gradle build, and does NOT touch any existing Caustica
# files outside of `native/mcvr_bridge/`. That guarantees the parallel
# Java branch stays unaffected.
#
# Build:
#   cmake -S native/mcvr_bridge -B build/cpp-cube -G Ninja \
#         -DCMAKE_BUILD_TYPE=Release \
#         -DCAUSTICA_FFX_SDK_DIR=/home/darren/.local/share/ffx_build/ffx-sdk
#   cmake --build build/cpp-cube
#   ./build/cpp-cube/vk_cube
#
# If the spike builds and runs, MCa's Vulkan backend machinery is alive.
# If it doesn't, we have a deterministic failure point to fix before
# planning any C++ port of the renderer.

cmake_minimum_required(VERSION 3.20)
project(caustica_mcvr_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- Vulkan loader --------------------------------------------------------
find_package(Vulkan REQUIRED)

# --- The FFX SDK Vulkan backend ----------------------------------------
# Optional: when set, we link the AMD FFX SDK Vulkan backend so that
# ffxCreateBackendVKDesc + the SPIR-V permutations produced by FFX_SC
# are accessible directly. This is what would later become the FSR3
# dispatch host.
set(CAUSTICA_FFX_SDK_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/FidelityFX-SDK-vendored"
    CACHE PATH "FFX SDK vendored snapshot")

option(CAUSTICA_MCVR_USE_FFX "Build with FFX SDK Vulkan backend" OFF)

if(CAUSTICA_MCVR_USE_FFX)
    if(NOT EXISTS "${CAUSTICA_FFX_SDK_DIR}/ffx-api/CMakeLists.txt")
        message(FATAL_ERROR
            "FFX SDK not found at ${CAUSTICA_FFX_SDK_DIR}. "
            "Pass -DCAUSTICA_FFX_SDK_DIR=<path>.")
    endif()
    add_subdirectory("${CAUSTICA_FFX_SDK_DIR}/ffx-api" ffx-api-build EXCLUDE_FROM_ALL)
endif()

# --- Spike source: a minimal Vulkan cube that demonstrates instance +
# device + RT compute pipeline. No rendering beyond a clear screen so the
# test focus is "do we have a valid VkDevice + VkPipeline + VkShaderModule
# + Ray Tracing acceleration structure on Linux".

add_executable(vk_cube
    src/main.cpp
    src/vk_renderer.cpp
    src/vk_renderer.h
)
target_include_directories(vk_cube PRIVATE
    src
    ${Vulkan_INCLUDE_DIRS}
)
target_link_libraries(vk_cube PRIVATE Vulkan::Vulkan)

if(CAUSTICA_MCVR_USE_FFX)
    target_link_libraries(vk_cube PRIVATE
        amd_fidelityfx_vk
        ffx_backend_vk_x64
        ffx_fsr3upscaler_x64)
    target_include_directories(vk_cube PRIVATE
        ${CAUSTICA_FFX_SDK_DIR}/ffx-api/include
        ${CAUSTICA_FFX_SDK_DIR}/sdk/include)
    target_compile_definitions(vk_cube PRIVATE FFX_BACKEND_VK=1)
endif()

# --- Bundle the compute shader pre-compiled ---------------------------
# The smoke executable is invalid without this shader, so fail during
# configure rather than producing a binary that can only fail at runtime.
find_program(GLSLC glslc REQUIRED)
set(SPIKE_SPV "${CMAKE_CURRENT_BINARY_DIR}/vk_cube.comp.spv")
add_custom_command(
    OUTPUT "${SPIKE_SPV}"
    COMMAND "${GLSLC}"
        -fshader-stage=compute
        --target-env=vulkan1.2
        -o "${SPIKE_SPV}"
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/vk_cube.comp"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/vk_cube.comp"
    COMMENT "Compiling vk_cube.comp -> vk_cube.comp.spv"
    VERBATIM
)
add_custom_target(vk_cube_shader ALL DEPENDS "${SPIKE_SPV}")
add_dependencies(vk_cube vk_cube_shader)

include(CTest)
if(BUILD_TESTING)
    add_test(NAME mcvr_bridge_compute_smoke COMMAND vk_cube)
    set_tests_properties(mcvr_bridge_compute_smoke PROPERTIES
        PASS_REGULAR_EXPRESSION "MCVR-BRIDGE-SPIKE-OK")
endif()
