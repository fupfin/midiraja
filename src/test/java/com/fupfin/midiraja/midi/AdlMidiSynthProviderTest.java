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

class AdlMidiSynthProviderTest
{
    /** Mock bridge to test AdlMidiSynthProvider's routing logic without libADLMIDI. */
    static class MockAdlBridge implements AdlMidiNativeBridge
    {
        boolean initialized = false;
        boolean closed = false;
        boolean panicCalled = false;
        boolean resetCalled = false;
        int bankSet = -1;
        String bankFileLoaded = null;
        int numChipsSet = -1;
        List<String> eventLog = new ArrayList<>();
        // Configurable generate behavior for testing
        java.util.function.Consumer<short[]> generateCallback = buf -> {};

        @Override
        public void init(int sampleRate)
        {
            initialized = true;
        }

        @Override
        public void setBank(int bankNumber)
        {
            bankSet = bankNumber;
        }

        @Override
        public void loadBankFile(String path)
        {
            bankFileLoaded = path;
        }

        @Override
        public void setNumChips(int numChips)
        {
            numChipsSet = numChips;
        }

        @Override
        public void switchEmulator(int emulatorId)
        {
        }

        @Override
        public void reset()
        {
            resetCalled = true;
        }

        @Override
        public void panic()
        {
            panicCalled = true;
        }

        @Override
        public void noteOn(int channel, int note, int velocity)
        {
            eventLog.add(String.format("NOTE_ON ch:%d note:%d vel:%d", channel, note, velocity));
        }

        @Override
        public void noteOff(int channel, int note)
        {
            eventLog.add(String.format("NOTE_OFF ch:%d note:%d", channel, note));
        }

        @Override
        public void controlChange(int channel, int type, int value)
        {
            eventLog.add(String.format("CC ch:%d type:%d val:%d", channel, type, value));
        }

        @Override
        public void patchChange(int channel, int patch)
        {
            eventLog.add(String.format("PC ch:%d patch:%d", channel, patch));
        }

        @Override
        public void pitchBend(int channel, int pitch)
        {
            eventLog.add(String.format("PB ch:%d pitch:%d", channel, pitch));
        }

        @Override
        public void systemExclusive(byte[] data)
        {
            eventLog.add(String.format("SYSEX len:%d", data.length));
        }

