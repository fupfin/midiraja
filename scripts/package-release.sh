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
if [ "$(uname -s)" = "Linux" ]; then
    [ ! -f /usr/include/alsa/asoundlib.h ] && MISSING+=("libasound2-dev")
    [ ! -f /usr/include/zlib.h ] && MISSING+=("zlib1g-dev")
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

RAW_OS=$(uname -s)
case "$RAW_OS" in
    Darwin*)  OS="darwin"   ;;
    Linux*)   OS="linux"    ;;
    MINGW*|MSYS*|CYGWIN*) OS="windows" ;;
    *)        OS=$(echo "$RAW_OS" | tr '[:upper:]' '[:lower:]') ;;
esac
ARCH=$(uname -m)

if [ "$ARCH" = "x86_64" ]; then
    ARCH="amd64"
fi

BIN_DIR="build/native/nativeCompile"
DIST_DIR="dist"
if [ "$OS" = "windows" ]; then
    ARCHIVE_NAME="midra-${OS}-${ARCH}-v${VERSION}.zip"
else
    ARCHIVE_NAME="midra-${OS}-${ARCH}-v${VERSION}.tar.gz"
fi
CHECKSUM_FILE="midra-${OS}-${ARCH}-v${VERSION}.sha256"

# Ensure submodules are initialized
echo "📦 Initializing git submodules..."
git submodule update --init --recursive

# Build C/C++ native libraries
echo "🛠️  Building C/C++ native libraries..."
./scripts/build-native-libs.sh

# Download GUS patch set (freepats) for GusSynthProvider
echo "🎵 Downloading FreePats..."
./gradlew setupFreepats

# Download FluidR3 GM SF3 SoundFont for TsfSynthProvider
echo "🎵 Downloading FluidR3 GM SF3..."
./gradlew downloadFluidR3Sf3

# Build the native binary (force relink so linker options are always applied)
echo "🛠️  Building native image via GraalVM Native Image..."
rm -f "${BIN_DIR}/midra" "${BIN_DIR}/midra.exe"
./gradlew nativeCompile

BIN_NAME="midra"
[ "$OS" = "windows" ] && BIN_NAME="midra.exe"
if [ ! -f "${BIN_DIR}/${BIN_NAME}" ]; then
    echo "❌ Error: Binary not found at ${BIN_DIR}/${BIN_NAME} even after build attempt."
    exit 1
fi

echo "📦 Packaging ${ARCHIVE_NAME}..."
mkdir -p "${DIST_DIR}"

# Resolve the native-libs directory (build system uses aarch64/x86_64, not arm64/amd64)
NATIVE_OS="$OS"
[ "$NATIVE_OS" = "darwin" ] && NATIVE_OS="macos"
NATIVE_ARCH=$(uname -m)
[ "$NATIVE_ARCH" = "arm64"  ] && NATIVE_ARCH="aarch64"
NATIVE_LIBS_DIR="build/native-libs/${NATIVE_OS}-${NATIVE_ARCH}"

STAGING_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGING_DIR"' EXIT
mkdir -p "${STAGING_DIR}/bin" "${STAGING_DIR}/lib" "${STAGING_DIR}/share/midra/soundfonts"
cp "${BIN_DIR}/${BIN_NAME}" "${STAGING_DIR}/bin/${BIN_NAME}"
echo "${VERSION}" > "${STAGING_DIR}/VERSION"

# Man page: skip on Windows
if [ "$OS" != "windows" ]; then
    cp "src/main/man/midra.1" "${STAGING_DIR}/midra.1"
fi

# Include freepats in share/midra/ so GusSynthProvider finds them after install
if [ ! -d "build/freepats" ]; then
    echo "❌ Error: build/freepats not found. Run './gradlew setupFreepats' first."
    exit 1
fi
mkdir -p "${STAGING_DIR}/share/midra/freepats"
cp -r "build/freepats/." "${STAGING_DIR}/share/midra/freepats/"

# Include FluidR3 GM SF3 in share/midra/soundfonts/ so TsfSynthProvider finds it
if [ ! -f "build/soundfonts/FluidR3_GM.sf3" ]; then
    echo "❌ Error: build/soundfonts/FluidR3_GM.sf3 not found. Run './gradlew downloadFluidR3Sf3' first."
    exit 1
fi
cp "build/soundfonts/FluidR3_GM.sf3" "${STAGING_DIR}/share/midra/soundfonts/"

# Include demo MIDI files in share/midra/demomidi/
echo "🎵 Bundling demo MIDI files..."
mkdir -p "${STAGING_DIR}/share/midra/demomidi"
cp src/main/resources/demomidi/*.mid "${STAGING_DIR}/share/midra/demomidi/"
cp src/main/resources/demomidi/CREDITS.md "${STAGING_DIR}/share/midra/demomidi/"

# Bundle native libraries
# Windows: DLLs go in bin/ (same dir as exe) so the loader finds them
# Unix: shared libs go in lib/ so rpath (@executable_path/../lib / $ORIGIN/../lib) finds them
if [ "$OS" = "windows" ]; then
    LIB_EXT="dll"
    LIB_DEST="${STAGING_DIR}/bin"
elif [ "$OS" = "darwin" ]; then
    LIB_EXT="dylib"
    LIB_DEST="${STAGING_DIR}/lib"
else
    LIB_EXT="so"
    LIB_DEST="${STAGING_DIR}/lib"
fi
for lib in \
    "${NATIVE_LIBS_DIR}/miniaudio/libmidiraja_audio.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/adlmidi/libADLMIDI.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/opnmidi/libOPNMIDI.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/munt/libmt32emu.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/tsf/libtsf.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/mediakeys/libmidiraja_mediakeys.${LIB_EXT}" \
    "${NATIVE_LIBS_DIR}/vgm/libmidiraja_vgm.${LIB_EXT}"; do
    [ -f "$lib" ] && cp "$lib" "${LIB_DEST}/"
done

if [ "$OS" = "windows" ]; then
    (cd "${STAGING_DIR}" && zip -r - .) > "${DIST_DIR}/${ARCHIVE_NAME}"
else
    tar -czf "${DIST_DIR}/${ARCHIVE_NAME}" -C "${STAGING_DIR}" .
fi

echo "🔒 Calculating SHA256 Checksum..."
cd "${DIST_DIR}"
if command -v shasum &> /dev/null; then
    shasum -a 256 "${ARCHIVE_NAME}" > "${CHECKSUM_FILE}"
else
    sha256sum "${ARCHIVE_NAME}" > "${CHECKSUM_FILE}"
fi

echo "✅ Release package successfully created: ${DIST_DIR}/${ARCHIVE_NAME}"
cat "${CHECKSUM_FILE}"
