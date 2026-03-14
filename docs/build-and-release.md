# Build & Release Engineering

This document describes the complete build pipeline — from source to distributable native binary — including C library compilation, GraalVM native image configuration, code quality gates, and the release packaging process.

---

## 1. Prerequisites

| Tool | Required for | Notes |
|------|-------------|-------|
| GraalVM JDK 25+ | Java compilation + native image | Community edition works; set `JAVA_HOME` |
| `native-image` | `./gradlew nativeCompile` | Bundled with GraalVM |
| `cmake` + `make` | C++ library build (ADL, OPN, Munt) | `brew install cmake` / `apt install cmake` |
| `gcc` | All C library builds | System default gcc suffices |
| `git` submodules | C source trees | Run `git submodule update --init --recursive` once |
| `libasound2-dev` | Linux only — ALSA headers for miniaudio | `sudo apt install libasound2-dev` |

---

## 2. Key Gradle Tasks

```
./gradlew test              # compile + test (also builds native libs as a side effect)
./gradlew nativeCompile     # produce build/native/nativeCompile/midra
./gradlew run --args="..."  # run in JVM mode (no native image needed)
./gradlew installDist       # JVM-mode distribution under build/install/
./gradlew setupFreepats     # download and unpack the FreePats GUS patch set
./gradlew spotlessApply     # auto-format Java sources
./gradlew cpdCheck          # detect copy-paste duplication (report only)
```

### Code Generation

Two source-generation tasks run before every `compileJava`:

| Task | Output | Purpose |
|------|--------|---------|
| `generateVersionClass` | `Version.java` | Embeds `project.version` as a compile-time constant |
| `generateLibraryPathsClass` | `LibraryPaths.java` | Embeds OS-specific fallback library search paths (`/opt/homebrew/lib`, `/usr/local/lib` …) used by `AbstractFFMBridge.tryLoadLibrary()` at runtime |

---

## 3. Native Library Build

All C/C++ libraries are built into `build/native-libs/{os}-{arch}/` (e.g. `macos-aarch64`, `linux-x86_64`). Each library has a dedicated Gradle `Exec` task that is `UP-TO-DATE`-cached on source inputs.

### Task dependency graph

```
nativeCompile
 ├── buildMiniaudioLib    (gcc, single C file)
 ├── buildAdlMidiLib      (cmake + make)
 ├── buildOpnMidiLib      (cmake + make)
 └── buildTsfLib          (gcc, single C file)

test / installDist
 └── (same four tasks)
```

Munt (`libmt32emu`) is **not** built by Gradle — it is user-installed and loaded dynamically at runtime. The `scripts/build-native-libs.sh` script builds it for CI/release packaging.

### Per-library details

#### miniaudio (`libmidiraja_audio`)
- Source: `src/main/c/miniaudio/midiraja_audio.c` (single-file miniaudio wrapper)
- macOS: linked against `CoreAudio`, `AudioToolbox`, `AudioUnit`, `CoreFoundation` frameworks
- Linux: linked with `-ldl -lpthread -lm`
- Output: both `.dylib`/`.so` (runtime) and `.a` (not currently used)

#### libADLMIDI
- Source: `ext/libADLMIDI` (git submodule)
- cmake flags: `SHARED=ON`, `STATIC=ON`, `WITH_EMBEDDED_BANKS=ON`; disabled: MUS/XMI support, DosBox/Opal/Java emulators (unused, reduce binary size)

#### libOPNMIDI
- Source: `ext/libOPNMIDI` (git submodule)
- cmake flags: `SHARED=ON`, `STATIC=ON`; disabled: MIDI sequencer, XMI, Gens emulator, Nuked OPN2/OPNA LLE emulators, VGM dumper

#### TinySoundFont (`libtsf`)
- Source: `src/main/c/tsf/tsf_wrapper.c` — a three-line file that activates the single-header library
- Build: `gcc -shared -fPIC -O2 -I ext/TinySoundFont -o libtsf.{dylib,so}`
- No cmake, no dependencies

#### libmt32emu (Munt)
- Source: `ext/munt/mt32emu` (git submodule)
- Built by `scripts/build-native-libs.sh` via `cmake -Dmt32emu_SHARED=ON`
- Not built by Gradle `compileJava`/`test`/`nativeCompile` tasks — built separately by `build-native-libs.sh` and then bundled in the release `lib/` directory alongside the other shared libraries