        @Override
        public void generate(short[] buffer, int stereoFrames)
        {
            generateCallback.accept(buffer);
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    @Test
    void testLifecycleAndMidiRouting() throws Exception
    {
        MockAdlBridge mockBridge = new MockAdlBridge();
        AdlMidiSynthProvider provider = new AdlMidiSynthProvider(mockBridge, null, 0, 4, null);

        // 1. Lifecycle: openPort → init, setNumChips
        provider.openPort(0);
        assertTrue(mockBridge.initialized, "adl_init should be called in openPort");
        assertEquals(4, mockBridge.numChipsSet, "4 chips should be set by default");

        // 2. loadSoundbank with embedded bank number
        provider.loadSoundbank("bank:42");
        assertEquals(42, mockBridge.bankSet, "Bank 42 should be selected");

        // 3. MIDI routing through sendMessage → eventQueue → dispatchToNative
        // Note On
        provider.sendMessage(new byte[] { (byte) 0x90, 60, 100 });
        // Note Off
        provider.sendMessage(new byte[] { (byte) 0x81, 64, 0 });
        // Note On with vel 0
        provider.sendMessage(new byte[] { (byte) 0x92, 65, 0 });
        // CC
        provider.sendMessage(new byte[] { (byte) 0xB3, 7, 120 });
        // Program Change
        provider.sendMessage(new byte[] { (byte) 0xC4, 5 });
        // Pitch Bend (center = 8192, LSB=0, MSB=64 → (64 << 7) | 0 = 8192)
        provider.sendMessage(new byte[] { (byte) 0xE5, 0, 64 });
        // SysEx
        provider.sendMessage(new byte[] { (byte) 0xF0, 0x7E, 0x7F, 0x09, 0x01, (byte) 0xF7 });

        // Drain the event queue (normally done by render thread; no audio engine here)
        // We need to trigger the render. With audio == null, we have to invoke the
        // render path ourselves by calling prepareForNewTrack + checking state.
        // Simpler: use a test subclass or just verify queue contents after sendMessage.
        // Since render thread is not started (audio == null), we verify by starting and
        // draining manually. Instead, let's verify using a provider with a test render loop.
        // We'll create a helper that flushes the queue:
        provider.flushEventQueueForTest();

        assertEquals(7, mockBridge.eventLog.size(), "All 7 MIDI events should be dispatched");
        assertEquals("NOTE_ON ch:0 note:60 vel:100", mockBridge.eventLog.get(0));
        assertEquals("NOTE_OFF ch:1 note:64", mockBridge.eventLog.get(1));
        assertEquals("NOTE_ON ch:2 note:65 vel:0", mockBridge.eventLog.get(2));
        assertEquals("CC ch:3 type:7 val:120", mockBridge.eventLog.get(3));
        assertEquals("PC ch:4 patch:5", mockBridge.eventLog.get(4));
        assertEquals("PB ch:5 pitch:8192", mockBridge.eventLog.get(5));
        assertEquals("SYSEX len:6", mockBridge.eventLog.get(6));

        // 4. Edge cases
        assertDoesNotThrow(() -> provider.sendMessage(null));
        assertDoesNotThrow(() -> provider.sendMessage(new byte[0]));
        assertDoesNotThrow(() -> provider.sendMessage(new byte[] { (byte) 0x90 })); // too short

        // 5. Closure
        provider.closePort();
        assertTrue(mockBridge.closed, "Bridge should be closed on closePort");
    }

    @Test
    void testLoadSoundbankWithWoplFile() throws Exception
    {
        MockAdlBridge mockBridge = new MockAdlBridge();
        AdlMidiSynthProvider provider = new AdlMidiSynthProvider(mockBridge, null, 0, 4, null);
        provider.openPort(0);

        provider.loadSoundbank("/path/to/custom.wopl");
        assertEquals("/path/to/custom.wopl", mockBridge.bankFileLoaded,
                "WOPL file path should be passed to bridge.loadBankFile");
        assertEquals(-1, mockBridge.bankSet, "No embedded bank should be selected for a WOPL file");
    }

    @Test
    void testLoadSoundbankDefaultBank() throws Exception
    {
        MockAdlBridge mockBridge = new MockAdlBridge();
        AdlMidiSynthProvider provider = new AdlMidiSynthProvider(mockBridge, null, 0, 4, null);
        provider.openPort(0);

        provider.loadSoundbank("bank:0");
        assertEquals(0, mockBridge.bankSet, "Bank 0 should be selected for default");
    }

    @Test
    void testPanic() throws Exception
    {
        MockAdlBridge mockBridge = new MockAdlBridge();
        AdlMidiSynthProvider provider = new AdlMidiSynthProvider(mockBridge, null, 0, 4, null);

        provider.panic();

        // 16 channels × 4 CCs = 64 events queued; bridge.panic() NOT called
        // (bridge.panic() is only called from prepareForNewTrack() after render thread is paused)
        assertFalse(mockBridge.panicCalled,
                "bridge.panic() should NOT be called from provider.panic() (not thread-safe)");

        // Drain and verify note-off events
        provider.flushEventQueueForTest();
        // 16 channels × 4 CCs = 64 events
        assertEquals(64, mockBridge.eventLog.size(),
                "panic() should enqueue 4 CCs per channel × 16 channels = 64 events");
        assertEquals("CC ch:0 type:64 val:0", mockBridge.eventLog.get(0));
        assertEquals("CC ch:0 type:123 val:0", mockBridge.eventLog.get(1));
        assertEquals("CC ch:0 type:120 val:0", mockBridge.eventLog.get(2));
        assertEquals("CC ch:0 type:121 val:0", mockBridge.eventLog.get(3));
        // Channel 15
        assertEquals("CC ch:15 type:64 val:0", mockBridge.eventLog.get(60));
    }

    @Test
    void testPrepareForNewTrack() throws Exception
    {
        MockAdlBridge mockBridge = new MockAdlBridge();
        AdlMidiSynthProvider provider = new AdlMidiSynthProvider(mockBridge, null, 0, 4, null);

        // Queue some stale events
        provider.sendMessage(new byte[] { (byte) 0x90, 60, 100 });

        // prepareForNewTrack should clear queue, call bridge.panic(), call bridge.reset()
        provider.prepareForNewTrack(null);

        // Queue should be cleared before panic events (bridge.panic() called from render-paused
        // context)
        assertTrue(mockBridge.panicCalled,
                "bridge.panic() should be called in prepareForNewTrack (render thread is paused)");
        assertTrue(mockBridge.resetCalled, "bridge.reset() should be called in prepareForNewTrack");

        // Stale events should have been cleared (not dispatched)
        provider.flushEventQueueForTest();
        assertTrue(
                mockBridge.eventLog.isEmpty(), "Stale events should be cleared by prepareForNewTrack");
    }
}
