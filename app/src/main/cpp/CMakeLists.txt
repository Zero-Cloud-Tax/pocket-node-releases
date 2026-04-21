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
    GIT_TAG        b4160    # pinned stable release
    GIT_SHALLOW    TRUE
)

# Build llama.cpp as a static library
set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Enable Vulkan Backend for Android GPU support
# Disabled temporarily because vulkan-shaders-gen requires host-side glslc and ninja on Windows Native
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# ============================================================
# JNI bridge library
# ============================================================
add_library(pocketnode SHARED
    pocketnode_jni.cpp
)

target_include_directories(pocketnode PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
)

target_link_libraries(pocketnode
    llama
    log
    android
)
