cmake_minimum_required(VERSION 3.20)
project(mgba_frontend LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(MGBA_SOURCE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../mgba-source/mgba(mGBA-Libretro)"
    CACHE PATH "Path to the pinned mGBA Libretro source tree")
set(MGBA_LIBRETRO_INCLUDE_DIR "${MGBA_SOURCE_DIR}/src/platform/libretro")

if(NOT EXISTS "${MGBA_LIBRETRO_INCLUDE_DIR}/libretro.h")
    message(FATAL_ERROR
        "libretro.h not found under MGBA_SOURCE_DIR: ${MGBA_SOURCE_DIR}")
endif()

add_library(mgba_session STATIC
    src/retro_core_api.c
    src/emulator_session.c
    src/framebuffer.c
    src/audio_ring.c
    src/input_state.c
)
target_include_directories(mgba_session
    PUBLIC include "${MGBA_LIBRETRO_INCLUDE_DIR}")

if(ANDROID)
    add_library(mgba_frontend SHARED src/jni_bridge.c)
    target_include_directories(mgba_frontend PRIVATE "${MGBA_LIBRETRO_INCLUDE_DIR}")
    target_link_libraries(mgba_frontend PRIVATE mgba_session dl log)
    # Android 15+ devices may use 16 KiB pages.
    target_link_options(mgba_frontend PRIVATE "-Wl,-z,max-page-size=16384")
elseif(WIN32)
    # Windows JNI frontend. The Libretro core itself is a separate DLL loaded
    # at runtime by retro_core_api.c through LoadLibraryA().
    if(NOT JAVA_HOME)
        message(FATAL_ERROR
            "JAVA_HOME is required to build the Windows JNI frontend "
            "(jni.h and jni_md.h).")
    endif()
    if(NOT EXISTS "${JAVA_HOME}/include/jni.h")
        message(FATAL_ERROR "jni.h not found under JAVA_HOME=${JAVA_HOME}")
    endif()

    add_library(mgba_frontend SHARED src/jni_bridge.c)
    target_include_directories(mgba_frontend PRIVATE
        "${MGBA_LIBRETRO_INCLUDE_DIR}"
        "${JAVA_HOME}/include"
        "${JAVA_HOME}/include/win32")
    target_link_libraries(mgba_frontend PRIVATE mgba_session)
    set_target_properties(mgba_frontend PROPERTIES
        PREFIX ""
        OUTPUT_NAME "mgba_frontend")
    if(MINGW)
        # Avoid a hard dependency on libgcc_s_seh-1.dll next to the frontend.
        target_link_options(mgba_frontend PRIVATE "-static-libgcc")
    endif()
endif()

include(CTest)
if(BUILD_TESTING AND NOT ANDROID)
    add_library(fake_core SHARED tests/fake_core.c)
    target_include_directories(fake_core PRIVATE "${MGBA_LIBRETRO_INCLUDE_DIR}")

    add_library(partial_core SHARED tests/partial_core.c)
    target_include_directories(partial_core PRIVATE "${MGBA_LIBRETRO_INCLUDE_DIR}")

    add_executable(test_session tests/test_session.c)
    target_link_libraries(test_session PRIVATE mgba_session)
    add_test(NAME test_session COMMAND test_session)

    add_executable(test_core_loader tests/test_core_loader.c)
    target_link_libraries(test_core_loader PRIVATE mgba_session)
    target_compile_definitions(test_core_loader PRIVATE
        "FAKE_CORE_PATH=\"$<TARGET_FILE:fake_core>\""
        "PARTIAL_CORE_PATH=\"$<TARGET_FILE:partial_core>\"")
    add_dependencies(test_core_loader fake_core partial_core)
    add_test(NAME test_core_loader COMMAND test_core_loader)
endif()
