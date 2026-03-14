# Native Library Distribution Strategy

## Decision: Bundle vs. User-installed

A native library is bundled in `lib/` if and only if it has no transitive
dependencies beyond libc / libc++. Such a library can be copied into the
release tarball and loaded via rpath without the user installing anything extra.

| Library | Bundled | User-installed | Reason |
|---|---|---|---|
| miniaudio (`libmidiraja_audio`) | ✓ | — | No external deps; our own C wrapper |
| libADLMIDI | ✓ | — | No external deps; built from submodule |
| libOPNMIDI | ✓ | — | No external deps; built from submodule |
| libmt32emu | ✓ | — | No external deps; built from submodule |
| FluidSynth | — | ✓ | Requires glib, pcre2, and other transitive deps that cannot be portably bundled |

## Release Layout

```
midra-<os>-<arch>-v<version>.tar.gz
├── bin/
│   └── midra          ← native binary
├── lib/
│   ├── libmidiraja_audio.dylib  (or .so on Linux)
│   ├── libADLMIDI.dylib
│   ├── libOPNMIDI.dylib
│   └── libmt32emu.dylib
├── share/midra/
│   └── freepats/      ← GUS patch set for GusSynthProvider
├── midra.1            ← man page
└── VERSION
```

## rpath

The binary embeds an rpath so the dynamic linker finds `lib/` at runtime without
any `LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH` override:

- **macOS**: `-Wl,-rpath,@executable_path/../lib`
- **Linux**: `-Wl,-rpath,$ORIGIN/../lib`

Both resolve to the `lib/` directory next to `bin/` in the installed layout.

In a development tree (unpackaged), `AbstractFFMBridge.tryLoadLibrary()` falls
back to `build/native-libs/<os>-<arch>/<libdir>/` so the JVM run-mode also works
after `./scripts/build-native-libs.sh`.

## Static Linking Removed

libADLMIDI and libOPNMIDI were previously statically linked into the native
binary as an alternative distribution strategy. This was removed because:

1. **`loaderLookup()` was never wired up** — the static-link path depended on
   `SymbolLookup.loaderLookup()` to find symbols in the process image, but that
   call was never added to the FFM bridge constructors, making the static archives
   dead weight in the linker command.

2. **Duplicate `ymfm` symbols** — both libraries embed the `ymfm` FM emulation
   core. Forcing both archives into the binary with `--whole-archive` / `-force_load`
   causes multiple-definition linker errors on Linux. Avoiding this required
   `--no-whole-archive` for libOPNMIDI, which made symbol lookup unreliable anyway.

3. **`.dylib` bundle is simpler** — since the libraries are already built as shared
   libraries and the rpath is in place, bundling the `.dylib` / `.so` is the
   canonical solution with no linker complexity.
