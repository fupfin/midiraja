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

class SysexFilterTest
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

    private static byte[] sysex(byte... data)
    {
        byte[] msg = new byte[data.length + 2];
        msg[0] = (byte) 0xF0;
        System.arraycopy(data, 0, msg, 1, data.length);
        msg[msg.length - 1] = (byte) 0xF7;
        return msg;
    }

    // ── ignoreSysex=true: SysEx dropped ──────────────────────────────────────

    @Test
    void ignoreSysex_true_drops_sysex_0xF0() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, true);

        filter.sendMessage(sysex((byte) 0x41, (byte) 0x10));

        assertTrue(rec.received.isEmpty(), "SysEx must be dropped when ignoreSysex=true");
    }

    @Test
    void ignoreSysex_true_passes_other_system_messages() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, true);

        // 0xF1 MIDI Time Code, 0xF8 Timing Clock, 0xFE Active Sensing
        for (byte status : new byte[] { (byte) 0xF1, (byte) 0xF8, (byte) 0xFE, (byte) 0xFF })
        {
            rec.received.clear();
            byte[] msg = { status };
            filter.sendMessage(msg);
            assertFalse(rec.received.isEmpty(), "0x" + Integer.toHexString(status & 0xFF) +
                    " must pass when ignoreSysex=true");
        }
    }

    @Test
    void ignoreSysex_true_eox_0xF7_passes() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, true);
        byte[] msg = { (byte) 0xF7 };

        filter.sendMessage(msg);

        assertFalse(rec.received.isEmpty(), "EOX (0xF7) is not 0xF0, must pass through");
    }

    // ── ignoreSysex=false: SysEx passes ──────────────────────────────────────

    @Test
    void ignoreSysex_false_passes_sysex() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, false);
        byte[] msg = sysex((byte) 0x41);

        filter.sendMessage(msg);

        assertFalse(rec.received.isEmpty(), "SysEx must pass when ignoreSysex=false");
        assertArrayEquals(msg, rec.received.get(0));
    }

    // ── channel messages always pass ──────────────────────────────────────────

    @Test
    void channel_messages_always_pass_regardless_of_ignoreSysex() throws Exception
    {
        byte[] noteOn = { (byte) 0x90, 60, 100 };
        byte[] cc7 = { (byte) 0xB0, 7, 127 };

        for (boolean ignore : new boolean[] { true, false })
        {
            var rec = new RecordingProcessor();
            var filter = new SysexFilter(rec, ignore);
            filter.sendMessage(noteOn);
            filter.sendMessage(cc7);
            assertEquals(2, rec.received.size(), "channel messages must pass for ignoreSysex=" + ignore);
        }
    }

    // ── null/empty edge cases ─────────────────────────────────────────────────

    @Test
    void null_forwarded_when_ignoreSysex_true() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, true);

        filter.sendMessage(null);

        assertEquals(1, rec.received.size());
        assertNull(rec.received.get(0));
    }

    @Test
    void empty_array_forwarded() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, true);

        filter.sendMessage(new byte[0]);

        assertEquals(1, rec.received.size());
    }

    // ── runtime toggle ────────────────────────────────────────────────────────

    @Test
    void setIgnoreSysex_toggle_takes_effect_immediately() throws Exception
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, false);

        filter.sendMessage(sysex((byte) 0x41)); // passes (ignoreSysex=false)
        assertEquals(1, rec.received.size());

        filter.setIgnoreSysex(true);
        filter.sendMessage(sysex((byte) 0x41)); // dropped (ignoreSysex=true)
        assertEquals(1, rec.received.size(), "second SysEx must be dropped after toggle");

        filter.setIgnoreSysex(false);
        filter.sendMessage(sysex((byte) 0x41)); // passes again
        assertEquals(2, rec.received.size());
    }

    @Test
    void isIgnoreSysex_reflects_current_state()
    {
        var rec = new RecordingProcessor();
        var filter = new SysexFilter(rec, true);
        assertTrue(filter.isIgnoreSysex());

        filter.setIgnoreSysex(false);
        assertFalse(filter.isIgnoreSysex());
    }
}
