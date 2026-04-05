/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.Test;

class NativeAudioEngineTest
{
    @Test
    void testAudioEngineInitializationAndClose()
    {
        // Find the compiled dylib/so in the Gradle build output directory
        String osFamily = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("mac") ? "macos" : "linux";
        String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
        if (arch.equals("amd64"))
            arch = "x86_64";
        if (arch.equals("arm64"))
            arch = "aarch64";
        String ext = osFamily.equals("macos") ? "dylib" : "so";
        File libFile = new File("build/native-libs/" + osFamily + "-" + arch
                + "/miniaudio/libmidiraja_audio." + ext);

        // If the library isn't compiled yet in this environment, skip the test gracefully.
        if (!libFile.exists())
        {
            System.out.println(
                    "Skipping NativeAudioEngineTest because shared library is not built.");
            return;
        }

        final File finalLibFile = libFile;
        assertDoesNotThrow(() -> engineInitAndClose(finalLibFile.getAbsolutePath()));
    }

    private static void engineInitAndClose(String libPath) throws Exception
    {
        try (NativeAudioEngine engine = new NativeAudioEngine(libPath))
        {
            // Initialize with standard parameters (44.1kHz, Stereo, 4096 frames buffer)
            engine.init(44100, 2, 4096);

            // Push some silence to ensure the ring buffer doesn't crash
            short[] silence = new short[1024];
            engine.push(silence);

            // Close is called automatically by try-with-resources
        }
    }

    @Test
    void testEngineThrowsOnInvalidLibraryPath()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new NativeAudioEngine("/path/to/non/existent/lib.dylib"));
    }
}
