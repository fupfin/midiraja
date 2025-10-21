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

echo "Native libraries built successfully."