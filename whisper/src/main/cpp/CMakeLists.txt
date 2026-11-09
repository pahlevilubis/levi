cmake_minimum_required(VERSION 3.22.1)
project(levi_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Sumber whisper.cpp diambil oleh workflow GitHub ke folder third_party/
set(WHISPER_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp)
if(NOT EXISTS ${WHISPER_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp tidak ditemukan di ${WHISPER_SRC}")
endif()

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL             OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)

if(ANDROID)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_SRC} whisper-build)

add_library(levi_whisper SHARED levi_whisper.cpp)

target_include_directories(levi_whisper PRIVATE
        ${WHISPER_SRC}/include
        ${WHISPER_SRC}/ggml/include)

if(ANDROID)
    target_link_libraries(levi_whisper PRIVATE whisper android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(levi_whisper PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(levi_whisper PRIVATE whisper)
endif()

# Sembunyikan simbol ggml supaya tidak bentrok dengan library llama
target_link_options(levi_whisper PRIVATE "-Wl,--exclude-libs,ALL")
