#!/bin/bash
# scripts/package-release.sh
# Packages the compiled native binary for distribution

set -e

VERSION="1.1.0"
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

if [ "$ARCH" = "x86_64" ]; then
    ARCH="amd64"
fi

BIN_DIR="build/native/nativeCompile"
DIST_DIR="dist"
ARCHIVE_NAME="midra-${OS}-${ARCH}-v${VERSION}.tar.gz"
CHECKSUM_FILE="midra-${OS}-${ARCH}-v${VERSION}.sha256"

if [ ! -f "${BIN_DIR}/midra" ]; then
    echo "Error: Binary not found at ${BIN_DIR}/midra"
    echo "Run './gradlew nativeCompile' first."
    exit 1
fi

echo "Packaging ${ARCHIVE_NAME}..."

mkdir -p "${DIST_DIR}"
# Using -C to change directory so the tarball contains just 'midra' without the path structure
tar -czf "${DIST_DIR}/${ARCHIVE_NAME}" -C "${BIN_DIR}" midra

echo "Calculating SHA256 Checksum..."
cd "${DIST_DIR}"
if command -v shasum &> /dev/null; then
    shasum -a 256 "${ARCHIVE_NAME}" > "${CHECKSUM_FILE}"
else
    sha256sum "${ARCHIVE_NAME}" > "${CHECKSUM_FILE}"
fi

echo "✅ Package created: ${DIST_DIR}/${ARCHIVE_NAME}"
cat "${CHECKSUM_FILE}"
