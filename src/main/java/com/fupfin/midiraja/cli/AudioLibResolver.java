/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.util.Locale;

/**
 * Resolves the path to the native audio helper library (libmidiraja_audio). Tries the system
 * library path first, then the in-tree dev build path.
 */
public final class AudioLibResolver
{
    private AudioLibResolver()
    {}

    /** Returns the resolved path suitable for passing to {@code NativeAudioEngine}. */
    public static String resolve() throws RuntimeException
    {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osFamily =
                osName.contains("mac") ? "macos" : (osName.contains("linux") ? "linux" : "windows");
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64")) arch = "x86_64";
        if (arch.equals("arm64")) arch = "aarch64";

        String nativeTarget = osFamily + "-" + arch;
        String libName = osName.contains("mac") ? "libmidiraja_audio.dylib"
                : osName.contains("win") ? "libmidiraja_audio.dll"
                : "libmidiraja_audio.so";
        String devPath = new File("").getAbsolutePath() + "/build/native-libs/" + nativeTarget
                + "/miniaudio/" + libName;
        String[] paths = {libName, devPath};

        try (Arena arena = Arena.ofShared())
        {
            for (String p : paths)
            {
                try
                {
                    if (new File(p).isAbsolute())
                    {
                        if (new File(p).exists()) return p;
                    }
                    else
                    {
                        SymbolLookup.libraryLookup(p, arena);
                        return p;
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }
        throw new RuntimeException(
                "Could not find " + libName + ". Run scripts/build-native-libs.sh first.");
    }
}
