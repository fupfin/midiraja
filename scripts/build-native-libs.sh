#!/bin/bash
set -e

PROJECT_ROOT="$(pwd)"
echo "Building Native Libraries for Midiraja..."

# Detect OS and arch (matches AudioLibResolver / AbstractFFMBridge naming)
CMAKE_MAKE_FLAG=""
if [[ "$OSTYPE" == "darwin"* ]]; then
    OS_FAMILY="macos"
    LIB_EXT="dylib"
    PARALLEL=$(sysctl -n hw.ncpu)
    CMAKE_GENERATOR="Unix Makefiles"
    MAKE_CMD="make"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS_FAMILY="linux"
    LIB_EXT="so"
    PARALLEL=$(nproc)
    CMAKE_GENERATOR="Unix Makefiles"
    MAKE_CMD="make"
elif [[ "$OSTYPE" == "msys"* || "$OSTYPE" == "mingw"* || "$OSTYPE" == "cygwin"* ]]; then
    OS_FAMILY="windows"
    LIB_EXT="dll"
    PARALLEL=$NUMBER_OF_PROCESSORS
    if command -v mingw32-make &>/dev/null; then
        CMAKE_GENERATOR="MinGW Makefiles"
        MAKE_CMD="mingw32-make"
        MAKE_PROGRAM="$(which mingw32-make)"
    elif command -v make &>/dev/null; then
        CMAKE_GENERATOR="MSYS Makefiles"
        MAKE_CMD="make"
        MAKE_PROGRAM="$(which make)"
    else
        echo "ERROR: neither mingw32-make nor make found in PATH."
        echo "  Open 'MSYS2 MinGW x64' from the Start menu (NOT 'MSYS2 MSYS')."
        echo "  Or install: pacman -S mingw-w64-x86_64-cmake make"
        exit 1
    fi
    CMAKE_MAKE_FLAG="-DCMAKE_MAKE_PROGRAM=$MAKE_PROGRAM"
else
    echo "Unsupported OS: $OSTYPE"
    exit 1
fi

ARCH=$(uname -m)
case "$ARCH" in
    arm64|ARM64|aarch64) ARCH="aarch64" ;;
    x86_64|AMD64)        ARCH="x86_64"  ;;
esac

NATIVE_LIBS="$PROJECT_ROOT/build/native-libs/$OS_FAMILY-$ARCH"
echo "Output directory: $NATIVE_LIBS"

# Returns 0 (true) if $1 output is missing or older than any of the source files $2..
needs_rebuild() {
    local output="$1"; shift
    [ ! -f "$output" ] && return 0
    for src in "$@"; do
        [ "$src" -nt "$output" ] && return 0
    done
    return 1
}

# Runs cmake configure only when CMakeCache.txt is absent, then always runs make.
# cmake configure is slow (~1s); make is fast when sources are unchanged.
cmake_build() {
    local build_dir="$1"; shift
    mkdir -p "$build_dir"
    cd "$build_dir"
    if [ ! -f "$build_dir/CMakeCache.txt" ]; then
        cmake -G "$CMAKE_GENERATOR" $CMAKE_MAKE_FLAG "$@"
    fi
    $MAKE_CMD -j"$PARALLEL"
}

# 1. Build miniaudio wrapper
MINIAUDIO_OUT="$NATIVE_LIBS/miniaudio"
MINIAUDIO_SRC="$PROJECT_ROOT/src/main/c/miniaudio/midiraja_audio.c"
MINIAUDIO_LIB="$MINIAUDIO_OUT/libmidiraja_audio.$LIB_EXT"
if needs_rebuild "$MINIAUDIO_LIB" "$MINIAUDIO_SRC"; then
    echo "==> Building libmidiraja_audio..."
    mkdir -p "$MINIAUDIO_OUT"
    cd "$MINIAUDIO_OUT"
    if [ "$OS_FAMILY" = "macos" ]; then
        ${CC:-gcc} -shared -fPIC -O2 \
            -framework CoreAudio -framework AudioToolbox -framework AudioUnit -framework CoreFoundation \
            -o "libmidiraja_audio.$LIB_EXT" \
            "$MINIAUDIO_SRC"
    elif [ "$OS_FAMILY" = "windows" ]; then
        ${CC:-gcc} -shared -O2 \
            -lole32 -lpthread -lm \
            -o "libmidiraja_audio.$LIB_EXT" \
            "$MINIAUDIO_SRC"
    else
        ${CC:-gcc} -shared -fPIC -O2 \
            -ldl -lpthread -Wl,--no-as-needed -lm \
            -o "libmidiraja_audio.$LIB_EXT" \
            "$MINIAUDIO_SRC"
    fi
else
    echo "==> libmidiraja_audio up-to-date, skipping."
fi

# 2. Build libmunt
echo "==> Building libmt32emu..."
cmake_build "$NATIVE_LIBS/munt" -Dmt32emu_SHARED=ON "$PROJECT_ROOT/ext/munt/mt32emu"

# 3. Build libADLMIDI
echo "==> Building libADLMIDI..."
cmake_build "$NATIVE_LIBS/adlmidi" \
    -DCMAKE_BUILD_TYPE=Release \
    -DlibADLMIDI_SHARED=ON \
    -DlibADLMIDI_STATIC=ON \
    -DWITH_EMBEDDED_BANKS=ON \
    -DWITH_MUS_SUPPORT=OFF \
    -DWITH_XMI_SUPPORT=OFF \
    -DUSE_DOSBOX_EMULATOR=OFF \
    -DUSE_OPAL_EMULATOR=OFF \
    -DUSE_JAVA_EMULATOR=OFF \
    "$PROJECT_ROOT/ext/libADLMIDI"