---

## 4. Code Quality Gates

All checks run on `./gradlew test` (or `check`). Style violations and PMD/CPD findings do not fail the build (`ignoreFailures = true`) but are printed to the console.

| Tool | Role | Config |
|------|------|--------|
| **Checkstyle 10** | Style enforcement | `config/checkstyle/checkstyle.xml` |
| **PMD 7** | Static analysis | `config/pmd/ruleset.xml` |
| **CPD** (PMD) | Copy-paste detection, token threshold 50 | report only (`cpdCheck` task) |
| **Error Prone** | Compile-time bug patterns | severity `ERROR` for project sources |
| **NullAway** | Null-safety enforcement | `AnnotatedPackages=com.fupfin.midiraja`, JSpecify mode |
| **Spotless** | Auto-formatter | Eclipse Java style + remove unused imports |
| **JaCoCo** | Coverage reporting | HTML/XML/CSV under `build/reports/jacoco/` |

> **Note on JaCoCo + Java 25:** JaCoCo 0.8.11 cannot instrument Java 25 class files. Tests still run and pass; only the coverage report task may emit warnings or fail. Use `-x jacocoTestReport` to suppress it.

---

## 5. GraalVM Native Image Configuration

### Build arguments (`build.gradle` → `graalvmNative`)

| Argument | Purpose |
|----------|---------|
| `--no-fallback` | Fail instead of producing a JVM-fallback image |
| `--enable-native-access=ALL-UNNAMED` | Required for FFM API (foreign memory and function calls) |
| `--enable-preview` | Java preview features used in the codebase |
| `-H:+SharedArenaSupport` | Enables `Arena.ofShared()` needed by FFM bridges |
| `--initialize-at-build-time=com.sun.media.sound.*, javax.sound.midi.MidiSystem` | Prevents "Can't find java.home" at startup |
| `-Os` | Optimise for binary size |
| `--install-exit-handlers` | Ensures `Runtime.addShutdownHook` runs on `Ctrl+C` |

### rpath embedding

The binary embeds a relative rpath so the bundled shared libraries in `lib/` are found automatically — no `LD_LIBRARY_PATH` or `DYLD_LIBRARY_PATH` needed at runtime:

| OS | Linker flag | Resolves to |
|----|------------|-------------|
| macOS | `-Wl,-rpath,@executable_path/../lib` | `<install>/lib/` relative to `bin/midra` |
| Linux | `-Wl,-rpath,$ORIGIN/../lib` | same, using ELF `$ORIGIN` |

### Reflection & FFM registration

GraalVM requires all dynamically accessed types and FFM `FunctionDescriptor`s to be declared in advance:

- **CLI reflection** (`reachability-metadata.json` → `reflection` array): all `@Command` / `@Option` / `@Parameters` classes and their fields — kept current by picocli-codegen's annotation processor.
- **FFM downcalls** (`reachability-metadata.json` → `foreign.downcalls` array): every `FunctionDescriptor` used in the bridges. The `NativeMetadataConsistencyTest` verifies completeness at `./gradlew test` time, failing with the exact JSON snippet to add if any descriptor is missing.

---

## 6. Release Archive

### Structure

```
midra-{os}-{arch}.tar.gz
├── bin/
│   └── midra                        — native binary
├── lib/
│   ├── libmidiraja_audio.{dylib,so} — miniaudio wrapper
│   ├── libADLMIDI.{dylib,so}        — OPL FM synthesis
│   ├── libOPNMIDI.{dylib,so}        — OPN FM synthesis
│   ├── libmt32emu.{dylib,so}        — MT-32 emulation
│   └── libtsf.{dylib,so}            — TinySoundFont SF2/SF3
├── share/midra/freepats/            — FreePats GUS patch set
├── midra.1                          — man page
└── VERSION                          — version string (plain text)
```

### Local packaging

```bash
./scripts/package-release.sh
# → dist/midra-{os}-{arch}-v{version}.tar.gz
# → dist/midra-{os}-{arch}-v{version}.sha256
```

The script handles: prerequisite checks, submodule initialisation, C library build, FreePats download, native image compile, staging directory assembly, tar.gz creation, and SHA-256 checksum.

