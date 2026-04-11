# CMakeLists.txt — libmidiraja_vgm shared library
#
# Wraps ValleyBell/libvgm PlayerA with a thin extern-C API consumed by
# FFMLibvgmBridge via Java Panama FFM.
#
# Prerequisites:
#   git submodule add https://github.com/ValleyBell/libvgm.git <project_root>/ext/libvgm
#
# Usage (invoked by scripts/build-native-libs.sh or the Gradle buildVgmLib task):
#   cmake -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Release <path_to_this_dir>
#   make -j$(nproc)

cmake_minimum_required(VERSION 3.16)
project(midiraja_vgm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── libvgm source root (three levels up: src/main/c/libvgm → project root) ───
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(LIBVGM_ROOT "${PROJECT_ROOT}/ext/libvgm")

if(NOT EXISTS "${LIBVGM_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "ext/libvgm not found. Run:\n"
        "  git submodule add https://github.com/ValleyBell/libvgm.git ext/libvgm\n"
        "  git submodule update --init ext/libvgm")
endif()

# ── Build libvgm as a sub-project (static libs only, no tests/tools) ──────────
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PLAYER      ON  CACHE BOOL "" FORCE)
set(BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)

add_subdirectory("${LIBVGM_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/libvgm" EXCLUDE_FROM_ALL)

# ── Shared wrapper library ────────────────────────────────────────────────────
add_library(midiraja_vgm SHARED vgm_bridge.cpp)

target_include_directories(midiraja_vgm PRIVATE "${LIBVGM_ROOT}")

# Link against the static libvgm player and emulator libraries
target_link_libraries(midiraja_vgm PRIVATE
    vgm-player
    vgm-emu
    vgm-utils
)

# ── Output naming: libmidiraja_vgm.{dylib,so,dll} ─────────────────────────────
set_target_properties(midiraja_vgm PROPERTIES
    OUTPUT_NAME  "midiraja_vgm"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
)

# macOS: set install name for @rpath resolution
if(APPLE)
    set_target_properties(midiraja_vgm PROPERTIES
        INSTALL_NAME_DIR "@rpath"
        BUILD_WITH_INSTALL_RPATH TRUE
    )
endif()
