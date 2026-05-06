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

# ============================================================
# OpenCL headers for Android cross-compilation
# libOpenCL.so is provided at runtime by the Adreno driver;
# CMake only needs the headers and a library name to link against.
# ============================================================
if(ANDROID AND GGML_OPENCL)
    FetchContent_Declare(
        opencl_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG        v2023.12.14
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(opencl_headers)

    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_INCLUDE_DIRS "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)

    # ICD-style forwarding stub — each function lazily dlopen's the real Adreno
    # libOpenCL.so and forwards with correct arguments/return values.
    # The empty-void-stub approach caused clGetPlatformIDs to return nothing,
    # leaving n_platforms=0 and the backend silently detecting 0 GPU devices.
    set(OPENCL_STUB_C "${CMAKE_CURRENT_BINARY_DIR}/opencl_stub.c")
    file(WRITE "${OPENCL_STUB_C}" [=[
#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_1_APIS
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#define CL_USE_DEPRECATED_OPENCL_2_0_APIS
#include <CL/cl.h>
#include <dlfcn.h>
#include <stddef.h>

static void* _h = 0;
static void* _sym(const char* n) {
    if (!_h) {
        _h = dlopen("libOpenCL.so", RTLD_NOW|RTLD_GLOBAL);
        if (!_h) _h = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW|RTLD_GLOBAL);
        if (!_h) _h = dlopen("/system/vendor/lib64/libOpenCL.so", RTLD_NOW|RTLD_GLOBAL);
        if (!_h) _h = dlopen("/system/lib64/libOpenCL.so", RTLD_NOW|RTLD_GLOBAL);
    }
    return _h ? dlsym(_h, n) : 0;
}
#define FWD(ret,name,params,args,err) \
    ret name params { \
        typedef ret(*_f)params; static _f p=0; \
        if(!p) p=(_f)_sym(#name); return p?p args:err; }
#define FWD_V(name,params,args) \
    void name params { \
        typedef void(*_f)params; static _f p=0; \
        if(!p) p=(_f)_sym(#name); if(p) p args; }

FWD(cl_int,clGetPlatformIDs,(cl_uint a,cl_platform_id*b,cl_uint*c),(a,b,c),CL_OUT_OF_RESOURCES)
FWD(cl_int,clGetPlatformInfo,(cl_platform_id a,cl_platform_info b,size_t c,void*d,size_t*e),(a,b,c,d,e),CL_OUT_OF_RESOURCES)
FWD(cl_int,clGetDeviceIDs,(cl_platform_id a,cl_device_type b,cl_uint c,cl_device_id*d,cl_uint*e),(a,b,c,d,e),CL_OUT_OF_RESOURCES)
FWD(cl_int,clGetDeviceInfo,(cl_device_id a,cl_device_info b,size_t c,void*d,size_t*e),(a,b,c,d,e),CL_OUT_OF_RESOURCES)
FWD(cl_context,clCreateContext,(const cl_context_properties*a,cl_uint b,const cl_device_id*c,void(CL_CALLBACK*d)(const char*,const void*,size_t,void*),void*e,cl_int*f),(a,b,c,d,e,f),0)
FWD(cl_command_queue,clCreateCommandQueue,(cl_context a,cl_device_id b,cl_command_queue_properties c,cl_int*d),(a,b,c,d),0)
FWD(cl_mem,clCreateBuffer,(cl_context a,cl_mem_flags b,size_t c,void*d,cl_int*e),(a,b,c,d,e),0)
FWD(cl_mem,clCreateBufferWithProperties,(cl_context a,const cl_mem_properties*b,cl_mem_flags c,size_t d,void*e,cl_int*f),(a,b,c,d,e,f),0)
FWD(cl_mem,clCreateSubBuffer,(cl_mem a,cl_mem_flags b,cl_buffer_create_type c,const void*d,cl_int*e),(a,b,c,d,e),0)
FWD(cl_mem,clCreateImage,(cl_context a,cl_mem_flags b,const cl_image_format*c,const cl_image_desc*d,void*e,cl_int*f),(a,b,c,d,e,f),0)
FWD(cl_program,clCreateProgramWithSource,(cl_context a,cl_uint b,const char**c,const size_t*d,cl_int*e),(a,b,c,d,e),0)
FWD(cl_int,clBuildProgram,(cl_program a,cl_uint b,const cl_device_id*c,const char*d,void(CL_CALLBACK*e)(cl_program,void*),void*f),(a,b,c,d,e,f),CL_OUT_OF_RESOURCES)
FWD(cl_int,clGetProgramBuildInfo,(cl_program a,cl_device_id b,cl_program_build_info c,size_t d,void*e,size_t*f),(a,b,c,d,e,f),CL_OUT_OF_RESOURCES)
FWD(cl_kernel,clCreateKernel,(cl_program a,const char*b,cl_int*c),(a,b,c),0)
FWD(cl_int,clGetKernelInfo,(cl_kernel a,cl_kernel_info b,size_t c,void*d,size_t*e),(a,b,c,d,e),CL_OUT_OF_RESOURCES)
FWD(cl_int,clGetKernelWorkGroupInfo,(cl_kernel a,cl_device_id b,cl_kernel_work_group_info c,size_t d,void*e,size_t*f),(a,b,c,d,e,f),CL_OUT_OF_RESOURCES)
FWD(cl_int,clGetKernelSubGroupInfo,(cl_kernel a,cl_device_id b,cl_kernel_sub_group_info c,size_t d,const void*e,size_t f,void*g,size_t*h),(a,b,c,d,e,f,g,h),CL_OUT_OF_RESOURCES)
FWD(cl_int,clSetKernelArg,(cl_kernel a,cl_uint b,size_t c,const void*d),(a,b,c,d),CL_OUT_OF_RESOURCES)
FWD(cl_int,clEnqueueNDRangeKernel,(cl_command_queue a,cl_kernel b,cl_uint c,const size_t*d,const size_t*e,const size_t*f,cl_uint g,const cl_event*h,cl_event*i),(a,b,c,d,e,f,g,h,i),CL_OUT_OF_RESOURCES)
FWD(cl_int,clEnqueueReadBuffer,(cl_command_queue a,cl_mem b,cl_bool c,size_t d,size_t e,void*f,cl_uint g,const cl_event*h,cl_event*i),(a,b,c,d,e,f,g,h,i),CL_OUT_OF_RESOURCES)
FWD(cl_int,clEnqueueWriteBuffer,(cl_command_queue a,cl_mem b,cl_bool c,size_t d,size_t e,const void*f,cl_uint g,const cl_event*h,cl_event*i),(a,b,c,d,e,f,g,h,i),CL_OUT_OF_RESOURCES)
FWD(cl_int,clEnqueueCopyBuffer,(cl_command_queue a,cl_mem b,cl_mem c,size_t d,size_t e,size_t f,cl_uint g,const cl_event*h,cl_event*i),(a,b,c,d,e,f,g,h,i),CL_OUT_OF_RESOURCES)
FWD(cl_int,clEnqueueFillBuffer,(cl_command_queue a,cl_mem b,const void*c,size_t d,size_t e,size_t f,cl_uint g,const cl_event*h,cl_event*i),(a,b,c,d,e,f,g,h,i),CL_OUT_OF_RESOURCES)
FWD(cl_int,clEnqueueBarrierWithWaitList,(cl_command_queue a,cl_uint b,const cl_event*c,cl_event*d),(a,b,c,d),CL_OUT_OF_RESOURCES)
FWD(cl_int,clEnqueueMarkerWithWaitList,(cl_command_queue a,cl_uint b,const cl_event*c,cl_event*d),(a,b,c,d),CL_OUT_OF_RESOURCES)
FWD(cl_int,clGetEventProfilingInfo,(cl_event a,cl_profiling_info b,size_t c,void*d,size_t*e),(a,b,c,d,e),CL_OUT_OF_RESOURCES)
FWD(cl_int,clFinish,(cl_command_queue a),(a),CL_OUT_OF_RESOURCES)
FWD(cl_int,clFlush,(cl_command_queue a),(a),CL_OUT_OF_RESOURCES)
FWD(cl_int,clReleaseContext,(cl_context a),(a),CL_OUT_OF_RESOURCES)
FWD(cl_int,clReleaseMemObject,(cl_mem a),(a),CL_OUT_OF_RESOURCES)
FWD(cl_int,clReleaseProgram,(cl_program a),(a),CL_OUT_OF_RESOURCES)
FWD(cl_int,clReleaseEvent,(cl_event a),(a),CL_OUT_OF_RESOURCES)
FWD(cl_int,clWaitForEvents,(cl_uint a,const cl_event*b),(a,b),CL_OUT_OF_RESOURCES)
]=])
    # Create OpenCL stub as SHARED library so Android packages it separately
    add_library(OpenCL SHARED "${OPENCL_STUB_C}")
    target_include_directories(OpenCL PRIVATE "${opencl_headers_SOURCE_DIR}")
    set(OpenCL_LIBRARY OpenCL CACHE FILEPATH "" FORCE)
    set(OpenCL_LIBRARIES OpenCL CACHE STRING "" FORCE)
    set(OpenCL_FOUND TRUE CACHE BOOL "" FORCE)
endif()

FetchContent_MakeAvailable(llama_cpp)

# ============================================================
# JNI bridge library
# ============================================================
add_library(pocketnode SHARED
    pocketnode_jni.cpp
)

target_include_directories(pocketnode PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/common
)

target_link_libraries(pocketnode
    llama
    log
    android
)

# Link against the OpenCL stub if GGML_OPENCL is enabled
if(ANDROID AND GGML_OPENCL)
    target_link_libraries(pocketnode OpenCL)
endif()

# Fix for "undefined symbol: clGetPlatformIDs"
# The stub libOpenCL.so in opencl-stub/ is missing symbols, but they exist on the device.
# We allow undefined symbols at link time; they will be resolved at runtime from libOpenCL.so.
if(ANDROID)
    target_link_options(pocketnode PRIVATE "-Wl,--allow-shlib-undefined")
    target_link_options(pocketnode PRIVATE "-Wl,--unresolved-symbols=ignore-in-shared-libs")
endif()
