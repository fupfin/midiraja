#!/bin/bash
# install.sh
# One-liner installation script for midra

set -e

VERSION="1.1.0"
REPO="YOUR_GITHUB_USERNAME/playmidi"
URL_PREFIX="https://github.com/${REPO}/releases/download/v${VERSION}"

# Detect OS
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
if [ "$OS" != "darwin" ] && [ "$OS" != "linux" ]; then
    echo "Unsupported OS: $OS"
    exit 1
fi

# Detect Architecture
ARCH=$(uname -m)
if [ "$ARCH" = "x86_64" ]; then
    ARCH="amd64"
fi

FILENAME="midra-${OS}-${ARCH}-v${VERSION}.tar.gz"
DOWNLOAD_URL="${URL_PREFIX}/${FILENAME}"

INSTALL_DIR="/usr/local/bin"

echo "🎵 Installing midra v${VERSION} for ${OS}-${ARCH}..."
echo "Downloading from: ${DOWNLOAD_URL}"

# Create a temporary directory
TMP_DIR=$(mktemp -d)
cd "$TMP_DIR"

# Download the tarball
if command -v curl &> /dev/null; then
    curl -sL --fail -o "$FILENAME" "$DOWNLOAD_URL" || { echo "Download failed. Check the URL."; exit 1; }
elif command -v wget &> /dev/null; then
    wget -qO "$FILENAME" "$DOWNLOAD_URL" || { echo "Download failed. Check the URL."; exit 1; }
else
    echo "Error: curl or wget is required."
    exit 1
fi

# Extract and install
tar -xzf "$FILENAME"
chmod +x midra

echo "Moving 'midra' to ${INSTALL_DIR}..."
if [ -w "$INSTALL_DIR" ]; then
    mv midra "$INSTALL_DIR/"
else
    echo "Sudo privileges required to install to ${INSTALL_DIR}."
    sudo mv midra "$INSTALL_DIR/"
fi

# Cleanup
cd - > /dev/null
rm -rf "$TMP_DIR"

echo "✅ Successfully installed! Run 'midra --help' to get started."
