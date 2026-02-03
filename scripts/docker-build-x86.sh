#!/bin/bash
# scripts/docker-build-x86.sh
# Cross-compiles the Linux Native Image for x86_64 using Docker's multi-platform support

set -e

# Move to the project root directory
cd "$(dirname "$0")/.."

IMAGE_NAME="midra-linux-builder-x86:latest"

echo "🐳 Building Docker image for x86_64: ${IMAGE_NAME}..."
# Force Docker to use the x86_64 (amd64) architecture platform
docker build --platform linux/amd64 -t "${IMAGE_NAME}" -f Dockerfile.linux .

echo "🚀 Running Linux x86_64 Native Compilation inside Docker..."
git submodule update --init --recursive

# Clean up macOS/ARM64 CMakeCache
rm -f src/main/c/adlmidi/CMakeCache.txt
rm -f src/main/c/opnmidi/CMakeCache.txt

# Force the container to run in amd64 mode
docker run --rm --platform linux/amd64 -v "$(pwd)":/app -w /app "${IMAGE_NAME}" ./gradlew clean buildAdlMidiLib buildOpnMidiLib buildMiniaudioLib nativeCompile --no-daemon

echo "✅ Linux x86_64 Native Build Completed."
echo "Output binary: build/native/nativeCompile/midra"
