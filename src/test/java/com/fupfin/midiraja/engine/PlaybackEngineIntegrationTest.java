/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.midi.MuntNativeBridge;
import com.fupfin.midiraja.midi.MuntSynthProvider;
import com.fupfin.midiraja.ui.DumbUI;
import java.io.File;
import java.util.List;
import java.util.Optional;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Integration test that verifies every MIDI event in monkey_island.mid is correctly
 * delivered through PlaybackEngine → MuntSynthProvider → MuntNativeBridge.
 * Uses a counting mock bridge so no native library or ROM files are required.
 */
@EnabledIf("monkeyIslandMidPresent") class PlaybackEngineIntegrationTest
{
    static boolean monkeyIslandMidPresent()
    {
        return new File("monkey_island.mid").exists();
    }

    static class CountingMuntBridge implements MuntNativeBridge
    {
        int noteOnCount, noteOffCount, ccCount, pcCount, pbCount;

        @Override public void createSynth()
        {
        }
        @Override public void loadRoms(String romDirectory)
        {
        }
        @Override public void openSynth()
        {
        }
        @Override public void playSysex(byte[] sysexData)
        {
        }
        @Override public void renderAudio(short[] buffer, int frames)
        {
        }
        @Override public void close()
        {
        }

        @Override public void playNoteOn(int channel, int key, int velocity)
        {
            noteOnCount++;
        }
        @Override public void playNoteOff(int channel, int key)
        {
            noteOffCount++;
        }
        @Override public void playControlChange(int channel, int number, int value)
        {
            ccCount++;
        }
        @Override public void playProgramChange(int channel, int program)
        {
            pcCount++;
        }
        @Override public void playPitchBend(int channel, int value)
        {
            pbCount++;
        }
    }

    @Test void testAllEventsDelivered() throws Exception
    {
        // 1. Parse monkey_island.mid with the Java MIDI API
        Sequence seq = MidiSystem.getSequence(new File("monkey_island.mid"));

        // 2. Count expected events by iterating all tracks.
        //    MuntSynthProvider.sendMessage() routes:
        //      0x90 (any velocity, including vel=0) → playNoteOn()
        //      0x80                                  → playNoteOff()
        //      0xB0                                  → playControlChange()
        //      0xC0                                  → playProgramChange()
        int expectedNoteOn = 0, expectedNoteOff = 0, expectedCC = 0, expectedPC = 0;
        for (Track track : seq.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                byte[] raw = track.get(i).getMessage().getMessage();
                int status = raw[0] & 0xFF;
                if (status < 0xF0 && raw.length >= 2)
                {
                    switch (status & 0xF0)
                    {
                        case 0x90 -> expectedNoteOn++; // all NoteOn, including vel=0
                        case 0x80 -> expectedNoteOff++;
                        case 0xB0 -> expectedCC++;
                        case 0xC0 -> expectedPC++;
                    }
                }
            }
        }

        // 3. Wire up: MuntSynthProvider with the counting bridge; no audio engine needed
        CountingMuntBridge bridge = new CountingMuntBridge();
        MuntSynthProvider provider = new MuntSynthProvider(bridge, null);
        provider.openPort(0);
        provider.loadSoundbank("");

        // 4. Run PlaybackEngine at 1000x speed so the file completes in ~200ms wall-clock time
        PlaylistContext ctx = new PlaylistContext(
            List.of(new File("monkey_island.mid")), 0, new MidiPort(0, "Test"), null, false, false);

        PlaybackEngine engine =
            new PlaybackEngine(seq, provider, ctx, 100, 1000.0, Optional.empty(), Optional.empty());

        MockTerminalIO mockIO = new MockTerminalIO();
        ScopedValue.where(TerminalIO.CONTEXT, mockIO).call(() -> {
            engine.start(new DumbUI());
            return null;
        });

        // 5. After playback, provider.panic() sends all-notes-off to
        // MuntSynthProvider.sendMessage(),
        //    which routes them through the counting bridge. Account for these in expectations:
        //      16 channels × 4 CC messages (sustain-off, all-notes-off, all-sound-off, reset-CC) =
        //      64 16 channels × 128 note-off messages = 2048
        final int PANIC_CC_COUNT = 16 * 4;
        final int PANIC_NOTEOFF_COUNT = 16 * 128;

        assertEquals(expectedNoteOn, bridge.noteOnCount, "NoteOn count mismatch");
        assertEquals(
            expectedNoteOff + PANIC_NOTEOFF_COUNT, bridge.noteOffCount, "NoteOff count mismatch");
        assertEquals(expectedCC + PANIC_CC_COUNT, bridge.ccCount, "CC count mismatch");
        assertEquals(expectedPC, bridge.pcCount, "PC count mismatch");
    }
}
