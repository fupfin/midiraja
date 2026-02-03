#!/bin/bash
# scripts/docker-build.sh
# Builds the Linux Native Image using Docker (Ubuntu 24.04 + GraalVM 25)

set -e

# Move to the project root directory
cd "$(dirname "$0")/.."

IMAGE_NAME="midra-linux-builder:latest"

echo "🐳 Building Docker image: ${IMAGE_NAME}..."
docker build -t "${IMAGE_NAME}" -f Dockerfile.linux .

echo "🚀 Running Linux Native Compilation inside Docker..."
# Mount current directory, run the container
# Use the Gradle cache from host if possible, but safely.
# Ensure git submodules are actually checked out (empty ext/libADLMIDI causes CMake to fail)
git submodule update --init --recursive

# The ext submodules might not be checked out if we just cloned.
# But since this is a local volume mount, they should be there.

docker run --rm -v "$(pwd)":/app -w /app "${IMAGE_NAME}" ./gradlew nativeCompile --no-daemon

echo "✅ Linux Native Build Completed."
echo "Output binary: build/native/nativeCompile/midra"