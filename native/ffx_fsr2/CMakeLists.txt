cmake_minimum_required(VERSION 3.17)
project(caustica_ffx_fsr2 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT FFX_SDK)
  get_filename_component(_REPO "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
  set(FFX_SDK "${_REPO}/third_party/FidelityFX-SDK")
endif()

add_library(caustica_ffx_fsr2 SHARED ffx_fsr2_shim.cpp)
target_include_directories(caustica_ffx_fsr2 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${FFX_SDK}/sdk/include)
set_target_properties(caustica_ffx_fsr2 PROPERTIES OUTPUT_NAME "ffx_fsr2_caustica")

option(CAUSTICA_FFX_FSR2_FULL "Link ffx_fsr2 + vk backend (needs permutation blobs)" OFF)
if(CAUSTICA_FFX_FSR2_FULL)
  message(WARNING "CAUSTICA_FFX_FSR2_FULL requires generated ffx_sc permutation headers — not enabled by default")
  target_compile_definitions(caustica_ffx_fsr2 PRIVATE CAUSTICA_FFX_FSR2_FULL=1)
endif()
