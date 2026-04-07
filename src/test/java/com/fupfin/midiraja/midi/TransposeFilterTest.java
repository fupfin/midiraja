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

class TransposeFilterTest
{
    static class RecordingProcessor implements MidiProcessor
    {
        final List<byte[]> received = new ArrayList<>();

        @Override
        public void sendMessage(byte[] data) throws Exception
        {
            received.add(data == null ? null : data.clone());
        }
    }

    private static byte[] noteOn(int channel, int note, int velocity)
    {
        return new byte[] { (byte) (0x90 | channel), (byte) note, (byte) velocity };
    }

    private static byte[] noteOff(int channel, int note, int velocity)
    {
        return new byte[] { (byte) (0x80 | channel), (byte) note, (byte) velocity };
    }

    private static byte[] cc(int channel, int cc, int value)
    {
        return new byte[] { (byte) (0xB0 | channel), (byte) cc, (byte) value };
    }

    // ── basic transposition ───────────────────────────────────────────────────

    @Test
    void noteOn_transposed_up_by_12() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);

        filter.sendMessage(noteOn(0, 60, 100));

        assertEquals(72, rec.received.get(0)[1] & 0xFF, "C4(60) + 12 = C5(72)");
    }

    @Test
    void noteOff_transposed_down_by_12() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, -12);

        filter.sendMessage(noteOff(0, 60, 0));

        assertEquals(48, rec.received.get(0)[1] & 0xFF, "C4(60) - 12 = C3(48)");
    }

    @Test
    void semitones_zero_passes_note_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 0);
        byte[] msg = noteOn(0, 60, 100);

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "semitones=0 must not modify message");
    }

    @Test
    void default_constructor_has_zero_semitones() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec);
        byte[] msg = noteOn(0, 60, 100);

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0));
        assertEquals(0, filter.getSemitones());
    }

    @Test
    void original_array_not_mutated() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 5);
        byte[] msg = noteOn(0, 60, 100);

        filter.sendMessage(msg);

        assertEquals(60, msg[1] & 0xFF, "original array must not be modified");
    }

    // ── drum channel (ch 9) exemption ─────────────────────────────────────────

    @Test
    void drum_channel_9_note_on_not_transposed() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);
        byte[] msg = noteOn(9, 38, 100); // snare on drum channel

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "drum channel (ch 9) must not be transposed");
    }

    @Test
    void drum_channel_9_note_off_not_transposed() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);
        byte[] msg = noteOff(9, 38, 0);

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0));
    }

    @Test
    void non_drum_channels_all_transposed() throws Exception
    {
        for (int ch : new int[] { 0, 1, 8, 10, 15 })
        {
            var rec = new RecordingProcessor();
            var filter = new TransposeFilter(rec, 1);
            filter.sendMessage(noteOn(ch, 60, 100));
            assertEquals(61, rec.received.get(0)[1] & 0xFF,
                    "channel " + ch + " must be transposed");
        }
    }

    // ── clamping ──────────────────────────────────────────────────────────────

    @Test
    void note_clamped_at_127_on_overflow() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 100);

        filter.sendMessage(noteOn(0, 60, 100)); // 60 + 100 = 160 → 127

        assertEquals(127, rec.received.get(0)[1] & 0xFF, "note must clamp to 127");
    }

    @Test
    void note_clamped_at_0_on_underflow() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, -100);

        filter.sendMessage(noteOn(0, 12, 100)); // 12 - 100 = -88 → 0

        assertEquals(0, rec.received.get(0)[1] & 0xFF, "note must clamp to 0");
    }

    @Test
    void note_127_plus_1_clamps_to_127() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 1);
        filter.sendMessage(noteOn(0, 127, 100));
        assertEquals(127, rec.received.get(0)[1] & 0xFF);
    }

    @Test
    void note_0_minus_1_clamps_to_0() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, -1);
        filter.sendMessage(noteOn(0, 0, 100));
        assertEquals(0, rec.received.get(0)[1] & 0xFF);
    }

    // ── passthrough for non-note messages ─────────────────────────────────────

    @Test
    void cc_passes_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);
        byte[] msg = cc(0, 7, 100);

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "CC must pass unmodified");
    }

    @Test
    void program_change_passes_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);
        byte[] msg = { (byte) 0xC0, 42 };

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "Program Change must pass unmodified");
    }

    @Test
    void sysex_passes_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);
        byte[] msg = { (byte) 0xF0, 0x41, (byte) 0xF7 };

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "SysEx must pass unmodified");
    }

    @Test
    void null_forwarded() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);

        filter.sendMessage(null);

        assertEquals(1, rec.received.size());
        assertNull(rec.received.get(0));
    }

    @Test
    void short_array_less_than_2_forwarded() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);
        byte[] msg = { (byte) 0x90 }; // only status byte

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0));
    }

    // ── adjust() and setSemitones() ───────────────────────────────────────────

    @Test
    void adjust_accumulates() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 0);

        filter.adjust(5);
        filter.adjust(3);

        assertEquals(8, filter.getSemitones());
        filter.sendMessage(noteOn(0, 60, 100));
        assertEquals(68, rec.received.get(0)[1] & 0xFF);
    }

    @Test
    void setSemitones_overrides_previous() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new TransposeFilter(rec, 12);

        filter.setSemitones(-7);

        assertEquals(-7, filter.getSemitones());
        filter.sendMessage(noteOn(0, 60, 100));
        assertEquals(53, rec.received.get(0)[1] & 0xFF, "60 - 7 = 53");
    }
}
