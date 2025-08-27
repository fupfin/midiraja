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
docker run --rm -v "$(pwd)":/app -w /app "${IMAGE_NAME}" ./gradlew nativeCompile --no-daemon

echo "✅ Linux Native Build Completed."
echo "Output binary: build/native/nativeCompile/midra"