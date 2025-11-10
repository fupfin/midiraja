#!/bin/bash
# scripts/build-leyden-dist.sh
# Creates a portable, high-performance JVM distribution using jlink and Leyden (AppCDS).

set -e

cd "$(dirname "$0")/.."

echo "🚀 Building Midiraja JAR..."
./gradlew installDist

VERSION=$(./gradlew properties -q | awk '/^version:/ {print $2}')
if [ -z "$VERSION" ] || [ "$VERSION" = "unspecified" ]; then
    VERSION="0.1.0"
fi

OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
if [ "$ARCH" = "x86_64" ]; then ARCH="amd64"; fi

DIST_DIR="build/leyden-dist"
JRE_DIR="${DIST_DIR}/jre"
APP_DIR="${DIST_DIR}/app"
BIN_DIR="${DIST_DIR}/bin"
ARCHIVE_NAME="midra-jvm-${OS}-${ARCH}-v${VERSION}.tar.gz"

echo "🧹 Cleaning previous distributions..."
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}" "${APP_DIR}" "${BIN_DIR}"

echo "✂️  Creating Custom JRE using jlink..."
# We need java.desktop for MIDI, java.base for core, jdk.unsupported for unsafe (JLine)
jlink --module-path "$JAVA_HOME/jmods" \
      --add-modules java.base,java.desktop,jdk.unsupported \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=2 \
      --output "${JRE_DIR}"

echo "📦 Copying application files..."
# Copy the libs from installDist
cp -r build/install/midiraja/lib/* "${APP_DIR}/"

# Copy dynamic C libraries if they exist (macOS: .dylib, Linux: .so)
mkdir -p "${APP_DIR}/native"
find src/main/c -name "*.dylib" -o -name "*.so" -o -name "*.dll" 2>/dev/null | while read -r file; do
    cp "$file" "${APP_DIR}/native/"
done

echo "⚡ Generating Base CDS Archive for Custom JRE..."
"${JRE_DIR}/bin/java" -Xshare:dump --enable-native-access=ALL-UNNAMED --enable-preview > /dev/null 2>&1 || true

echo "⚡ Training Leyden (AppCDS) Archive..."
# We run the application with a training command to record classes and FFM resolutions.
# -Xshare:dump and -XX:SharedArchiveFile are used for standard AppCDS.
# In Java 24+, -XX:CacheDataStore (Leyden) might be available, but -XX:ArchiveClassesAtExit is standard and robust.
TRAINING_CMD="ports"
JSA_FILE="${APP_DIR}/midra.jsa"

# To maximize training, we run --help and ports.
CLASS_PATH="${APP_DIR}/*"

# 1. First run to generate the shared archive
"${JRE_DIR}/bin/java" -XX:ArchiveClassesAtExit="${JSA_FILE}" \
    --enable-native-access=ALL-UNNAMED --enable-preview \
    -cp "${CLASS_PATH}" com.midiraja.MidirajaCommand --help > /dev/null

echo "✅ AppCDS Archive generated at ${JSA_FILE} ($(du -h "${JSA_FILE}" | cut -f1))"

echo "📝 Creating Launcher Script..."
cat << 'EOF' > "${BIN_DIR}/midrax"
#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT_DIR="$(dirname "$DIR")"
JRE="$ROOT_DIR/jre"
APP="$ROOT_DIR/app"

# Determine library path for FFM / JNA depending on OS
OS="$(uname -s)"
if [ "$OS" = "Darwin" ]; then
    export DYLD_LIBRARY_PATH="$APP/native:$DYLD_LIBRARY_PATH"
elif [ "$OS" = "Linux" ]; then
    export LD_LIBRARY_PATH="$APP/native:$LD_LIBRARY_PATH"
fi

exec "$JRE/bin/java" -Xlog:cds=off -XX:SharedArchiveFile="$APP/midra.jsa" -Xshare:auto \
     --enable-native-access=ALL-UNNAMED --enable-preview \
     -cp "$APP/*" com.midiraja.MidirajaCommand "$@"
EOF

chmod +x "${BIN_DIR}/midrax"

echo "📦 Packaging final distribution..."
cd build
tar -czf "leyden-dist.tar.gz" -C leyden-dist .
mv "leyden-dist.tar.gz" "../dist/${ARCHIVE_NAME}"
cd ..

echo "🎉 Done! Leyden Distribution created at: dist/${ARCHIVE_NAME}"
echo "Test it by running: ./${BIN_DIR}/midrax --help"
