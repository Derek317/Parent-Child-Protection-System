cmake_minimum_required(VERSION 3.22)
project(igguard_risk C CXX)

# llama.cpp source tree (tag b12503, commit 4577932fab595eaa8b27ad71734d5de1f3c07d8c).
set(LLAMA_CPP_DIR "" CACHE PATH "Path to a llama.cpp checkout")
if(NOT LLAMA_CPP_DIR OR NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Set -DLLAMA_CPP_DIR=<llama.cpp checkout>")
endif()
option(RISK_HOST_CHECK "Build the host_check CLI instead of the Android JNI library" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp options: CPU only, one static archive set linked into our single .so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)   # b12503 replaced LLAMA_CURL; common/http is not built anyway
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if(RISK_HOST_CHECK)
    # Host x86-64: keep the CPU backend generic enough to run anywhere; still the same engine code path.
    set(GGML_NATIVE ON CACHE BOOL "" FORCE)
else()
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

if(RISK_HOST_CHECK)
    add_executable(host_check host_check.cpp risk_core.cpp)
    target_include_directories(host_check PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(host_check PRIVATE llama)
else()
    add_library(igguard_risk SHARED risk_jni.cpp risk_core.cpp)
    target_include_directories(igguard_risk PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(igguard_risk PRIVATE llama log)
    # Android 15 devices use 16 KB pages: align LOAD segments to 16 KB (NDK 27 defaults to 4 KB).
    target_link_options(igguard_risk PRIVATE "-Wl,-z,max-page-size=16384")
endif()
