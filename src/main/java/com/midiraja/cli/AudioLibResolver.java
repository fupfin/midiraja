/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.cli;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.util.Locale;

/**
 * Resolves the path to the native audio helper library (libmidiraja_audio).
 * Tries the system library path first, then the in-tree dev build path.
 */
public final class AudioLibResolver {

    private AudioLibResolver() {}

    /** Returns the resolved path suitable for passing to {@code NativeAudioEngine}. */
    public static String resolve() throws Exception {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String libName = os.contains("mac") ? "libmidiraja_audio.dylib" : "libmidiraja_audio.so";
        String devPath = new File("").getAbsolutePath() + "/src/main/c/miniaudio/" + libName;
        String[] paths = {libName, devPath};

        try (Arena arena = Arena.ofShared()) {
            for (String p : paths) {
                try {
                    if (p.startsWith("/")) {
                        if (new File(p).exists()) return p;
                    } else {
                        SymbolLookup.libraryLookup(p, arena);
                        return p;
                    }
                } catch (Exception ignored) {
                    // try next candidate
                }
            }
        }
        throw new Exception("Could not find " + libName + ". Run scripts/build-native-libs.sh first.");
    }
}
