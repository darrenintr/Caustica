# Caustica — FSR 4.1 / FFX 4.x modular loader native shim.
#
# Builds a shared lib exposing a thin C ABI for Java FFM. The shim
# compiles standalone by default (probe-only mode); with
# CAUSTICA_FFX_FSR41_FULL=ON it links against the FSR 4.1 Linux port
# static lib (libffx_fsr41_linux.a) so the AMD FFX provider symbols
# are real.
#
# Default build (PROBE_ONLY):
#   cmake -S native/ffx_fsr41 -B build/ffx_fsr41 -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/ffx_fsr41 -j
# Probe-only build is always successful — the FSR 4.1 Linux port
# headers/libs are not required.
#
# Full build (links the FSR 4.1 Linux port):
#   1. Build the FSR 4.1 Linux port static lib:
#        cd ../../../fsr3.1/fsr41_linux && mkdir -p build && cd build \
#          && cmake -DCMAKE_BUILD_TYPE=Release .. && make -j
#   2. Re-run this CMake with:
#        cmake -S native/ffx_fsr41 -B build/ffx_fsr41 \
#          -DCAUSTICA_FFX_FSR41_FULL=ON \
#          -DCAUSTICA_FFX_FSR41_PORT_LIB=/path/to/fsr3.1/fsr41_linux/build/libffx_fsr41_linux.a \
#          -DCAUSTICA_FFX_FSR41_PORT_INCLUDE=/path/to/fsr3.1/fsr41_linux/include
#   3. cmake --build build/ffx_fsr41 -j

cmake_minimum_required(VERSION 3.17)
project(caustica_ffx_fsr41 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(CAUSTICA_FFX_FSR41_FULL "Link the FSR 4.1 Linux port provider (FFX 4.x)"
       OFF)

# Common FFX / Vulkan include paths. The FSR 4.1 Linux port headers
# are added under CAUSTICA_FFX_FSR41_FULL — they are not required
# for the probe-only build.
if(CAUSTICA_FFX_FSR41_FULL)
  if(NOT CAUSTICA_FFX_FSR41_PORT_INCLUDE)
    # Sensible default: the sibling project checked out alongside Caustica.
    get_filename_component(_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
    set(CAUSTICA_FFX_FSR41_PORT_INCLUDE "${_REPO_ROOT}/fsr3.1/fsr41_linux/include"
        CACHE PATH "Path to FSR 4.1 Linux port headers")
  endif()
  if(NOT CAUSTICA_FFX_FSR41_PORT_LIB)
    get_filename_component(_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
    set(CAUSTICA_FFX_FSR41_PORT_LIB "${_REPO_ROOT}/fsr3.1/fsr41_linux/build/libffx_fsr41_linux.a"
        CACHE FILEPATH "Path to libffx_fsr41_linux.a")
  endif()
  if(NOT EXISTS "${CAUSTICA_FFX_FSR41_PORT_INCLUDE}/ffx_api.h")
    message(FATAL_ERROR
      "CAUSTICA_FFX_FSR41_FULL=ON but FFX headers not at ${CAUSTICA_FFX_FSR41_PORT_INCLUDE}/ffx_api.h. "
      "Set CAUSTICA_FFX_FSR41_PORT_INCLUDE, or unset CAUSTICA_FFX_FSR41_FULL for probe-only.")
  endif()
  if(NOT EXISTS "${CAUSTICA_FFX_FSR41_PORT_LIB}")
    message(FATAL_ERROR
      "CAUSTICA_FFX_FSR41_FULL=ON but static lib not found at ${CAUSTICA_FFX_FSR41_PORT_LIB}. "
      "Build the FSR 4.1 Linux port first, or set CAUSTICA_FFX_FSR41_PORT_LIB.")
  endif()
  message(STATUS "caustica_ffx_fsr41: FULL link mode")
  message(STATUS "  headers: ${CAUSTICA_FFX_FSR41_PORT_INCLUDE}")
  message(STATUS "  lib:     ${CAUSTICA_FFX_FSR41_PORT_LIB}")
  add_compile_definitions(CAUSTICA_FFX_FSR41_FULL=1)
else()
  message(STATUS "caustica_ffx_fsr41: PROBE_ONLY mode (FSR 4.1 native dispatch stubbed)")
endif()

add_library(caustica_ffx_fsr41 SHARED ffx_fsr41_shim.cpp)
target_include_directories(caustica_ffx_fsr41
  PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CAUSTICA_FFX_FSR41_PORT_INCLUDE}
)

if(CAUSTICA_FFX_FSR41_FULL)
  target_link_libraries(caustica_ffx_fsr41 PRIVATE ${CAUSTICA_FFX_FSR41_PORT_LIB})
  # The FSR 4.1 Linux port pulls in vulkan headers transitively.
  find_package(Vulkan REQUIRED)
  target_link_libraries(caustica_ffx_fsr41 PRIVATE Vulkan::Vulkan)
endif()

set_target_properties(caustica_ffx_fsr41 PROPERTIES
  OUTPUT_NAME "ffx_fsr41_caustica"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)

install(TARGETS caustica_ffx_fsr41 LIBRARY DESTINATION lib)
