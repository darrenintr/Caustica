cmake_minimum_required(VERSION 3.17)
project(caustica_nrd LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT NRD_ROOT)
  set(NRD_ROOT "/tmp/NRD")
endif()
if(NOT EXISTS "${NRD_ROOT}/Include/NRD.h")
  message(FATAL_ERROR "NRD_ROOT missing NRD.h: ${NRD_ROOT}")
endif()
if(NOT EXISTS "${NRD_ROOT}/_Bin/libNRD.a")
  message(FATAL_ERROR "NRD_ROOT missing _Bin/libNRD.a — build NRD first")
endif()

find_package(Vulkan REQUIRED)

# NRD embeds SPIR-V blobs that call ShaderMake::FindPermutationInBlob at runtime.
if(NOT SHADERMAKE_BLOB_LIB)
  set(_sm_candidates
    "${NRD_ROOT}/../NRD-build/_deps/shadermake-build/libShaderMakeBlob.a"
    "/tmp/NRD-build/_deps/shadermake-build/libShaderMakeBlob.a"
    "${CMAKE_BINARY_DIR}/../NRD-build/_deps/shadermake-build/libShaderMakeBlob.a"
  )
  foreach(_c ${_sm_candidates})
    if(EXISTS "${_c}")
      set(SHADERMAKE_BLOB_LIB "${_c}")
      break()
    endif()
  endforeach()
endif()
if(NOT SHADERMAKE_BLOB_LIB OR NOT EXISTS "${SHADERMAKE_BLOB_LIB}")
  message(FATAL_ERROR
    "libShaderMakeBlob.a not found (set SHADERMAKE_BLOB_LIB). "
    "It is built alongside NRD in NRD-build/_deps/shadermake-build/")
endif()
message(STATUS "Caustica NRD: ShaderMakeBlob=${SHADERMAKE_BLOB_LIB}")

add_library(caustica_nrd SHARED caustica_nrd_shim.cpp)
target_include_directories(caustica_nrd PRIVATE
  ${NRD_ROOT}/Include
  ${Vulkan_INCLUDE_DIRS}
)
# whole-archive: pull all NRD + ShaderMakeBlob objects into the .so (static deps)
target_link_libraries(caustica_nrd PRIVATE
  -Wl,--whole-archive
  ${NRD_ROOT}/_Bin/libNRD.a
  ${SHADERMAKE_BLOB_LIB}
  -Wl,--no-whole-archive
  Vulkan::Vulkan
  ${CMAKE_DL_LIBS}
  stdc++
  m
)
target_compile_definitions(caustica_nrd PRIVATE NRD_STATIC_LIBRARY=1)

set_target_properties(caustica_nrd PROPERTIES
  OUTPUT_NAME "nrd_caustica"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)

message(STATUS "Caustica NRD: NRD_ROOT=${NRD_ROOT}")
