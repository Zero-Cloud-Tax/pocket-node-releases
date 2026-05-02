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

# Enable mmap so models stream from storage instead of loading fully into RAM
set(LLAMA_MMAP ON CACHE BOOL "" FORCE)

# Vulkan GPU backend. The Android target links against the device Vulkan loader
# while shader generation uses the host Vulkan SDK.
option(POCKETNODE_ENABLE_VULKAN "Build llama.cpp with Vulkan GPU offload" ON)

if(POCKETNODE_ENABLE_VULKAN AND CMAKE_HOST_WIN32 AND EXISTS "C:/VulkanSDK/1.4.341.1")
    set(VULKAN_SDK "C:/VulkanSDK/1.4.341.1" CACHE PATH "" FORCE)
    set(ENV{VULKAN_SDK} "${VULKAN_SDK}")
    set(Vulkan_GLSLC_EXECUTABLE "${VULKAN_SDK}/Bin/glslc.exe" CACHE FILEPATH "" FORCE)
    include_directories("${VULKAN_SDK}/Include")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

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

if(DEFINED VULKAN_SDK)
    target_include_directories(pocketnode PRIVATE "${VULKAN_SDK}/Include")
endif()

target_link_libraries(pocketnode
    llama
    log
    android
)

if(GGML_VULKAN)
    target_link_libraries(pocketnode vulkan)
endif()