# 4. Build libOPNMIDI
echo "==> Building libOPNMIDI..."
cmake_build "$NATIVE_LIBS/opnmidi" \
    -DCMAKE_BUILD_TYPE=Release \
    -DlibOPNMIDI_SHARED=ON \
    -DlibOPNMIDI_STATIC=ON \
    -DWITH_MIDI_SEQUENCER=OFF \
    -DWITH_XMI_SUPPORT=OFF \
    -DUSE_GENS_EMULATOR=OFF \
    -DUSE_NUKED_OPN2_LLE_EMULATOR=OFF \
    -DUSE_NUKED_OPNA_LLE_EMULATOR=OFF \
    -DUSE_VGM_FILE_DUMPER=OFF \
    "$PROJECT_ROOT/ext/libOPNMIDI"

# 5. Build libtsf (TinySoundFont — single-header, no cmake needed)
TSF_OUT="$NATIVE_LIBS/tsf"
TSF_SRC="$PROJECT_ROOT/src/main/c/tsf/tsf_wrapper.c"
TSF_HDR="$PROJECT_ROOT/ext/TinySoundFont/tsf.h"
TSF_LIB="$TSF_OUT/libtsf.$LIB_EXT"
if needs_rebuild "$TSF_LIB" "$TSF_SRC" "$TSF_HDR"; then
    echo "==> Building libtsf..."
    mkdir -p "$TSF_OUT"
    cd "$TSF_OUT"
    if [ "$OS_FAMILY" = "windows" ]; then
        ${CC:-gcc} -shared -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
            -o "libtsf.$LIB_EXT" \
            "$TSF_SRC"
    elif [ "$OS_FAMILY" = "macos" ]; then
        ${CC:-gcc} -shared -fPIC -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
            -o "libtsf.$LIB_EXT" \
            "$TSF_SRC" \
            -lm
    else
        # Linux: use --no-as-needed to force libm.so.6 into DT_NEEDED so that
        # log() and other math symbols are resolved at runtime. On glibc 2.38+,
        # log() lives in libm.so.6 (GLIBC_2.29) and static libm.a no longer
        # provides it, so dynamic linking is the only reliable approach.
        ${CC:-gcc} -shared -fPIC -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
            -o "libtsf.$LIB_EXT" \
            "$TSF_SRC" \
            -Wl,--no-as-needed -lm
    fi
else
    echo "==> libtsf up-to-date, skipping."
fi

# 6. Build macOS media keys wrapper (MPRemoteCommandCenter / MPNowPlayingInfoCenter)
if [ "$OS_FAMILY" = "macos" ]; then
    MEDIAKEYS_SRC="$PROJECT_ROOT/src/main/c/mediakeys/macos_media_session.m"
    MEDIAKEYS_LIB="$NATIVE_LIBS/mediakeys/libmidiraja_mediakeys.$LIB_EXT"
    if needs_rebuild "$MEDIAKEYS_LIB" "$MEDIAKEYS_SRC"; then
        echo "==> Building libmidiraja_mediakeys..."
        mkdir -p "$NATIVE_LIBS/mediakeys"
        clang -fobjc-arc -dynamiclib -O2 \
            -framework MediaPlayer -framework Foundation \
            -o "$MEDIAKEYS_LIB" \
            "$MEDIAKEYS_SRC"
    else
        echo "==> libmidiraja_mediakeys up-to-date, skipping."
    fi
fi

# 7. Build Linux media keys wrapper (MPRIS2 D-Bus)
if [ "$OS_FAMILY" = "linux" ]; then
    MEDIAKEYS_SRC="$PROJECT_ROOT/src/main/c/mediakeys/linux_media_session.c"
    MEDIAKEYS_LIB="$NATIVE_LIBS/mediakeys/libmidiraja_mediakeys.so"
    if needs_rebuild "$MEDIAKEYS_LIB" "$MEDIAKEYS_SRC"; then
        echo "==> Building libmidiraja_mediakeys (Linux MPRIS2)..."
        mkdir -p "$NATIVE_LIBS/mediakeys"
        gcc $(pkg-config --cflags --libs dbus-1) \
            -shared -fPIC \
            -o "$MEDIAKEYS_LIB" \
            "$MEDIAKEYS_SRC"
        echo "  → $MEDIAKEYS_LIB"
    else
        echo "==> libmidiraja_mediakeys up-to-date, skipping."
    fi
fi

# 8. Build Windows media keys wrapper (SystemMediaTransportControls)
if [ "$OS_FAMILY" = "windows" ]; then
    MEDIAKEYS_SRC="$PROJECT_ROOT/src/main/c/mediakeys/windows_media_session.cpp"
    MEDIAKEYS_LIB="$NATIVE_LIBS/mediakeys/midiraja_mediakeys.dll"
    if needs_rebuild "$MEDIAKEYS_LIB" "$MEDIAKEYS_SRC"; then
        echo "==> Building midiraja_mediakeys.dll (Windows SMTC)..."
        mkdir -p "$NATIVE_LIBS/mediakeys"
        cl /std:c++17 /EHsc \
            "$MEDIAKEYS_SRC" \
            /link WindowsApp.lib Ole32.lib \
            /DLL /OUT:"$MEDIAKEYS_LIB"
        echo "  → $MEDIAKEYS_LIB"
    else
        echo "==> midiraja_mediakeys.dll up-to-date, skipping."
    fi
fi

echo "Native libraries built successfully → $NATIVE_LIBS"
