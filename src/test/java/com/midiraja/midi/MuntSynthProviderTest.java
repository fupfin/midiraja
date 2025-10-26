/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

class MuntSynthProviderTest {

    // A mock bridge to test MuntSynthProvider's routing logic without the native C library
    static class MockMuntBridge implements MuntNativeBridge {
        boolean created = false;
        boolean opened = false;
        boolean closed = false;
        String loadedRomsDir = null;
        List<String> eventLog = new ArrayList<>();

        @Override
        public void createSynth() { created = true; }

        @Override
        public void loadRoms(String romDirectory) { loadedRomsDir = romDirectory; }

        @Override
        public void openSynth() { opened = true; }

        @Override
        public void playNoteOn(int channel, int key, int velocity) {
            eventLog.add(String.format("NOTE_ON ch:%d key:%d vel:%d", channel, key, velocity));
        }

        @Override
        public void playNoteOff(int channel, int key) {
            eventLog.add(String.format("NOTE_OFF ch:%d key:%d", channel, key));
        }

        @Override
        public void playControlChange(int channel, int number, int value) {
            eventLog.add(String.format("CC ch:%d num:%d val:%d", channel, number, value));
        }

        @Override
        public void playProgramChange(int channel, int program) {
            eventLog.add(String.format("PC ch:%d prog:%d", channel, program));
        }

        @Override
        public void playPitchBend(int channel, int value) {
            eventLog.add(String.format("PB ch:%d val:%d", channel, value));
        }

        @Override
        public void playSysex(byte[] sysexData) {
            eventLog.add(String.format("SYSEX len:%d", sysexData.length));
        }

        @Override
        public void renderAudio(short[] buffer, int frames) {}

        @Override
        public void close() { closed = true; }
    }

    @Test
    void testLifecycleAndMidiRouting() throws Exception {
        MockMuntBridge mockBridge = new MockMuntBridge();
        
        // Provider takes the bridge and the audio engine. We'll pass null for audio engine 
        // in this test since we're only testing the MIDI routing and lifecycle.
        MuntSynthProvider provider = new MuntSynthProvider(mockBridge, null);
        
        // 1. Test Initialization Lifecycle
        provider.loadSoundbank("/path/to/roms");
        assertTrue(mockBridge.opened, "Synth should be opened after loadSoundbank");
        assertEquals("/path/to/roms", mockBridge.loadedRomsDir, "ROM directory should be passed to bridge");

        provider.openPort(0);
        assertTrue(mockBridge.created, "Context should be created after openPort");

        // 2. Test MIDI Message Routing
        // Note On
        provider.sendMessage(new byte[]{(byte) 0x90, 60, 100});
        // Note Off
        provider.sendMessage(new byte[]{(byte) 0x81, 64, 0});
        // Note On with vel 0 -> Note On (Munt bridge will handle it internally)
        provider.sendMessage(new byte[]{(byte) 0x92, 65, 0});
        // CC
        provider.sendMessage(new byte[]{(byte) 0xB3, 7, 120});
        // Program Change
        provider.sendMessage(new byte[]{(byte) 0xC4, 5});
        // Pitch Bend (Center)
        provider.sendMessage(new byte[]{(byte) 0xE5, 0, 64});
        // SysEx
        provider.sendMessage(new byte[]{(byte) 0xF0, 0x41, 0x10, 0x16, 0x12, 0x40, 0x00, 0x7F, 0x00, 0x41, (byte) 0xF7});

        assertEquals(7, mockBridge.eventLog.size());
        assertEquals("NOTE_ON ch:0 key:60 vel:100", mockBridge.eventLog.get(0));
        assertEquals("NOTE_OFF ch:1 key:64", mockBridge.eventLog.get(1));
        assertEquals("NOTE_ON ch:2 key:65 vel:0", mockBridge.eventLog.get(2));
        assertEquals("CC ch:3 num:7 val:120", mockBridge.eventLog.get(3));
        assertEquals("PC ch:4 prog:5", mockBridge.eventLog.get(4));
        assertEquals("PB ch:5 val:8192", mockBridge.eventLog.get(5));
        assertEquals("SYSEX len:11", mockBridge.eventLog.get(6));

        // 3. Test Edge Cases
        assertDoesNotThrow(() -> provider.sendMessage(null));
        assertDoesNotThrow(() -> provider.sendMessage(new byte[0]));
        assertDoesNotThrow(() -> provider.sendMessage(new byte[]{(byte)0x90})); // Malformed short message

        // 4. Test Closure
        provider.closePort();
        assertTrue(mockBridge.closed, "Bridge should be closed");
    }
}