---

## 7. CI/CD Pipeline (GitHub Actions)

Defined in `.github/workflows/release.yml`.

### Triggers

| Event | Action |
|-------|--------|
| `push` to a `v*` tag | Full build + GitHub Release creation |
| `workflow_dispatch` | Full build + CI artifact upload (no release) |

### Build matrix

| Runner | Artifact |
|--------|----------|
| `macos-15` | `midra-darwin-arm64.tar.gz` |
| `ubuntu-22.04` | `midra-linux-amd64.tar.gz` |
| `ubuntu-24.04-arm` | `midra-linux-arm64.tar.gz` |

### Steps per matrix job

1. `actions/checkout` with `submodules: recursive`
2. `graalvm/setup-graalvm@v1` — GraalVM Community 25
3. `gradle/actions/setup-gradle@v3` — Gradle wrapper + cache
4. Install ALSA dev headers (Linux only)
5. `./scripts/build-native-libs.sh` — compile all C/C++ libraries
6. `./gradlew test` — full test suite including `NativeMetadataConsistencyTest`
7. `./gradlew nativeCompile` — produce `midra` binary
8. `./gradlew setupFreepats` — download GUS patches
9. Package step — assemble staging directory, create `tar.gz`
10. On tag push: extract relevant CHANGELOG section → `softprops/action-gh-release@v2`
11. On manual run: `actions/upload-artifact@v4`

---

## 8. Installed Directory Layout

`install.sh` extracts the release archive and assembles the following layout under a configurable prefix (default `~/.local`):

```
{prefix}/
├── bin/
│   └── midra  ──────────────────────────────────── symlink → ../share/midiraja/{version}/bin/midra.sh
│
├── share/
│   ├── midiraja/
│   │   └── {version}/               ← versioned root (multiple versions can coexist)
│   │       ├── bin/
│   │       │   ├── midra            ← native binary  (real executable)
│   │       │   └── midra.sh         ← wrapper script (sets env, exec's binary)
│   │       └── lib/
│   │           ├── libmidiraja_audio.{dylib,so}
│   │           ├── libADLMIDI.{dylib,so}
│   │           ├── libOPNMIDI.{dylib,so}
│   │           ├── libmt32emu.{dylib,so}
│   │           └── libtsf.{dylib,so}
│   │
│   ├── midra/
│   │   └── freepats/                ← GUS patch set  (shared across versions, ~27 MB)
│   │
│   └── man/
│       └── man1/
│           └── midra.1
```

### Why a wrapper script?

The native binary has rpath `@executable_path/../lib` (macOS) / `$ORIGIN/../lib` (Linux) embedded at link time. This resolves correctly when the binary is executed directly from its own directory. However, `~/.local/bin/midra` is a symlink to `midra.sh`, not to the binary — so `@executable_path` would point to `~/.local/bin`, where no `lib/` exists.

The wrapper resolves this by explicitly setting `DYLD_LIBRARY_PATH` / `LD_LIBRARY_PATH` to the versioned `lib/` directory before `exec`'ing the binary by its absolute path:

```bash
MIDRA_HOME="{prefix}/share/midiraja/{version}"
export DYLD_LIBRARY_PATH="${MIDRA_HOME}/lib:${DYLD_LIBRARY_PATH:-}"   # macOS
# or
export LD_LIBRARY_PATH="${MIDRA_HOME}/lib:${LD_LIBRARY_PATH:-}"       # Linux
exec "${MIDRA_HOME}/bin/midra" "$@"
```

The wrapper also exports `MIDRA_DATA={prefix}/share/midra` so the GUS engine can locate the FreePats patch set regardless of the working directory.

### FreePats location

FreePats are installed to `{prefix}/share/midra/freepats/` — outside the versioned subtree — so that upgrading to a new version does not re-download the 27 MB patch set.

### PATH configuration

If `{prefix}/bin` is not already on `PATH`, the installer appends the following line to `~/.zshrc` (macOS) or `~/.bashrc` (Linux):

```bash
export PATH="{prefix}/bin:$PATH"
```

### Version resolution (packaging step)

The version is resolved in order of priority:
1. `workflow_dispatch` input `version`
2. Git tag name (strips leading `v`)
3. `./gradlew properties -q | awk '/^version:/ {print $2}'`
