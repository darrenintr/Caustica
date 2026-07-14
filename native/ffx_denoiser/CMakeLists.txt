# Caustica — official FidelityFX Denoiser (Vulkan) native library
#
# Builds a shared lib with a thin C ABI for Java FFM.
# Expects the FidelityFX SDK at FFX_SDK (default: repo third_party/FidelityFX-SDK).
#
#   cmake -S native/ffx_denoiser -B build/ffx_denoiser \
#     -DFFX_SDK=/path/to/FidelityFX-SDK \
#     -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/ffx_denoiser -j
#
# Note: Full SDK build of ffx_denoiser + ffx_backend_vk requires AMD's shader
# compiler / pre-generated shader blobs (see third_party/FidelityFX-SDK/build_linux_x64.sh
# for a proven Linux path that compiles the 8 denoiser GLSL passes with glslc).
# This CMake targets linking against those components when available; the shim
# alone can be compiled against headers for the probe symbol.

cmake_minimum_required(VERSION 3.17)
project(caustica_ffx_denoiser LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT FFX_SDK)
  get_filename_component(_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
  set(FFX_SDK "${_REPO_ROOT}/third_party/FidelityFX-SDK")
endif()
if(NOT EXISTS "${FFX_SDK}/sdk/include/FidelityFX/host/ffx_denoiser.h")
  message(FATAL_ERROR "FFX_SDK does not look like FidelityFX SDK (missing ffx_denoiser.h): ${FFX_SDK}")
endif()

message(STATUS "Caustica FFX Denoiser: FFX_SDK=${FFX_SDK}")

set(FFX_INCLUDE_PATH "${FFX_SDK}/sdk/include")
set(FFX_HOST_PATH "${FFX_INCLUDE_PATH}/FidelityFX/host")

add_library(caustica_ffx_denoiser SHARED
  ffx_denoiser_shim.cpp
)
target_include_directories(caustica_ffx_denoiser
  PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${FFX_INCLUDE_PATH}
)
target_compile_definitions(caustica_ffx_denoiser PRIVATE
  FFX_DENOISER=1
)
# Headers-only probe build: full ffxDenoiserContextDestroy needs the static lib.
# When CAUSTICA_FFX_DENOISER_LINK_SDK=ON, add_subdirectory the SDK components
# (requires working AMD shader blob generation — use build_linux_x64.sh first).
option(CAUSTICA_FFX_DENOISER_LINK_SDK "Link against built ffx_denoiser + vk backend" OFF)
if(CAUSTICA_FFX_DENOISER_LINK_SDK)
  # Expect prebuilt libs in FFX_SDK/bin/ffx_sdk or CAUSTICA_FFX_LIB_DIR
  set(CAUSTICA_FFX_LIB_DIR "${FFX_SDK}/bin/ffx_sdk" CACHE PATH "Directory with libffx_denoiser_*.a")
  find_library(FFX_DENOISER_LIB NAMES ffx_denoiser_x64 ffx_denoiser PATHS ${CAUSTICA_FFX_LIB_DIR})
  find_library(FFX_BACKEND_VK_LIB NAMES ffx_backend_vk_x64 ffx_backend_vk PATHS ${CAUSTICA_FFX_LIB_DIR})
  if(FFX_DENOISER_LIB AND FFX_BACKEND_VK_LIB)
    target_link_libraries(caustica_ffx_denoiser PRIVATE ${FFX_DENOISER_LIB} ${FFX_BACKEND_VK_LIB})
    target_compile_definitions(caustica_ffx_denoiser PRIVATE CAUSTICA_FFX_DENOISER_FULL=1)
  else()
    message(WARNING "CAUSTICA_FFX_DENOISER_LINK_SDK=ON but libs not found in ${CAUSTICA_FFX_LIB_DIR}")
  endif()
endif()

# Probe-only mode: stub destroy so link succeeds without SDK static libs.
if(NOT CAUSTICA_FFX_DENOISER_LINK_SDK)
  target_compile_definitions(caustica_ffx_denoiser PRIVATE CAUSTICA_FFX_DENOISER_PROBE_ONLY=1)
endif()

set_target_properties(caustica_ffx_denoiser PROPERTIES
  OUTPUT_NAME "ffx_denoiser_caustica"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)

install(TARGETS caustica_ffx_denoiser LIBRARY DESTINATION lib)
