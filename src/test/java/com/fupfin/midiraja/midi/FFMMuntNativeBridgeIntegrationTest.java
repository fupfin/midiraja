/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Smoke test for FFMMuntNativeBridge with the real Munt library.
 * Verifies that a NoteOn enqueued via playNoteOn() is processed by Munt and
 * produces non-silent PCM audio when renderAudio() is called.
 *
 * Requires ROM files in munt_roms/ (MT32_CONTROL.ROM + MT32_PCM.ROM).
 * Automatically skipped if ROM files are absent.
 */
@EnabledIf("muntRomsPresent")
class FFMMuntNativeBridgeIntegrationTest
{
    static boolean muntRomsPresent()
    {
        boolean hasControl = new File("munt_roms/MT32_CONTROL.ROM").exists()
                || new File("munt_roms/mt32_control.rom").exists();
        boolean hasPcm = new File("munt_roms/MT32_PCM.ROM").exists()
                || new File("munt_roms/mt32_pcm.rom").exists();
        return hasControl && hasPcm;
    }

    @Test
    void testNoteOnProducesAudio() throws Exception
    {
        FFMMuntNativeBridge bridge = new FFMMuntNativeBridge();
        bridge.createSynth();
        bridge.loadRoms("munt_roms");
        bridge.openSynth();

        // Render a baseline frame before any notes to warm up the pipeline
        short[] silentBuf = new short[1024];
        bridge.renderAudio(silentBuf, 512);

        // Enqueue a note via the playback thread path, then render to capture it.
        // Render up to 20 chunks (20 × 512 frames ≈ 320 ms at 32 kHz) to accommodate
        // the MT-32's LA-synthesis attack envelope, which may not rise within a single
        // 16 ms window.
        //
        // MT-32 default chanAssign: Part i → MIDI channel (i+1). Channel 0 has no part
        // assigned (chantable[0] = 0xFF), so notes on channel 0 are silently ignored.
        // Use channel 1 (Part 0) to produce audible output.
        bridge.playNoteOn(1, 60, 100);
        short[] audioBuf = new short[1024];
        boolean hasAudio = false;
        for (int chunk = 0; chunk < 20 && !hasAudio; chunk++)
        {
            bridge.renderAudio(audioBuf, 512);
            for (short s : audioBuf)
            {
                if (s != 0)
                {
                    hasAudio = true;
                    break;
                }
            }
        }
        assertTrue(hasAudio, "NoteOn should produce non-silent PCM output via renderAudio()");

        bridge.close();
    }
}
