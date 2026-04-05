/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Smoke test for FFMAdlMidiNativeBridge with the real libADLMIDI library.
 * Verifies that a NoteOn dispatched via the render-thread path produces
 * non-silent PCM output from adl_generate().
 *
 * <p>
 * Automatically skipped if libADLMIDI is absent from the search paths
 * checked by {@link FFMAdlMidiNativeBridge}'s library loader.
 */
@EnabledIf("adlMidiLibPresent")
class FFMAdlMidiNativeBridgeIntegrationTest
{
    static boolean adlMidiLibPresent()
    {
        String projectRoot = new java.io.File("").getAbsolutePath();
        String[] candidates = {
                projectRoot + "/src/main/c/adlmidi/libADLMIDI.dylib",
                projectRoot + "/src/main/c/adlmidi/libADLMIDI.so",
        };
        for (String path : candidates)
        {
            if (new java.io.File(path).exists())
                return true;
        }
        // Also try system library paths
        try
        {
            java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
            java.lang.foreign.SymbolLookup.libraryLookup("libADLMIDI.dylib", arena);
            arena.close();
            return true;
        }
        catch (Exception ignoredDylib)
        { // not on system path
        }
        try
        {
            java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
            java.lang.foreign.SymbolLookup.libraryLookup("libADLMIDI.so", arena);
            arena.close();
            return true;
        }
        catch (Exception ignoredSo)
        { // not on system path
        }
        return false;
    }

    @Test
    void testNoteOnProducesAudio() throws Exception
    {
        FFMAdlMidiNativeBridge bridge = new FFMAdlMidiNativeBridge();
        bridge.init(44100);
        bridge.setNumChips(1);
        bridge.setBank(0); // Default embedded bank

        // Warm up: render one silent chunk before any notes
        short[] silentBuf = new short[1024];
        bridge.generate(silentBuf, 512);

        // Dispatch a note directly (caller is render thread in this test)
        bridge.noteOn(0, 60, 100);

        // Render up to 20 chunks (~232 ms at 44100 Hz) to allow OPL3 FM attack
        short[] audioBuf = new short[1024];
        boolean hasAudio = false;
        for (int chunk = 0; chunk < 20 && !hasAudio; chunk++)
        {
            bridge.generate(audioBuf, 512);
            for (short s : audioBuf)
            {
                if (s != 0)
                {
                    hasAudio = true;
                    break;
                }
            }
        }
        assertTrue(hasAudio, "noteOn should produce non-silent PCM output via generate()");

        bridge.close();
    }
}
