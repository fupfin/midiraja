#!/bin/bash
set -e

PROJECT_ROOT="$(pwd)"
echo "Building Native Libraries for Midiraja..."

# 1. Build miniaudio wrapper
echo "==> Building libmidiraja_audio..."
cd "$PROJECT_ROOT/src/main/c/miniaudio"
if [[ "$OSTYPE" == "darwin"* ]]; then
    gcc -shared -fPIC -O2 -o libmidiraja_audio.dylib midiraja_audio.c
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    gcc -shared -fPIC -O2 -o libmidiraja_audio.so midiraja_audio.c
else
    echo "Unsupported OS for automated miniaudio build. Please build manually."
fi

# 2. Build libmunt
echo "==> Building libmt32emu..."
mkdir -p "$PROJECT_ROOT/src/main/c/munt"
cd "$PROJECT_ROOT/src/main/c/munt"
if [[ "$OSTYPE" == "darwin"* ]]; then
    cmake -G "Unix Makefiles" -Dmt32emu_SHARED=ON ../../../../ext/munt/mt32emu
    make -j$(sysctl -n hw.ncpu)
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    cmake -G "Unix Makefiles" -Dmt32emu_SHARED=ON ../../../../ext/munt/mt32emu
    make -j$(nproc)
else
    echo "Unsupported OS for automated munt build. Please build manually."
fi

# 3. Build libADLMIDI
echo "==> Building libADLMIDI..."
mkdir -p "$PROJECT_ROOT/src/main/c/adlmidi"
cd "$PROJECT_ROOT/src/main/c/adlmidi"
if [[ "$OSTYPE" == "darwin"* ]]; then
    cmake -G "Unix Makefiles" \
        -DCMAKE_BUILD_TYPE=Release \
        -DlibADLMIDI_SHARED=ON \
        -DlibADLMIDI_STATIC=ON \
        -DWITH_EMBEDDED_BANKS=ON \
        -DWITH_MUS_SUPPORT=OFF \
        -DWITH_XMI_SUPPORT=OFF \
        -DUSE_DOSBOX_EMULATOR=OFF \
        -DUSE_OPAL_EMULATOR=OFF \
        -DUSE_JAVA_EMULATOR=OFF \
        ../../../../ext/libADLMIDI
    make -j$(sysctl -n hw.ncpu)
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    cmake -G "Unix Makefiles" \
        -DCMAKE_BUILD_TYPE=Release \
        -DlibADLMIDI_SHARED=ON \
        -DlibADLMIDI_STATIC=ON \
        -DWITH_EMBEDDED_BANKS=ON \
        -DWITH_MUS_SUPPORT=OFF \
        -DWITH_XMI_SUPPORT=OFF \
        -DUSE_DOSBOX_EMULATOR=OFF \
        -DUSE_OPAL_EMULATOR=OFF \
        -DUSE_JAVA_EMULATOR=OFF \
        ../../../../ext/libADLMIDI
    make -j$(nproc)
else
    echo "Unsupported OS for automated libADLMIDI build. Please build manually."
fi

# 4. Build libOPNMIDI
echo "==> Building libOPNMIDI..."
mkdir -p "$PROJECT_ROOT/src/main/c/opnmidi"
cd "$PROJECT_ROOT/src/main/c/opnmidi"
if [[ "$OSTYPE" == "darwin"* ]]; then
    cmake -G "Unix Makefiles" \
        -DCMAKE_BUILD_TYPE=Release \
        -DlibOPNMIDI_SHARED=ON \
        -DlibOPNMIDI_STATIC=ON \
        -DWITH_MIDI_SEQUENCER=OFF \
        -DWITH_XMI_SUPPORT=OFF \
        -DUSE_GENS_EMULATOR=OFF \
        -DUSE_NUKED_OPN2_LLE_EMULATOR=OFF \
        -DUSE_NUKED_OPNA_LLE_EMULATOR=OFF \
        -DUSE_VGM_FILE_DUMPER=OFF \
        ../../../../ext/libOPNMIDI
    make -j$(sysctl -n hw.ncpu)
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    cmake -G "Unix Makefiles" \
        -DCMAKE_BUILD_TYPE=Release \
        -DlibOPNMIDI_SHARED=ON \
        -DlibOPNMIDI_STATIC=ON \
        -DWITH_MIDI_SEQUENCER=OFF \
        -DWITH_XMI_SUPPORT=OFF \
        -DUSE_GENS_EMULATOR=OFF \
        -DUSE_NUKED_OPN2_LLE_EMULATOR=OFF \
        -DUSE_NUKED_OPNA_LLE_EMULATOR=OFF \
        -DUSE_VGM_FILE_DUMPER=OFF \
        ../../../../ext/libOPNMIDI
    make -j$(nproc)
else
    echo "Unsupported OS for automated libOPNMIDI build. Please build manually."
fi

echo "Native libraries built successfully."