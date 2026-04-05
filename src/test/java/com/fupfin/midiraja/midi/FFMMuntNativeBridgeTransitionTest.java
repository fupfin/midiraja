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
 * Diagnostic integration test for the song-transition pipeline.
 *
 * <p>
 * Simulates the full sequence that occurs between two songs in a playlist:
 * <ol>
 * <li>Play a note, render audio — verify Munt receives and voices it.
 * <li>Run {@code panic()} — all 2112 note-offs across 16 channels.
 * <li>Reset render timing ({@code prepareForNewTrack + onPlaybackStarted} simulation).
 * <li>Play a new note, render audio — verify Munt still voices it after the transition.
 * </ol>
 *
 * <p>
 * Diagnostic interpretation:
 * <table>
 * <tr>
 * <th>Step</th>
 * <th>hasActivePartials</th>
 * <th>getPlayingNotes</th>
 * <th>hasAudio</th>
 * <th>Diagnosis</th>
 * </tr>
 * <tr>
 * <td>1 (initial)</td>
 * <td>false</td>
 * <td>0</td>
 * <td>any</td>
 * <td>NoteOn not delivered —
 * channel/queue bug</td>
 * </tr>
 * <tr>
 * <td>4 (after
 * transition)</td>
 * <td>false</td>
 * <td>0</td>
 * <td>false</td>
 * <td>Note-offs left stuck voices; transition
 * broke delivery</td>
 * </tr>
 * <tr>
 * <td>4 (after
 * transition)</td>
 * <td>true</td>
 * <td>&gt;0</td>
 * <td>false</td>
 * <td>Munt voiced the note but PCM render
 * is broken</td>
 * </tr>
 * <tr>
 * <td>4 (after
 * transition)</td>
 * <td>true</td>
 * <td>&gt;0</td>
 * <td>true</td>
 * <td>Pipeline OK — silence is in the audio
 * path above Munt</td>
 * </tr>
 * </table>
 *
 * <p>
 * Requires ROM files in {@code munt_roms/}. Automatically skipped if absent.
 */
@EnabledIf("muntRomsPresent")
class FFMMuntNativeBridgeTransitionTest
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
    @SuppressWarnings("SystemOut")
    void testSongTransition() throws Exception
    {
        FFMMuntNativeBridge bridge = new FFMMuntNativeBridge();
        bridge.createSynth();
        bridge.loadRoms("munt_roms");
        bridge.openSynth();

        short[] buf = new short[1024];

        // ── STEP 1: Play a note, verify Munt receives and voices it ────────────────
        // MT-32 default chanAssign: Part 0 → MIDI channel 1 (not channel 0).
        bridge.playNoteOn(1, 60, 100);
        bridge.renderAudio(buf, 512); // first render: drains queue, updates timing ref
        bridge.renderAudio(buf, 512); // second render: note in attack phase

        boolean activeAfterNote = bridge.hasActivePartials();
        int partStatesAfterNote = bridge.getPartStates();
        byte[] keys = new byte[4], vels = new byte[4];
        int noteCount = bridge.getPlayingNotes(0, keys, vels); // Part 0

        System.out.printf(
                "[Step 1] hasActivePartials=%b  partStates=0x%02X  playingNotes(part0)=%d",
                activeAfterNote, partStatesAfterNote, noteCount);
        if (noteCount > 0)
            System.out.printf("  key=%d vel=%d", keys[0] & 0xFF, vels[0] & 0xFF);
        System.out.println();

        assertTrue(activeAfterNote, "Step 1: NoteOn should activate partials");
        assertTrue(noteCount > 0, "Step 1: NoteOn should register as a playing note on Part 0");

        // ── STEP 2: Panic — all note-offs for all 16 channels ──────────────────────
        // This sends (4 CC + 128 note-offs) × 16 = 2112 messages.
        // With queue size 4096 (set in createSynth) all messages should go through.
        for (int ch = 0; ch < 16; ch++)
        {
            bridge.playControlChange(ch, 64, 0); // Sustain Off
            bridge.playControlChange(ch, 123, 0); // All Notes Off
            bridge.playControlChange(ch, 120, 0); // All Sound Off
            bridge.playControlChange(ch, 121, 0); // Reset All Controllers
            for (int n = 0; n < 128; n++)
                bridge.playNoteOff(ch, n);
        }

        // Render 5 cycles (80 ms) to let Munt process all note-offs and reverb tail
        for (int i = 0; i < 5; i++)
            bridge.renderAudio(buf, 512);

        boolean activeAfterPanic = bridge.hasActivePartials();
        int partStatesAfterPanic = bridge.getPartStates();
        System.out.printf(
                "[Step 2] hasActivePartials=%b  partStates=0x%02X  (after panic + 5 renders)%n",
                activeAfterPanic, partStatesAfterPanic);

        // Note: partials may still be in RELEASE state (reverb tail) — that is expected.
        // The key question is whether the transition restores correct note delivery.

        // ── STEP 3: Simulate song transition (resetRenderTiming) ───────────────────
        bridge.resetRenderTiming();

        // ── STEP 4: Play a new note, verify it works after the transition ──────────
        bridge.playNoteOn(1, 64, 100);
        bridge.renderAudio(buf, 512); // first render after transition
        bridge.renderAudio(buf, 512); // second render: new note in attack phase

        boolean activeAfterTransition = bridge.hasActivePartials();
        int partStatesAfterTransition = bridge.getPartStates();
        byte[] keys2 = new byte[4], vels2 = new byte[4];
        int noteCountAfterTransition = bridge.getPlayingNotes(0, keys2, vels2);

        System.out.printf(
                "[Step 4] hasActivePartials=%b  partStates=0x%02X  playingNotes(part0)=%d",
                activeAfterTransition, partStatesAfterTransition, noteCountAfterTransition);
        if (noteCountAfterTransition > 0)
            System.out.printf("  key=%d vel=%d", keys2[0] & 0xFF, vels2[0] & 0xFF);
        System.out.println();

        // Render up to 20 more cycles to capture audio (MT-32 attack may lag)
        boolean hasAudio = false;
        for (int chunk = 0; chunk < 20 && !hasAudio; chunk++)
        {
            bridge.renderAudio(buf, 512);
            for (short s : buf)
            {
                if (s != 0)
                {
                    hasAudio = true;
                    break;
                }
            }
        }
        System.out.printf("[Step 4] hasAudio=%b%n", hasAudio);

        assertTrue(
                activeAfterTransition, "Step 4: NoteOn after transition should activate partials");
        assertTrue(noteCountAfterTransition > 0,
                "Step 4: NoteOn after transition should register as a playing note on Part 0");
        assertTrue(hasAudio, "Step 4: NoteOn after transition should produce non-silent PCM");

        bridge.close();
    }
}
