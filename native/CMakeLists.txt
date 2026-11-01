cmake_minimum_required(VERSION 3.22.1)
project(gemma4_jni LANGUAGES C CXX)

if (NOT DEFINED LLAMA_CPP_DIR OR NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Set -DLLAMA_CPP_DIR=<path to pinned ggml-hexagon checkout>")
endif()

if (NOT DEFINED GEMMA4_HEXAGON_ASSET_DIR OR GEMMA4_HEXAGON_ASSET_DIR STREQUAL "")
    message(FATAL_ERROR "Set -DGEMMA4_HEXAGON_ASSET_DIR=<generated Android assets directory>")
endif()

# Hexagon is compiled into ggml and registered by ggml-backend-reg.cpp when
# GGML_HEXAGON is enabled. The transport is a build-time identity: AP libraries
# and the matching HTP skeleton must never be mixed at runtime.
set(GEMMA4_HEXAGON_BACKEND "cpu" CACHE STRING "Runtime backend: cpu, fastrpc-mempool, or dspqueue")
set_property(CACHE GEMMA4_HEXAGON_BACKEND PROPERTY STRINGS cpu fastrpc-mempool dspqueue)
if (NOT GEMMA4_HEXAGON_BACKEND MATCHES "^(cpu|fastrpc-mempool|dspqueue)$")
    message(FATAL_ERROR "GEMMA4_HEXAGON_BACKEND must be cpu, fastrpc-mempool, or dspqueue")
endif()

if (GEMMA4_HEXAGON_BACKEND STREQUAL "cpu")
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
else()
    if (NOT DEFINED HEXAGON_SDK_ROOT OR NOT IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
        message(FATAL_ERROR "Hexagon backend requires -DHEXAGON_SDK_ROOT=<licensed Hexagon SDK 6.7.0.0 path>")
    endif()
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    set(GGML_HEXAGON_HTP_BUILD_TYPE Release CACHE STRING "" FORCE)
    # Keep the backend in-process so llama's ggml registry sees the HTP
    # registration at startup. Dynamic backend loading is not wired here.
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    if (GEMMA4_HEXAGON_BACKEND STREQUAL "fastrpc-mempool")
        set(GGML_HEXAGON_USE_MEMPOOL ON CACHE BOOL "" FORCE)
    else()
        set(GGML_HEXAGON_USE_MEMPOOL OFF CACHE BOOL "" FORCE)
    endif()
endif()

# llama-common provides the Jinja renderer and Gemma 4's specialized chat
# template handler. llama_chat_apply_template() in libllama is only a
# heuristic formatter and cannot render arbitrary GGUF templates.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
add_subdirectory("${LLAMA_CPP_DIR}" llama-build)

add_library(gemma4_jni SHARED gemma4_jni.cpp)
target_compile_features(gemma4_jni PRIVATE cxx_std_17)
target_include_directories(gemma4_jni PRIVATE "${LLAMA_CPP_DIR}/include" "${LLAMA_CPP_DIR}/ggml/include")
target_compile_definitions(gemma4_jni PRIVATE GEMMA4_HEXAGON_BACKEND_NAME="${GEMMA4_HEXAGON_BACKEND}")
find_library(log-lib log)
target_link_libraries(gemma4_jni PRIVATE llama-common llama ${log-lib})

if (NOT GEMMA4_HEXAGON_BACKEND STREQUAL "cpu")
    if (NOT TARGET ggml-hexagon)
        message(FATAL_ERROR "Hexagon was requested but the pinned source did not create target ggml-hexagon")
    endif()

    # The DSP skeleton is not an Android/arm64 shared library. Keep it out of
    # jniLibs and package it as an asset for the explicit device deployment workflow.
    if (GEMMA4_HEXAGON_BACKEND STREQUAL "fastrpc-mempool")
        set(GEMMA4_HTP_V75_TARGET htp-mempool-v75)
        set(GEMMA4_HTP_V75_SOURCE "${CMAKE_BINARY_DIR}/bin/libggml-htp-v75.so")
        set(GEMMA4_TRANSPORT_CONFIG "${LLAMA_CPP_DIR}/scripts/ggml-hexagon.cfg")
    else()
        set(GEMMA4_HTP_V75_TARGET htp-v75)
        set(GEMMA4_HTP_V75_SOURCE "${CMAKE_BINARY_DIR}/llama-build/ggml/src/ggml-hexagon/libggml-htp-v75.so")
    endif()

    if (NOT TARGET ${GEMMA4_HTP_V75_TARGET})
        message(FATAL_ERROR "Pinned Hexagon source is missing expected HTP target ${GEMMA4_HTP_V75_TARGET}")
    endif()

    file(MAKE_DIRECTORY "${GEMMA4_HEXAGON_ASSET_DIR}/hexagon")
    file(WRITE "${GEMMA4_HEXAGON_ASSET_DIR}/hexagon/backend.properties"
        "backend=${GEMMA4_HEXAGON_BACKEND}\nupstream_commit=a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d\ndsp=HTP-v75\nabi=arm64-v8a\n")

    # Use an ALL target rather than POST_BUILD on gemma4_jni: ExternalProject
    # uses BUILD_ALWAYS, and the asset must be refreshed even when JNI itself
    # is already up to date.
    add_custom_target(gemma4_stage_hexagon_assets ALL
        COMMAND ${CMAKE_COMMAND} -E make_directory "${GEMMA4_HEXAGON_ASSET_DIR}/hexagon/dsp"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${GEMMA4_HTP_V75_SOURCE}"
            "${GEMMA4_HEXAGON_ASSET_DIR}/hexagon/dsp/libggml-htp-v75.so"
        DEPENDS ${GEMMA4_HTP_V75_TARGET}
        VERBATIM
        COMMENT "Staging matching ${GEMMA4_HEXAGON_BACKEND} HTP v75 DSP asset")

    if (GEMMA4_TRANSPORT_CONFIG)
        add_custom_target(gemma4_stage_fastrpc_config ALL
            COMMAND ${CMAKE_COMMAND} -E make_directory "${GEMMA4_HEXAGON_ASSET_DIR}/hexagon/ap"
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${GEMMA4_TRANSPORT_CONFIG}"
                "${GEMMA4_HEXAGON_ASSET_DIR}/hexagon/ap/ggml-hexagon.cfg"
            DEPENDS gemma4_stage_hexagon_assets
            VERBATIM
            COMMENT "Staging FastRPC runtime configuration")
        add_dependencies(gemma4_jni gemma4_stage_fastrpc_config)
    else()
        # AGP reuses this generated asset directory across transport variants.
        # Remove any previous FastRPC cfg so it cannot leak into dspqueue APKs.
        file(REMOVE "${GEMMA4_HEXAGON_ASSET_DIR}/hexagon/ap/ggml-hexagon.cfg")
        add_dependencies(gemma4_jni gemma4_stage_hexagon_assets)
    endif()
endif()
