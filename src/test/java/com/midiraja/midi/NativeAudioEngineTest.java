/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.File;

class NativeAudioEngineTest {

    @Test
    void testAudioEngineInitializationAndClose() {
        // Find the compiled dylib/so/dll
        File libFile = new File("src/main/c/miniaudio/libmidiraja_audio.dylib");
        if (!libFile.exists()) {
            libFile = new File("src/main/c/miniaudio/libmidiraja_audio.so");
        }
        
        // If the library isn't compiled yet in this environment, skip the test gracefully.
        if (!libFile.exists()) {
            System.out.println("Skipping NativeAudioEngineTest because shared library is not built.");
            return;
        }

        final File finalLibFile = libFile;
        assertDoesNotThrow(() -> {
            try (NativeAudioEngine engine = new NativeAudioEngine(finalLibFile.getAbsolutePath())) {
                // Initialize with standard parameters (44.1kHz, Stereo, 4096 frames buffer)
                engine.init(44100, 2, 4096);
                
                // Push some silence to ensure the ring buffer doesn't crash
                short[] silence = new short[1024];
                engine.push(silence);
                
                // Close is called automatically by try-with-resources
            }
        });
    }

    @Test
    void testEngineThrowsOnInvalidLibraryPath() {
        assertThrows(UnsatisfiedLinkError.class, () -> {
            new NativeAudioEngine("/path/to/non/existent/lib.dylib");
        });
    }
}