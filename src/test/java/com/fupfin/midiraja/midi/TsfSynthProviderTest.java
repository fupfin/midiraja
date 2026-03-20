/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TsfSynthProviderTest
{
    /** Mock bridge to test TsfSynthProvider's routing logic without libtsf. */
    static class MockTsfBridge implements TsfNativeBridge
    {
        boolean initialized = false;
        boolean closed = false;
        boolean panicCalled = false;
        boolean resetCalled = false;
        String soundfontPath = null;
        int soundfontSampleRate = -1;
        List<String> eventLog = new ArrayList<>();

        @Override public void loadSoundfontFile(String path, int sampleRate)
        {
            soundfontPath = path;
            soundfontSampleRate = sampleRate;
            initialized = true;
        }
        @Override public void init(int sampleRate)
        {
        }
        @Override public void reset()
        {
            resetCalled = true;
        }
        @Override public void panic()
        {
            panicCalled = true;
        }
        @Override public void noteOn(int channel, int note, int velocity)
        {
            eventLog.add(String.format("NOTE_ON ch:%d note:%d vel:%d", channel, note, velocity));
        }
        @Override public void noteOff(int channel, int note)
        {
            eventLog.add(String.format("NOTE_OFF ch:%d note:%d", channel, note));
        }
        @Override public void controlChange(int channel, int type, int value)
        {
            eventLog.add(String.format("CC ch:%d type:%d val:%d", channel, type, value));
        }
        @Override public void patchChange(int channel, int patch)
        {
            eventLog.add(String.format("PC ch:%d patch:%d", channel, patch));
        }
        @Override public void pitchBend(int channel, int pitch)
        {
            eventLog.add(String.format("PB ch:%d pitch:%d", channel, pitch));
        }
        @Override public void systemExclusive(byte[] data)
        {
            eventLog.add(String.format("SYSEX len:%d", data.length));
        }
        @Override public void generate(short[] buffer, int stereoFrames)
        {
        }
        @Override public void close()
        {
            closed = true;
        }
    }

    @Test void testLifecycleAndMidiRouting() throws Exception
    {
        MockTsfBridge mockBridge = new MockTsfBridge();
        TsfSynthProvider provider = new TsfSynthProvider(mockBridge, null, null);

        // openPort is a no-op for TSF
        provider.openPort(0);
        assertFalse(mockBridge.initialized, "TSF device is created in loadSoundbank, not openPort");

        // loadSoundbank calls loadSoundfontFile
        provider.loadSoundbank("/path/to/test.sf2");
        assertTrue(mockBridge.initialized, "loadSoundfontFile should be called in loadSoundbank");
        assertEquals("/path/to/test.sf2", mockBridge.soundfontPath);
        assertEquals(44100, mockBridge.soundfontSampleRate);

        // MIDI routing
        provider.sendMessage(new byte[] {(byte) 0x90, 60, 100});
        provider.sendMessage(new byte[] {(byte) 0x80, 64, 0});
        provider.sendMessage(new byte[] {(byte) 0xB0, 7, 120});
        provider.sendMessage(new byte[] {(byte) 0xC0, 5});
        provider.sendMessage(new byte[] {(byte) 0xE0, 0, 64});
        provider.sendMessage(new byte[] {(byte) 0xF0, 0x7E, 0x7F, 0x09, 0x01, (byte) 0xF7});

        provider.flushEventQueueForTest();

        assertEquals(6, mockBridge.eventLog.size(), "All 6 MIDI events should be dispatched");
        assertEquals("NOTE_ON ch:0 note:60 vel:100", mockBridge.eventLog.get(0));
        assertEquals("NOTE_OFF ch:0 note:64", mockBridge.eventLog.get(1));
        assertEquals("CC ch:0 type:7 val:120", mockBridge.eventLog.get(2));
        assertEquals("PC ch:0 patch:5", mockBridge.eventLog.get(3));
        assertEquals("PB ch:0 pitch:8192", mockBridge.eventLog.get(4));
        assertEquals("SYSEX len:6", mockBridge.eventLog.get(5));

        // Closure
        provider.closePort();
        assertTrue(mockBridge.closed, "Bridge should be closed on closePort");
    }

    @Test void testPanic() throws Exception
    {
        MockTsfBridge mockBridge = new MockTsfBridge();
        TsfSynthProvider provider = new TsfSynthProvider(mockBridge, null, null);

        provider.panic();

        // bridge.panic() must NOT be called directly from provider.panic() (not thread-safe)
        assertFalse(mockBridge.panicCalled,
            "bridge.panic() should NOT be called from provider.panic()");

        // 16 channels × 4 CCs = 64 events queued
        provider.flushEventQueueForTest();
        assertEquals(64, mockBridge.eventLog.size(),
            "panic() should enqueue 4 CCs per channel × 16 channels = 64 events");
    }

    @Test void testPrepareForNewTrack() throws Exception
    {
        MockTsfBridge mockBridge = new MockTsfBridge();
        TsfSynthProvider provider = new TsfSynthProvider(mockBridge, null, null);

        provider.sendMessage(new byte[] {(byte) 0x90, 60, 100});

        provider.prepareForNewTrack(null);

        assertTrue(mockBridge.panicCalled,
            "bridge.panic() should be called in prepareForNewTrack");
        assertTrue(mockBridge.resetCalled, "bridge.reset() should be called in prepareForNewTrack");

        provider.flushEventQueueForTest();
        // prepareForNewTrack clears stale events and queues CC121 on all 16 channels to
        // initialise f->channels in TSF (tsf_channel_note_on is a no-op when f->channels==NULL).
        assertEquals(16, mockBridge.eventLog.size(),
            "prepareForNewTrack should queue CC121 (Reset All Controllers) on all 16 channels");
        for (int ch = 0; ch < 16; ch++)
        {
            assertEquals("CC ch:" + ch + " type:121 val:0", mockBridge.eventLog.get(ch),
                "Channel " + ch + " should have CC121=0 (Reset All Controllers)");
        }
    }

    @Test void testGetOutputPorts()
    {
        MockTsfBridge mockBridge = new MockTsfBridge();
        TsfSynthProvider provider = new TsfSynthProvider(mockBridge, null, null);

        var ports = provider.getOutputPorts();
        assertEquals(1, ports.size());
        assertEquals("SoundFont", ports.get(0).name());
    }
}
