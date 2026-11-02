cmake_minimum_required(VERSION 3.22)
project(gguf_htp_tiled_converter LANGUAGES C CXX)

if (NOT DEFINED LLAMA_CPP_DIR OR NOT EXISTS "${LLAMA_CPP_DIR}/ggml/CMakeLists.txt")
    message(FATAL_ERROR "Set -DLLAMA_CPP_DIR=<pinned ggml-hexagon checkout>")
endif()
file(TO_CMAKE_PATH "${LLAMA_CPP_DIR}" LLAMA_CPP_DIR_NORMALIZED)

set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR_NORMALIZED}/ggml" ggml)

set(HEXAGON_DIR "${LLAMA_CPP_DIR_NORMALIZED}/ggml/src/ggml-hexagon")
add_executable(gguf-htp-tiled-convert
    main.cpp
    "${HEXAGON_DIR}/htp-repack.cpp")
target_compile_features(gguf-htp-tiled-convert PRIVATE cxx_std_17)
target_include_directories(gguf-htp-tiled-convert PRIVATE
    "${LLAMA_CPP_DIR_NORMALIZED}/ggml/include"
    "${LLAMA_CPP_DIR_NORMALIZED}/ggml/src"
    "${HEXAGON_DIR}"
    "${HEXAGON_DIR}/htp")
target_link_libraries(gguf-htp-tiled-convert PRIVATE ggml-base)
