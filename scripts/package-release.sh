#!/bin/bash
# scripts/package-release.sh
# Packages the compiled native binary for distribution

set -e

# Move to the project root directory regardless of where the script is called from
cd "$(dirname "$0")/.."

# Check prerequisites
echo "🔍 Checking prerequisites..."
MISSING=()
for cmd in cmake gcc g++ make curl git; do
    command -v "$cmd" &>/dev/null || MISSING+=("$cmd")
done
if [ "$(uname -s)" = "Linux" ] && [ ! -f /usr/include/alsa/asoundlib.h ]; then
    MISSING+=("libasound2-dev")
fi
if [ ${#MISSING[@]} -gt 0 ]; then
    echo "❌ Missing required dependencies: ${MISSING[*]}"
    if [ "$(uname -s)" = "Linux" ]; then
        echo "   Install with: sudo apt-get install -y ${MISSING[*]}"
    else
        echo "   Install with: brew install ${MISSING[*]}"
    fi
    exit 1
fi
if ! command -v native-image &>/dev/null; then
    echo "❌ GraalVM native-image not found."
    echo "   Install GraalVM JDK 25 and set JAVA_HOME, or run:"
    echo "   https://www.graalvm.org/downloads/"
    exit 1
fi
echo "✅ All prerequisites satisfied."

# Extract version dynamically from Gradle
echo "📦 Extracting project version from Gradle..."
VERSION=$(./gradlew properties -q | awk '/^version:/ {print $2}')

if [ -z "$VERSION" ] || [ "$VERSION" = "unspecified" ]; then
    echo "❌ Error: Could not determine version from Gradle."
    exit 1
fi
echo "✅ Version detected: v${VERSION}"

OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

if [ "$ARCH" = "x86_64" ]; then
    ARCH="amd64"
fi

BIN_DIR="build/native/nativeCompile"
DIST_DIR="dist"
ARCHIVE_NAME="midra-${OS}-${ARCH}-v${VERSION}.tar.gz"
CHECKSUM_FILE="midra-${OS}-${ARCH}-v${VERSION}.sha256"

# Ensure submodules are initialized
echo "📦 Initializing git submodules..."
git submodule update --init --recursive

# Build C/C++ native libraries
echo "🛠️  Building C/C++ native libraries..."
./scripts/build-native-libs.sh

# Build the native binary (force relink so linker options are always applied)
echo "🛠️  Building native image via GraalVM Native Image..."
rm -f "${BIN_DIR}/midra" "${BIN_DIR}/midra.exe"
./gradlew nativeCompile

if [ ! -f "${BIN_DIR}/midra" ]; then
    echo "❌ Error: Binary not found at ${BIN_DIR}/midra even after build attempt."
    exit 1
fi

echo "📦 Packaging ${ARCHIVE_NAME}..."
mkdir -p "${DIST_DIR}"

# Resolve the native-libs directory (build system uses aarch64/x86_64, not arm64/amd64)
NATIVE_OS=$(uname -s | tr '[:upper:]' '[:lower:]' | sed 's/darwin/macos/')
NATIVE_ARCH=$(uname -m)
[ "$NATIVE_ARCH" = "arm64"  ] && NATIVE_ARCH="aarch64"
NATIVE_LIBS_DIR="build/native-libs/${NATIVE_OS}-${NATIVE_ARCH}"

STAGING_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGING_DIR"' EXIT
mkdir -p "${STAGING_DIR}/bin" "${STAGING_DIR}/lib" "${STAGING_DIR}/share/midra"
cp "${BIN_DIR}/midra" "${STAGING_DIR}/bin/midra"
cp "src/main/man/midra.1" "${STAGING_DIR}/midra.1"
echo "${VERSION}" > "${STAGING_DIR}/VERSION"

# Include freepats in share/midra/ so GusSynthProvider finds them after install
if [ -d "build/freepats" ]; then
    mkdir -p "${STAGING_DIR}/share/midra/freepats"
    cp -r "build/freepats/." "${STAGING_DIR}/share/midra/freepats/"
else
    echo "⚠️  Warning: build/freepats not found. Run './gradlew setupFreepats' first."
fi

# Bundle native libraries in lib/ so rpath (@executable_path/../lib / $ORIGIN/../lib) finds them
LIB_EXT="dylib" ; [ "$(uname -s)" = "Linux" ] && LIB_EXT="so"
for lib in \
    "${NATIVE_LIBS_DIR}/miniaudio/libmidiraja_audio.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/adlmidi/libADLMIDI.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/opnmidi/libOPNMIDI.${LIB_EXT}"; do
    [ -f "$lib" ] && cp "$lib" "${STAGING_DIR}/lib/"
done

tar -czf "${DIST_DIR}/${ARCHIVE_NAME}" -C "${STAGING_DIR}" .

echo "🔒 Calculating SHA256 Checksum..."
cd "${DIST_DIR}"
if command -v shasum &> /dev/null; then
    shasum -a 256 "${ARCHIVE_NAME}" > "${CHECKSUM_FILE}"
else
    sha256sum "${ARCHIVE_NAME}" > "${CHECKSUM_FILE}"
fi

echo "✅ Release package successfully created: ${DIST_DIR}/${ARCHIVE_NAME}"
cat "${CHECKSUM_FILE}"
