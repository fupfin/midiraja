/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MidiPlaybackEngineTempoTest
{
    // --- extractTempoMspqn ---

    @Test
    void valid_tempo_120_bpm_returns_500000()
    {
        // 120 BPM = 500000 µs/qn = 0x07A120
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, 0x07, (byte) 0xA1, 0x20 };
        assertEquals(500000, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void valid_tempo_60_bpm_returns_1000000()
    {
        // 60 BPM = 1000000 µs/qn = 0x0F4240
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, 0x0F, 0x42, 0x40 };
        assertEquals(1000000, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void valid_tempo_240_bpm_returns_250000()
    {
        // 240 BPM = 250000 µs/qn = 0x03D090
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, 0x03, (byte) 0xD0, (byte) 0x90 };
        assertEquals(250000, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void not_a_meta_event_returns_minus_one()
    {
        // First byte is 0x90 (Note On), not 0xFF
        byte[] msg = { (byte) 0x90, 0x51, 0x03, 0x07, (byte) 0xA1, 0x20 };
        assertEquals(-1, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void wrong_meta_type_returns_minus_one()
    {
        // 0x52 instead of 0x51
        byte[] msg = { (byte) 0xFF, 0x52, 0x03, 0x07, (byte) 0xA1, 0x20 };
        assertEquals(-1, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void array_too_short_returns_minus_one()
    {
        // Only 5 bytes — needs at least 6
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, 0x07, (byte) 0xA1 };
        assertEquals(-1, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void mspqn_zero_returns_minus_one()
    {
        // Last 3 bytes all zero → mspqn == 0
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, 0x00, 0x00, 0x00 };
        assertEquals(-1, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void maximum_tempo_value_returns_16777215()
    {
        // 0x00FFFFFF = 16777215
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        assertEquals(16777215, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void array_exactly_6_bytes_is_minimum_valid_length()
    {
        // 100 BPM = 600000 µs/qn = 0x0927C0
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, 0x09, 0x27, (byte) 0xC0 };
        assertEquals(600000, MidiPlaybackEngine.extractTempoMspqn(msg));
    }

    @Test
    void array_longer_than_6_bytes_still_reads_correctly()
    {
        // Extra trailing bytes should be ignored; 120 BPM = 500000
        byte[] msg = { (byte) 0xFF, 0x51, 0x03, 0x07, (byte) 0xA1, 0x20, 0x00, 0x00 };
        assertEquals(500000, MidiPlaybackEngine.extractTempoMspqn(msg));
    }
}
