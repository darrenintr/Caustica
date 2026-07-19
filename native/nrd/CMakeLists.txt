cmake_minimum_required(VERSION 3.17)
project(caustica_nrd LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(NRD_ROOT "" CACHE PATH "Pinned NVIDIA NRD source tree")
set(SHADERMAKE_BLOB_LIB "" CACHE FILEPATH "ShaderMakeBlob static library built with NRD")
set(CAUSTICA_NRD_VERSION "4.17.4" CACHE STRING "Expected NRD source version")
set(CAUSTICA_NRD_COMMIT "9a3fe938a7558fd16b6c91a1c0456305cdcd9f16" CACHE STRING "Pinned NRD commit")
set(CAUSTICA_NRD_NORMAL_ENCODING "4" CACHE STRING "Encoding used by prepare_nrd_inputs.comp")
set(CAUSTICA_NRD_ROUGHNESS_ENCODING "1" CACHE STRING "Encoding used by prepare_nrd_inputs.comp")

if(NOT NRD_ROOT OR NOT EXISTS "${NRD_ROOT}/Include/NRD.h")
  message(FATAL_ERROR "NRD_ROOT must point at the pinned NRD source tree")
endif()

find_package(Vulkan REQUIRED)

if(MSVC)
  set(_nrd_candidates "${NRD_ROOT}/_Bin/NRD.lib" "${NRD_ROOT}/_Bin/Release/NRD.lib")
else()
  set(_nrd_candidates "${NRD_ROOT}/_Bin/libNRD.a")
endif()
foreach(_candidate IN LISTS _nrd_candidates)
  if(EXISTS "${_candidate}")
    set(NRD_LIBRARY "${_candidate}")
    break()
  endif()
endforeach()
if(NOT NRD_LIBRARY)
  message(FATAL_ERROR "NRD static library not found under ${NRD_ROOT}/_Bin; build pinned NRD first")
endif()
if(NOT SHADERMAKE_BLOB_LIB OR NOT EXISTS "${SHADERMAKE_BLOB_LIB}")
  message(FATAL_ERROR "Set SHADERMAKE_BLOB_LIB to the ShaderMakeBlob static library built with NRD")
endif()

add_library(caustica_nrd SHARED caustica_nrd_shim.cpp)
target_include_directories(caustica_nrd PRIVATE ${NRD_ROOT}/Include ${Vulkan_INCLUDE_DIRS})
target_link_libraries(caustica_nrd PRIVATE Vulkan::Vulkan ${CMAKE_DL_LIBS})
if(MSVC)
  target_link_libraries(caustica_nrd PRIVATE ${NRD_LIBRARY} ${SHADERMAKE_BLOB_LIB})
  target_link_options(caustica_nrd PRIVATE
    "/WHOLEARCHIVE:${NRD_LIBRARY}"
    "/WHOLEARCHIVE:${SHADERMAKE_BLOB_LIB}")
else()
  target_link_libraries(caustica_nrd PRIVATE
    "-Wl,--whole-archive" ${NRD_LIBRARY} ${SHADERMAKE_BLOB_LIB} "-Wl,--no-whole-archive" m)
endif()
target_compile_definitions(caustica_nrd PRIVATE
  NRD_STATIC_LIBRARY=1
  CAUSTICA_NRD_NORMAL_ENCODING=${CAUSTICA_NRD_NORMAL_ENCODING}
  CAUSTICA_NRD_ROUGHNESS_ENCODING=${CAUSTICA_NRD_ROUGHNESS_ENCODING})

set_target_properties(caustica_nrd PROPERTIES
  OUTPUT_NAME "nrd_caustica"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")

message(STATUS "Caustica NRD: NRD ${CAUSTICA_NRD_VERSION} (${CAUSTICA_NRD_COMMIT})")
message(STATUS "Caustica NRD: root=${NRD_ROOT}, library=${NRD_LIBRARY}")
message(STATUS "Caustica NRD: ShaderMakeBlob=${SHADERMAKE_BLOB_LIB}")
message(STATUS "Caustica NRD: normal=${CAUSTICA_NRD_NORMAL_ENCODING}, roughness=${CAUSTICA_NRD_ROUGHNESS_ENCODING}")
