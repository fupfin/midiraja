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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MidiEventDispatcherTest
{
    // ── recording stub ────────────────────────────────────────────────────────

    static class RecordingBridge implements MidiNativeBridge
    {
        record NoteOnCall(int channel, int note, int velocity)
        {
        }

        record NoteOffCall(int channel, int note)
        {
        }

        record ControlChangeCall(int channel, int cc, int value)
        {
        }

        record PatchChangeCall(int channel, int program)
        {
        }

        record PitchBendCall(int channel, int bend)
        {
        }

        final List<NoteOnCall> noteOns = new ArrayList<>();
        final List<NoteOffCall> noteOffs = new ArrayList<>();
        final List<ControlChangeCall> controlChanges = new ArrayList<>();
        final List<PatchChangeCall> patchChanges = new ArrayList<>();
        final List<PitchBendCall> pitchBends = new ArrayList<>();
        final List<byte[]> sysexMessages = new ArrayList<>();

        @Override
        public void noteOn(int channel, int note, int velocity)
        {
            noteOns.add(new NoteOnCall(channel, note, velocity));
        }

        @Override
        public void noteOff(int channel, int note)
        {
            noteOffs.add(new NoteOffCall(channel, note));
        }

        @Override
        public void controlChange(int channel, int cc, int value)
        {
            controlChanges.add(new ControlChangeCall(channel, cc, value));
        }

        @Override
        public void patchChange(int channel, int program)
        {
            patchChanges.add(new PatchChangeCall(channel, program));
        }

        @Override
        public void pitchBend(int channel, int bend)
        {
            pitchBends.add(new PitchBendCall(channel, bend));
        }

        @Override
        public void systemExclusive(byte[] data)
        {
            sysexMessages.add(data.clone());
        }

        @Override
        public void init(int sampleRate)
        {
        }

        @Override
        public void generate(short[] buffer, int frames)
        {
        }

        @Override
        public void panic()
        {
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void close()
        {
        }
    }

    private RecordingBridge bridge;
    private MidiEventDispatcher dispatcher;

    @BeforeEach
    void setUp()
    {
        bridge = new RecordingBridge();
        dispatcher = new MidiEventDispatcher(bridge);
    }

    // ── Note On (0x90) ────────────────────────────────────────────────────────

    @Test
    void noteOn_dispatched_to_bridge()
    {
        dispatcher.dispatch(new byte[] { (byte) 0x91, 60, 100 }); // ch 1, C4, vel 100

        assertEquals(1, bridge.noteOns.size());
        var call = bridge.noteOns.get(0);
        assertEquals(1, call.channel());
        assertEquals(60, call.note());
        assertEquals(100, call.velocity());
    }

    @Test
    void noteOn_channel_15_dispatched_correctly()
    {
        dispatcher.dispatch(new byte[] { (byte) 0x9F, 48, 64 });

        assertEquals(15, bridge.noteOns.get(0).channel());
        assertEquals(48, bridge.noteOns.get(0).note());
    }

    // ── Note Off (0x80) ───────────────────────────────────────────────────────

    @Test
    void noteOff_dispatched_to_bridge()
    {
        dispatcher.dispatch(new byte[] { (byte) 0x80, 60, 0 }); // ch 0, C4

        assertEquals(1, bridge.noteOffs.size());
        assertEquals(0, bridge.noteOffs.get(0).channel());
        assertEquals(60, bridge.noteOffs.get(0).note());
    }

    // ── Control Change (0xB0) ─────────────────────────────────────────────────

    @Test
    void controlChange_dispatched_to_bridge()
    {
        dispatcher.dispatch(new byte[] { (byte) 0xB0, 7, 100 }); // ch 0, CC#7 vol=100

        assertEquals(1, bridge.controlChanges.size());
        var call = bridge.controlChanges.get(0);
        assertEquals(0, call.channel());
        assertEquals(7, call.cc());
        assertEquals(100, call.value());
    }

    @Test
    void allNotesOff_cc123_dispatched()
    {
        dispatcher.dispatch(new byte[] { (byte) 0xB9, 123, 0 }); // ch 9, All Notes Off

        assertEquals(1, bridge.controlChanges.size());
        assertEquals(9, bridge.controlChanges.get(0).channel());
        assertEquals(123, bridge.controlChanges.get(0).cc());
    }

    // ── Program Change (0xC0) ─────────────────────────────────────────────────

    @Test
    void programChange_dispatched_to_bridge()
    {
        dispatcher.dispatch(new byte[] { (byte) 0xC3, 42 }); // ch 3, program 42

        assertEquals(1, bridge.patchChanges.size());
        assertEquals(3, bridge.patchChanges.get(0).channel());
        assertEquals(42, bridge.patchChanges.get(0).program());
    }

    @Test
    void programChange_missing_data2_uses_zero()
    {
        // Program Change only needs 2 bytes; data2 defaults to 0
        dispatcher.dispatch(new byte[] { (byte) 0xC0, 10 });

        assertEquals(10, bridge.patchChanges.get(0).program());
    }

    // ── Pitch Bend (0xE0) ────────────────────────────────────────────────────

    @Test
    void pitchBend_decoded_as_14bit_little_endian()
    {
        // bend = (data2 << 7) | data1
        // Center = 0x40 00 = data1=0x00, data2=0x40 → bend = (0x40 << 7) | 0 = 8192
        dispatcher.dispatch(new byte[] { (byte) 0xE0, 0x00, 0x40 }); // center

        assertEquals(1, bridge.pitchBends.size());
        assertEquals(8192, bridge.pitchBends.get(0).bend(), "center pitch bend = 8192");
    }

    @Test
    void pitchBend_max_value()
    {
        // data1=0x7F, data2=0x7F → (0x7F << 7) | 0x7F = 16383
        dispatcher.dispatch(new byte[] { (byte) 0xE0, 0x7F, 0x7F });

        assertEquals(16383, bridge.pitchBends.get(0).bend());
    }

    // ── SysEx (0xF0) ─────────────────────────────────────────────────────────

    @Test
    void sysex_dispatched_to_bridge()
    {
        byte[] msg = { (byte) 0xF0, 0x41, 0x10, 0x42, (byte) 0xF7 };
        dispatcher.dispatch(msg);

        assertEquals(1, bridge.sysexMessages.size());
    }

    @Test
    void sysex_single_byte_0xF0_not_dispatched()
    {
        // length == 1 → data.length > 1 is false → not dispatched
        dispatcher.dispatch(new byte[] { (byte) 0xF0 });

        assertTrue(bridge.sysexMessages.isEmpty(),
                "single-byte 0xF0 must not be dispatched to systemExclusive");
    }

    @Test
    void other_system_messages_not_dispatched_to_any_bridge_method()
    {
        dispatcher.dispatch(new byte[] { (byte) 0xF8 }); // Timing Clock
        dispatcher.dispatch(new byte[] { (byte) 0xFE }); // Active Sensing

        assertTrue(bridge.noteOns.isEmpty());
        assertTrue(bridge.sysexMessages.isEmpty(), "non-SysEx system messages are silently ignored");
    }

    // ── unknown command ignored ───────────────────────────────────────────────

    @Test
    void unknown_channel_command_0xA0_aftertouch_ignored()
    {
        // Polyphonic Aftertouch (0xA0) not in switch → ignored
        dispatcher.dispatch(new byte[] { (byte) 0xA0, 60, 64 });

        assertTrue(bridge.noteOns.isEmpty());
        assertTrue(bridge.noteOffs.isEmpty());
        assertTrue(bridge.controlChanges.isEmpty());
    }

    // ── null / empty / short arrays ───────────────────────────────────────────

    @Test
    void null_data_is_ignored()
    {
        assertDoesNotThrow(() -> dispatcher.dispatch(null));

        assertTrue(bridge.noteOns.isEmpty());
    }

    @Test
    void empty_array_is_ignored()
    {
        assertDoesNotThrow(() -> dispatcher.dispatch(new byte[0]));

        assertTrue(bridge.noteOns.isEmpty());
    }

    @Test
    void status_only_array_length_1_not_dispatched_for_channel_messages()
    {
        // data.length < 2 guard prevents dispatch
        dispatcher.dispatch(new byte[] { (byte) 0x90 });

        assertTrue(bridge.noteOns.isEmpty(), "1-byte channel message must be ignored");
    }

    @Test
    void high_bit_values_in_data_bytes_handled_as_unsigned()
    {
        // Note 60, velocity 200 (> 127, but & 0xFF = 200)
        dispatcher.dispatch(new byte[] { (byte) 0x90, 60, (byte) 200 });

        assertEquals(200, bridge.noteOns.get(0).velocity(),
                "velocity byte must be treated as unsigned (& 0xFF)");
    }
}
