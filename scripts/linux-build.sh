#!/bin/bash
# scripts/linux-build.sh
IMAGE="ghcr.io/graalvm/native-image-community:21"
echo "🚀 Starting Linux Native Build via Docker ($IMAGE)..."
docker run --rm -i -v "$(pwd)":/app -w /app $IMAGE bash -c "microdnf install -y alsa-lib-devel zlib-devel gcc gcc-c++ make && ./gradlew nativeCompile --no-daemon"
