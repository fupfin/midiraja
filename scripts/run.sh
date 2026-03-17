#!/bin/bash
# scripts/run.sh - Build and run Midiraja in development mode

set -e
cd "$(dirname "$0")/.."

# Ensure resources are available in the build directory
# (These gradle tasks are fast if resources already exist)
./gradlew downloadFluidR3Sf3 setupFreepats -q

# Set MIDRA_DATA so the engines can find soundfonts/patches in the build/ folder
# and find demo MIDI files if needed.
export MIDRA_DATA="$(pwd)/build"

# Build and install the distribution locally (fast after first build)
./gradlew installDist -x test -q

# Set MIDRA_DATA so the engines can find soundfonts/patches in the build/ folder
export MIDRA_DATA="$(pwd)/build"

# Run the actual installed binary directly for full TTY/interactive support
./build/install/midrax/bin/midrax "$@"
