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

# 1. Build miniaudio wrapper
echo "==> Building libmidiraja_audio..."
MINIAUDIO_OUT="$NATIVE_LIBS/miniaudio"
mkdir -p "$MINIAUDIO_OUT"
cd "$MINIAUDIO_OUT"
if [ "$OS_FAMILY" = "macos" ]; then
    ${CC:-gcc} -shared -fPIC -O2 \
        -framework CoreAudio -framework AudioToolbox -framework AudioUnit -framework CoreFoundation \
        -o "libmidiraja_audio.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/miniaudio/midiraja_audio.c"
elif [ "$OS_FAMILY" = "windows" ]; then
    ${CC:-gcc} -shared -O2 \
        -lole32 -lpthread -lm \
        -o "libmidiraja_audio.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/miniaudio/midiraja_audio.c"
else
    ${CC:-gcc} -shared -fPIC -O2 \
        -ldl -lpthread -Wl,--no-as-needed -lm \
        -o "libmidiraja_audio.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/miniaudio/midiraja_audio.c"
fi

# 2. Build libmunt
echo "==> Building libmt32emu..."
MUNT_BUILD="$NATIVE_LIBS/munt"
mkdir -p "$MUNT_BUILD"
cd "$MUNT_BUILD"
cmake -G "$CMAKE_GENERATOR" $CMAKE_MAKE_FLAG -Dmt32emu_SHARED=ON "$PROJECT_ROOT/ext/munt/mt32emu"
$MAKE_CMD -j"$PARALLEL"

# 3. Build libADLMIDI
echo "==> Building libADLMIDI..."
ADLMIDI_BUILD="$NATIVE_LIBS/adlmidi"
mkdir -p "$ADLMIDI_BUILD"
cd "$ADLMIDI_BUILD"
cmake -G "$CMAKE_GENERATOR" $CMAKE_MAKE_FLAG \
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
$MAKE_CMD -j"$PARALLEL"

# 4. Build libOPNMIDI
echo "==> Building libOPNMIDI..."
OPNMIDI_BUILD="$NATIVE_LIBS/opnmidi"
mkdir -p "$OPNMIDI_BUILD"
cd "$OPNMIDI_BUILD"
cmake -G "$CMAKE_GENERATOR" $CMAKE_MAKE_FLAG \
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
$MAKE_CMD -j"$PARALLEL"

# 5. Build libtsf (TinySoundFont — single-header, no cmake needed)
echo "==> Building libtsf..."
TSF_OUT="$NATIVE_LIBS/tsf"
mkdir -p "$TSF_OUT"
cd "$TSF_OUT"
if [ "$OS_FAMILY" = "windows" ]; then
    ${CC:-gcc} -shared -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
        -o "libtsf.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/tsf/tsf_wrapper.c"
elif [ "$OS_FAMILY" = "macos" ]; then
    ${CC:-gcc} -shared -fPIC -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
        -o "libtsf.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/tsf/tsf_wrapper.c" \
        -lm
else
    # Linux: use --no-as-needed to force libm.so.6 into DT_NEEDED so that
    # log() and other math symbols are resolved at runtime. On glibc 2.38+,
    # log() lives in libm.so.6 (GLIBC_2.29) and static libm.a no longer
    # provides it, so dynamic linking is the only reliable approach.
    ${CC:-gcc} -shared -fPIC -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
        -o "libtsf.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/tsf/tsf_wrapper.c" \
        -Wl,--no-as-needed -lm
fi

# 6. Build macOS media keys wrapper (MPRemoteCommandCenter / MPNowPlayingInfoCenter)
if [ "$OS_FAMILY" = "macos" ]; then
    echo "==> Building libmidiraja_mediakeys..."
    MEDIAKEYS_OUT="$NATIVE_LIBS/mediakeys"
    mkdir -p "$MEDIAKEYS_OUT"
    clang -fobjc-arc -dynamiclib -O2 \
        -framework MediaPlayer -framework Foundation \
        -o "$MEDIAKEYS_OUT/libmidiraja_mediakeys.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/mediakeys/macos_media_session.m"
fi

# 7. Build Linux media keys wrapper (MPRIS2 D-Bus)
if [ "$OS_FAMILY" = "linux" ]; then
    echo "==> Building libmidiraja_mediakeys (Linux MPRIS2)..."
    MEDIAKEYS_OUT="$NATIVE_LIBS/mediakeys"
    mkdir -p "$MEDIAKEYS_OUT"
    gcc $(pkg-config --cflags --libs dbus-1) \
        -shared -fPIC \
        -o "$MEDIAKEYS_OUT/libmidiraja_mediakeys.so" \
        "$PROJECT_ROOT/src/main/c/mediakeys/linux_media_session.c"
    echo "  → $MEDIAKEYS_OUT/libmidiraja_mediakeys.so"
fi

echo "Native libraries built successfully → $NATIVE_LIBS"
