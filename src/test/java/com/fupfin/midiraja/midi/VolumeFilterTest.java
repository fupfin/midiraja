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

class VolumeFilterTest
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

    private static byte[] cc7(int channel, int value)
    {
        return new byte[] { (byte) (0xB0 | channel), 7, (byte) value };
    }

    private static byte[] noteOn(int channel, int note, int velocity)
    {
        return new byte[] { (byte) (0x90 | channel), (byte) note, (byte) velocity };
    }

    // ── CC#7 scaling ─────────────────────────────────────────────────────────

    @Test
    void cc7_volumeScale_half_halves_value() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.5);

        filter.sendMessage(cc7(0, 100));

        assertEquals(1, rec.received.size());
        assertEquals(50, rec.received.get(0)[2] & 0xFF, "100 * 0.5 = 50");
    }

    @Test
    void cc7_volumeScale_zero_mutes() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.0);

        filter.sendMessage(cc7(0, 127));

        assertEquals(0, rec.received.get(0)[2] & 0xFF, "volume scale 0.0 must mute to 0");
    }

    @Test
    void cc7_volumeScale_one_passes_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 1.0);

        filter.sendMessage(cc7(0, 100));

        assertArrayEquals(cc7(0, 100), rec.received.get(0), "scale 1.0 must not modify data");
    }

    @Test
    void cc7_original_array_not_mutated() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.5);
        byte[] msg = cc7(0, 100);

        filter.sendMessage(msg);

        assertEquals(100, msg[2] & 0xFF, "original array must not be modified");
    }

    @Test
    void cc7_all_channels_treated_equally() throws Exception
    {
        for (int ch = 0; ch <= 15; ch++)
        {
            var rec = new RecordingProcessor();
            var filter = new VolumeFilter(rec, 0.5);
            filter.sendMessage(cc7(ch, 80));
            assertEquals(40, rec.received.get(0)[2] & 0xFF,
                "channel " + ch + " must apply volume scale");
        }
    }

    // ── passthrough cases ─────────────────────────────────────────────────────

    @Test
    void noteOn_always_passes_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.5);
        byte[] msg = noteOn(0, 60, 100);

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "Note On must pass unmodified");
    }

    @Test
    void other_cc_passes_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.5);
        // CC#1 (Modulation)
        byte[] msg = { (byte) 0xB0, 1, 64 };

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "CC#1 must pass unmodified");
    }

    @Test
    void sysex_passes_unmodified() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.5);
        byte[] msg = { (byte) 0xF0, 0x41, 0x10, (byte) 0xF7 };

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "SysEx must pass unmodified");
    }

    @Test
    void null_data_forwarded() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.5);

        filter.sendMessage(null);

        assertEquals(1, rec.received.size());
        assertNull(rec.received.get(0));
    }

    @Test
    void short_array_less_than_3_forwarded() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.5);
        byte[] msg = { (byte) 0xB0, 7 }; // only 2 bytes

        filter.sendMessage(msg);

        assertArrayEquals(msg, rec.received.get(0), "short array must pass unmodified");
    }

    // ── adjust() clamping ─────────────────────────────────────────────────────

    @Test
    void adjust_clamps_above_1() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.9);

        filter.adjust(0.5); // would be 1.4 → clamped to 1.0

        assertEquals(1.0, filter.getVolumeScale(), 1e-9);
        filter.sendMessage(cc7(0, 100));
        assertArrayEquals(cc7(0, 100), rec.received.get(0), "scale 1.0 must not modify CC#7");
    }

    @Test
    void adjust_clamps_below_0() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 0.1);

        filter.adjust(-0.5); // would be -0.4 → clamped to 0.0

        assertEquals(0.0, filter.getVolumeScale(), 1e-9);
        filter.sendMessage(cc7(0, 127));
        assertEquals(0, rec.received.get(0)[2] & 0xFF);
    }

    @Test
    void initial_scale_clamped_at_construction()
    {
        var rec = new RecordingProcessor();
        var filter = new VolumeFilter(rec, 2.0);
        assertEquals(1.0, filter.getVolumeScale(), 1e-9);

        var filter2 = new VolumeFilter(rec, -0.5);
        assertEquals(0.0, filter2.getVolumeScale(), 1e-9);
    }
}
