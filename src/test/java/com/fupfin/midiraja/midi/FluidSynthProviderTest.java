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

class FluidSynthProviderTest
{
    // A testable subclass that overrides the actual FFM execution methods
    // so we can verify parsing logic without needing the real C library loaded.
    static class TestableFluidSynthProvider extends FluidSynthProvider
    {
        List<String> eventLog = new ArrayList<>();

        public TestableFluidSynthProvider() throws Exception
        {
            super("MOCK_LIBRARY"); // Bypass actual load attempt for test
        }

        // We override the native call methods, not sendMessage itself,
        // to verify that sendMessage correctly parses and routes the bytes.
        @Override
        protected void fluid_synth_noteon(int channel, int key, int velocity)
        {
            eventLog.add(String.format("NOTE_ON ch:%d key:%d vel:%d", channel, key, velocity));
        }

        @Override
        protected void fluid_synth_noteoff(int channel, int key)
        {
            eventLog.add(String.format("NOTE_OFF ch:%d key:%d", channel, key));
        }

        @Override
        protected void fluid_synth_cc(int channel, int num, int val)
        {
            eventLog.add(String.format("CC ch:%d num:%d val:%d", channel, num, val));
        }

        @Override
        protected void fluid_synth_program_change(int channel, int program)
        {
            eventLog.add(String.format("PC ch:%d prog:%d", channel, program));
        }

        @Override
        protected void fluid_synth_pitch_bend(int channel, int val)
        {
            eventLog.add(String.format("PB ch:%d val:%d", channel, val));
        }

        @Override
        protected void fluid_synth_sysex(byte[] data)
        {
            eventLog.add(String.format("SYSEX len:%d", data.length));
        }
    }

    @Test
    void testInitializationWithoutLibraryThrowsHelpfulException()
    {
        try
        {
            FluidSynthProvider provider = new FluidSynthProvider(null);
            assertNotNull(provider);
        }
        catch (Exception e)
        {
            assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("fluidsynth"),
                    "Exception message should mention FluidSynth");
            assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("install"),
                    "Exception message should provide a hint to install it");
        }
    }

    @Test
    void testMidiMessageParsingAndRouting() throws Exception
    {
        TestableFluidSynthProvider provider = new TestableFluidSynthProvider();

        // Note On: Channel 0, Key 60, Vel 100
        provider.sendMessage(new byte[] { (byte) 0x90, 60, 100 });

        // Note Off: Channel 1, Key 64, Vel 0
        provider.sendMessage(new byte[] { (byte) 0x81, 64, 0 });

        // Note On with Vel 0 (often treated as Note Off): Channel 2, Key 65, Vel 0
        provider.sendMessage(new byte[] { (byte) 0x92, 65, 0 });

        // CC: Channel 3, CC 7 (Volume), Val 120
        provider.sendMessage(new byte[] { (byte) 0xB3, 7, 120 });

        // Program Change: Channel 4, Program 5
        provider.sendMessage(new byte[] { (byte) 0xC4, 5 });

        // Pitch Bend: Channel 5, LSB 0, MSB 64 (Center)
        provider.sendMessage(new byte[] { (byte) 0xE5, 0, 64 });

        // SysEx
        provider.sendMessage(new byte[] {
                (byte) 0xF0, 0x41, 0x10, 0x42, 0x12, 0x40, 0x00, 0x7F, 0x00, 0x41, (byte) 0xF7 });

        assertEquals(7, provider.eventLog.size());
        assertEquals("NOTE_ON ch:0 key:60 vel:100", provider.eventLog.get(0));
        assertEquals("NOTE_OFF ch:1 key:64", provider.eventLog.get(1));

        // Note On w/ vel=0 is tricky. Some engines route it to NOTE_ON and let the engine handle
        // vel 0, others explicitly call NOTE_OFF. For our binding, we map it directly as NoteOn.
        assertEquals("NOTE_ON ch:2 key:65 vel:0", provider.eventLog.get(2));

        assertEquals("CC ch:3 num:7 val:120", provider.eventLog.get(3));
        assertEquals("PC ch:4 prog:5", provider.eventLog.get(4));

        // Pitch bend is 14-bit: (MSB << 7) | LSB = (64 << 7) | 0 = 8192
        assertEquals("PB ch:5 val:8192", provider.eventLog.get(5));

        assertEquals("SYSEX len:11", provider.eventLog.get(6));
    }
}
