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

# Vulkan GPU backend — requires Vulkan SDK (with glslc) installed on the build host.
if(CMAKE_HOST_WIN32)
    set(VULKAN_SDK "C:/VulkanSDK/1.4.341.1" CACHE PATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${VULKAN_SDK}/Bin/glslc.exe" CACHE FILEPATH "" FORCE)
    set(_ggml_vk_genshaders_cmd "${VULKAN_SDK}/Bin/vulkan-shaders-gen.exe" CACHE STRING "" FORCE)
    include_directories("${VULKAN_SDK}/Include")
else()
    find_program(Vulkan_GLSLC_EXECUTABLE glslc)
endif()

set(GGML_VULKAN ON CACHE BOOL "" FORCE)

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
    ${llama_cpp_SOURCE_DIR}/examples/llava
    ${llama_cpp_SOURCE_DIR}/common
    "${VULKAN_SDK}/Include"
)

# Also ensure ggml-vulkan sees the Vulkan headers if needed
include_directories("${VULKAN_SDK}/Include")

target_link_libraries(pocketnode
    llama
    log
    android
)

if(GGML_VULKAN)
    target_link_libraries(pocketnode vulkan)
endif()
