#!/bin/bash
set -e

PROJECT_ROOT="$(pwd)"
echo "Building Native Libraries for Midiraja..."

# Detect OS and arch (matches AudioLibResolver / AbstractFFMBridge naming)
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
    CMAKE_GENERATOR="MinGW Makefiles"
    MAKE_CMD="mingw32-make"
else
    echo "Unsupported OS: $OSTYPE"
    exit 1
fi

ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ]; then ARCH="aarch64"; fi
if [ "$ARCH" = "x86_64" ]; then ARCH="x86_64"; fi

NATIVE_LIBS="$PROJECT_ROOT/build/native-libs/$OS_FAMILY-$ARCH"
echo "Output directory: $NATIVE_LIBS"

# 1. Build miniaudio wrapper
echo "==> Building libmidiraja_audio..."
MINIAUDIO_OUT="$NATIVE_LIBS/miniaudio"
mkdir -p "$MINIAUDIO_OUT"
if [ "$OS_FAMILY" = "macos" ]; then
    gcc -shared -fPIC -O2 \
        -framework CoreAudio -framework AudioToolbox -framework AudioUnit -framework CoreFoundation \
        -o "$MINIAUDIO_OUT/libmidiraja_audio.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/miniaudio/midiraja_audio.c"
elif [ "$OS_FAMILY" = "windows" ]; then
    gcc -shared -O2 \
        -lole32 -lpthread -lm \
        -o "$MINIAUDIO_OUT/libmidiraja_audio.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/miniaudio/midiraja_audio.c"
else
    gcc -shared -fPIC -O2 \
        -ldl -lpthread -lm \
        -o "$MINIAUDIO_OUT/libmidiraja_audio.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/miniaudio/midiraja_audio.c"
fi

# 2. Build libmunt
echo "==> Building libmt32emu..."
MUNT_BUILD="$NATIVE_LIBS/munt"
mkdir -p "$MUNT_BUILD"
cd "$MUNT_BUILD"
cmake -G "$CMAKE_GENERATOR" -Dmt32emu_SHARED=ON "$PROJECT_ROOT/ext/munt/mt32emu"
$MAKE_CMD -j"$PARALLEL"

# 3. Build libADLMIDI
echo "==> Building libADLMIDI..."
ADLMIDI_BUILD="$NATIVE_LIBS/adlmidi"
mkdir -p "$ADLMIDI_BUILD"
cd "$ADLMIDI_BUILD"
cmake -G "$CMAKE_GENERATOR" \
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
cmake -G "$CMAKE_GENERATOR" \
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
if [ "$OS_FAMILY" = "windows" ]; then
    gcc -shared -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
        -o "$TSF_OUT/libtsf.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/tsf/tsf_wrapper.c"
else
    gcc -shared -fPIC -O2 -I"$PROJECT_ROOT/ext/TinySoundFont" \
        -o "$TSF_OUT/libtsf.$LIB_EXT" \
        "$PROJECT_ROOT/src/main/c/tsf/tsf_wrapper.c"
fi

echo "Native libraries built successfully → $NATIVE_LIBS"
