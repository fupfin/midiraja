#!/usr/bin/env bash
set -euo pipefail

# Midiraja install script
# Usage: curl -sL https://raw.githubusercontent.com/fupfin/midiraja/master/install.sh | bash
# Or:    bash install.sh [--prefix /usr/local]

REPO="fupfin/midiraja"
BINARY_NAME="midra"
PREFIX="${HOME}/.local"

# Parse --prefix argument
while [[ $# -gt 0 ]]; do
    case "$1" in
        --prefix)
            PREFIX="$2"
            shift 2
            ;;
        --prefix=*)
            PREFIX="${1#*=}"
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Usage: $0 [--prefix /path]" >&2
            exit 1
            ;;
    esac
done

INSTALL_DIR="${PREFIX}/bin"

# Detect OS and Architecture
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
    Darwin)
        OS_NAME="darwin"
        ;;
    Linux)
        echo "Linux support is coming soon."
        echo "Please download manually from: https://github.com/${REPO}/releases"
        exit 1
        ;;
    *)
        echo "Unsupported OS: $OS"
        echo "Please download manually from: https://github.com/${REPO}/releases"
        exit 1
        ;;
esac

case "$ARCH" in
    x86_64|amd64)
        ARCH_NAME="amd64"
        ;;
    arm64|aarch64)
        ARCH_NAME="arm64"
        ;;
    *)
        echo "Unsupported architecture: $ARCH"
        echo "Please download manually from: https://github.com/${REPO}/releases"
        exit 1
        ;;
esac

ASSET_NAME="midra-${OS_NAME}-${ARCH_NAME}.tar.gz"

# Fetch latest release tag via GitHub API redirect
echo "Fetching latest release..."
LATEST_TAG="$(curl -sI "https://github.com/${REPO}/releases/latest" \
    | grep -i '^location:' \
    | sed 's/.*\/tag\///' \
    | tr -d '[:space:]')"

if [ -z "$LATEST_TAG" ]; then
    echo "Error: Could not determine the latest release tag." >&2
    echo "Please check https://github.com/${REPO}/releases and download manually." >&2
    exit 1
fi

echo "Latest release: ${LATEST_TAG}"

DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${LATEST_TAG}/${ASSET_NAME}"

# Download the tarball
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Downloading ${ASSET_NAME}..."
if ! curl -fL --progress-bar -o "${TMP_DIR}/${ASSET_NAME}" "${DOWNLOAD_URL}"; then
    echo "Error: Download failed from ${DOWNLOAD_URL}" >&2
    echo "Please check https://github.com/${REPO}/releases for available assets." >&2
    exit 1
fi

# Extract and install
echo "Installing to ${INSTALL_DIR}..."
tar -xzf "${TMP_DIR}/${ASSET_NAME}" -C "${TMP_DIR}"

mkdir -p "${INSTALL_DIR}"
install -m 755 "${TMP_DIR}/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"

# Install man page if man directory is writable
MAN_DIR="${PREFIX}/share/man/man1"
if [ -f "${TMP_DIR}/midra.1" ]; then
    if mkdir -p "${MAN_DIR}" 2>/dev/null; then
        install -m 644 "${TMP_DIR}/midra.1" "${MAN_DIR}/midra.1"
        echo "Man page installed to ${MAN_DIR}/midra.1"
    fi
fi

echo ""
echo "midra ${LATEST_TAG} installed to ${INSTALL_DIR}/midra"

# Check if INSTALL_DIR is on PATH
if ! echo ":${PATH}:" | grep -q ":${INSTALL_DIR}:"; then
    echo ""
    echo "Note: ${INSTALL_DIR} is not in your PATH."
    echo "Add the following line to your shell profile (~/.zshrc, ~/.bashrc, etc.):"
    echo ""
    echo "  export PATH=\"${INSTALL_DIR}:\$PATH\""
    echo ""
fi

echo "Run 'midra --help' to get started."
