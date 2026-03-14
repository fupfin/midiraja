#!/usr/bin/env bash
set -euo pipefail

# Midiraja install script
# Usage: curl -sL https://raw.githubusercontent.com/fupfin/midiraja/master/install.sh | bash
# Or:    bash install.sh [--prefix /usr/local] [--local /path/to/midra-*.tar.gz]

REPO="fupfin/midiraja"
BINARY_NAME="midra"
PREFIX="${HOME}/.local"
LOCAL_FILE=""

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
        --local)
            LOCAL_FILE="$2"
            shift 2
            ;;
        --local=*)
            LOCAL_FILE="${1#*=}"
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Usage: $0 [--prefix /path] [--local /path/to/midra-*.tar.gz]" >&2
            exit 1
            ;;
    esac
done

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [ -n "$LOCAL_FILE" ]; then
    if [ ! -f "$LOCAL_FILE" ]; then
        echo "Error: File not found: $LOCAL_FILE" >&2
        exit 1
    fi
    echo "Installing from local file: ${LOCAL_FILE}"
    cp "$LOCAL_FILE" "${TMP_DIR}/midra.tar.gz"
    LATEST_TAG="(local)"
else
    # Detect OS and Architecture
    OS="$(uname -s)"
    ARCH="$(uname -m)"

    case "$OS" in
        Darwin)
            OS_NAME="darwin"
            ;;
        Linux)
            OS_NAME="linux"
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

    echo "Downloading ${ASSET_NAME}..."
    if ! curl -fL --progress-bar -o "${TMP_DIR}/midra.tar.gz" "${DOWNLOAD_URL}"; then
        echo "Error: Download failed from ${DOWNLOAD_URL}" >&2
        echo "Please check https://github.com/${REPO}/releases for available assets." >&2
        exit 1
    fi
fi

# Extract tarball
tar -xzf "${TMP_DIR}/midra.tar.gz" -C "${TMP_DIR}"

# Determine version: prefer VERSION file in tarball, fall back to LATEST_TAG
if [ -f "${TMP_DIR}/VERSION" ]; then
    VERSION="$(cat "${TMP_DIR}/VERSION")"
else
    VERSION="${LATEST_TAG#v}"   # strip leading 'v' if present
fi

# Versioned install root: ~/.local/share/midiraja/{version}/
INSTALL_BASE="${PREFIX}/share/midiraja/${VERSION}"
mkdir -p "${INSTALL_BASE}/bin" "${INSTALL_BASE}/lib"

echo "Installing midra ${VERSION} to ${INSTALL_BASE}..."

install -m 755 "${TMP_DIR}/bin/${BINARY_NAME}" "${INSTALL_BASE}/bin/${BINARY_NAME}"

for lib in "${TMP_DIR}/lib"/*.dylib "${TMP_DIR}/lib"/*.so; do
    [ -f "$lib" ] && install -m 755 "$lib" "${INSTALL_BASE}/lib/$(basename "$lib")"
done

if [ -d "${TMP_DIR}/share/midra" ]; then
    mkdir -p "${PREFIX}/share/midra"
    cp -r "${TMP_DIR}/share/midra/." "${PREFIX}/share/midra/"
fi

# Write a wrapper script into the versioned bin/ dir, then symlink it into ~/.local/bin/.
# The wrapper sets up library/data paths and exec's the real binary by absolute path.
# Using a distinct name (midra.sh) avoids the wrapper accidentally calling itself via PATH.
LINK_DIR="${PREFIX}/bin"
WRAPPER="${INSTALL_BASE}/bin/${BINARY_NAME}.sh"
mkdir -p "${LINK_DIR}"
cat > "${WRAPPER}" << EOF
#!/usr/bin/env bash
MIDRA_HOME="${INSTALL_BASE}"
export MIDRA_DATA="${PREFIX}/share/midra"
if [ "\$(uname -s)" = "Darwin" ]; then
    export DYLD_LIBRARY_PATH="\${MIDRA_HOME}/lib:\${DYLD_LIBRARY_PATH:-}"
else
    export LD_LIBRARY_PATH="\${MIDRA_HOME}/lib:\${LD_LIBRARY_PATH:-}"
fi
exec "\${MIDRA_HOME}/bin/${BINARY_NAME}" "\$@"
EOF
chmod 755 "${WRAPPER}"
ln -sf "${WRAPPER}" "${LINK_DIR}/${BINARY_NAME}"

# Install man page
MAN_DIR="${PREFIX}/share/man/man1"
if [ -f "${TMP_DIR}/midra.1" ]; then
    if mkdir -p "${MAN_DIR}" 2>/dev/null; then
        install -m 644 "${TMP_DIR}/midra.1" "${MAN_DIR}/midra.1"
        echo "Man page installed to ${MAN_DIR}/midra.1"
    fi
fi

echo ""
echo "midra ${VERSION} installed."
echo "  binary : ${INSTALL_BASE}/bin/${BINARY_NAME}"
echo "  wrapper: ${INSTALL_BASE}/bin/${BINARY_NAME}.sh"
echo "  symlink: ${LINK_DIR}/${BINARY_NAME} -> ${INSTALL_BASE}/bin/${BINARY_NAME}.sh"

# Add LINK_DIR to PATH in shell profile if not already present
if ! echo ":${PATH}:" | grep -q ":${LINK_DIR}:"; then
    if [ "$(uname -s)" = "Darwin" ]; then
        SHELL_RC="${HOME}/.zshrc"
    else
        SHELL_RC="${HOME}/.bashrc"
    fi
    echo "" >> "${SHELL_RC}"
    echo "export PATH=\"${LINK_DIR}:\$PATH\"" >> "${SHELL_RC}"
    echo "Added ${LINK_DIR} to PATH in ${SHELL_RC}."
    echo "Run 'source ${SHELL_RC}' or open a new terminal to apply."
fi

echo "Run 'midra --help' to get started."
