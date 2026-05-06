cmake_minimum_required(VERSION 3.22.1)
project("pocketnode" CXX C)

set(CMAKE_CXX_STANDARD 17)

# Android Native Release Profiling
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")

# ============================================================
# llama.cpp — fetch and build from source via FetchContent
# ============================================================
include(FetchContent)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b9045    # pinned stable — has GGML_OPENCL_USE_ADRENO_KERNELS + GGML_CPU_KLEIDIAI
    GIT_SHALLOW    TRUE
)

# Build llama.cpp as a static library
set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Enable mmap so models stream from storage instead of loading fully into RAM
set(LLAMA_MMAP ON CACHE BOOL "" FORCE)

# Adreno OpenCL + KleidiAI CPU — flags passed in from build.gradle.kts
# GGML_OPENCL, GGML_OPENCL_USE_ADRENO_KERNELS, GGML_CPU_KLEIDIAI, GGML_OPENMP
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# ============================================================
# JNI bridge library
# ============================================================
add_library(pocketnode SHARED
    pocketnode_jni.cpp
    ${llama_cpp_SOURCE_DIR}/examples/llava/clip.cpp
    ${llama_cpp_SOURCE_DIR}/examples/llava/llava.cpp
)

target_include_directories(pocketnode PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/examples/llava
    ${llama_cpp_SOURCE_DIR}/common
)

target_link_libraries(pocketnode
    llama
    log
    android
)
