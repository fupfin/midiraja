/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;

class StandardPlaybackPipelineTest
{
    // --- Minimal stub that records all calls ---

    static class RecordingProvider implements MidiOutProvider
    {
        final List<byte[]> sent = new ArrayList<>();
        int panicCount = 0;

        @Override
        public List<MidiPort> getOutputPorts()
        {
            return List.of();
        }

        @Override
        public void openPort(int portIndex)
        {
        }

        @Override
        public void sendMessage(byte[] data) throws Exception
        {
            sent.add(data.clone());
        }

        @Override
        public void closePort()
        {
        }

        @Override
        public void panic()
        {
            panicCount++;
        }
    }

    static class ThrowingProvider extends RecordingProvider
    {
        @Override
        public void sendMessage(byte[] data) throws Exception
        {
            throw new Exception("simulated send failure");
        }
    }

    private RecordingProvider provider;
    private StandardPlaybackPipeline pipeline;

    @BeforeEach
    void setUp()
    {
        provider = new RecordingProvider();
        // 100% initial volume, 0 semitone transpose
        pipeline = new StandardPlaybackPipeline(provider, 100, 0);
    }

    // --- Initial state ---

    @Test
    void initial_volume_scale_is_one()
    {
        assertEquals(1.0, pipeline.getVolumeScale(), 1e-9);
    }

    @Test
    void initial_transpose_is_zero()
    {
        assertEquals(0, pipeline.getCurrentTranspose());
    }

    @Test
    void initial_volume_percent_50_sets_volume_scale_to_half() throws Exception
    {
        var p = new StandardPlaybackPipeline(provider, 50, 0);
        assertEquals(0.5, p.getVolumeScale(), 1e-9);
    }

    // --- sendMessage forwards bytes to provider ---

    @Test
    void send_message_forwards_note_on_to_provider() throws Exception
    {
        byte[] noteOn = { (byte) 0x90, 60, 80 };
        pipeline.sendMessage(noteOn);

        assertEquals(1, provider.sent.size());
        assertArrayEquals(noteOn, provider.sent.get(0));
    }

    @Test
    void send_message_forwards_control_change_to_provider() throws Exception
    {
        byte[] cc = { (byte) 0xB0, 10, 64 };
        pipeline.sendMessage(cc);

        assertEquals(1, provider.sent.size());
        assertArrayEquals(cc, provider.sent.get(0));
    }

    // --- adjustVolume ---

    @Test
    void adjust_volume_positive_delta_increases_volume_scale()
    {
        // Initial scale from 50% = 0.5; add 0.2 → 0.7
        var p = new StandardPlaybackPipeline(provider, 50, 0);
        p.adjustVolume(0.2);
        assertEquals(0.7, p.getVolumeScale(), 1e-9);
    }

    @Test
    void adjust_volume_negative_delta_decreases_volume_scale()
    {
        // Initial 1.0; subtract 0.3 → 0.7
        pipeline.adjustVolume(-0.3);
        assertEquals(0.7, pipeline.getVolumeScale(), 1e-9);
    }

    @Test
    void adjust_volume_clamps_at_zero_lower_bound()
    {
        pipeline.adjustVolume(-999.0);
        assertEquals(0.0, pipeline.getVolumeScale(), 1e-9);
    }

    @Test
    void adjust_volume_clamps_at_one_upper_bound()
    {
        // Without outputGain, volumeFilter caps at 1.0
        pipeline.adjustVolume(999.0);
        assertEquals(1.0, pipeline.getVolumeScale(), 1e-9);
    }

    // --- adjustTranspose ---

    @Test
    void adjust_transpose_positive_updates_current_transpose()
    {
        pipeline.adjustTranspose(3);
        assertEquals(3, pipeline.getCurrentTranspose());
    }

    @Test
    void adjust_transpose_negative_updates_current_transpose()
    {
        pipeline.adjustTranspose(-2);
        assertEquals(-2, pipeline.getCurrentTranspose());
    }

    @Test
    void adjust_transpose_calls_provider_panic()
    {
        pipeline.adjustTranspose(1);
        assertEquals(1, provider.panicCount);
    }

    @Test
    void adjust_transpose_accumulates_across_calls()
    {
        pipeline.adjustTranspose(3);
        pipeline.adjustTranspose(-1);
        assertEquals(2, pipeline.getCurrentTranspose());
    }

    @Test
    void transpose_shifts_note_on_pitch() throws Exception
    {
        pipeline.adjustTranspose(2);

        byte[] noteOn = { (byte) 0x90, 60, 80 }; // Middle C, channel 1
        pipeline.sendMessage(noteOn);

        // Expect note 62 (C+2 semitones), velocity unchanged
        boolean found = provider.sent.stream()
                .anyMatch(m -> (m[0] & 0xFF) == 0x90 && (m[1] & 0xFF) == 62 && (m[2] & 0xFF) == 80);
        assertTrue(found, "Transposed Note On (62) should have been sent");
    }

    // --- Sysex filtering ---

    @Test
    void sysex_passes_through_when_not_ignoring() throws Exception
    {
        byte[] sysex = { (byte) 0xF0, 0x41, 0x10, (byte) 0xF7 };
        pipeline.setIgnoreSysex(false); // false = pass sysex through
        pipeline.sendMessage(sysex);

        boolean sysexSent = provider.sent.stream()
                .anyMatch(m -> (m[0] & 0xFF) == 0xF0);
        assertTrue(sysexSent, "SysEx should pass when setIgnoreSysex(false)");
    }

    @Test
    void sysex_is_dropped_when_ignoring() throws Exception
    {
        byte[] sysex = { (byte) 0xF0, 0x41, 0x10, (byte) 0xF7 };
        pipeline.setIgnoreSysex(true); // true = drop sysex
        pipeline.sendMessage(sysex);

        boolean sysexSent = provider.sent.stream()
                .anyMatch(m -> (m[0] & 0xFF) == 0xF0);
        assertFalse(sysexSent, "SysEx should be dropped when setIgnoreSysex(true)");
    }

    @Test
    void non_sysex_passes_through_regardless_of_sysex_filter() throws Exception
    {
        byte[] noteOn = { (byte) 0x90, 60, 80 };
        pipeline.setIgnoreSysex(true);
        pipeline.sendMessage(noteOn);

        assertEquals(1, provider.sent.stream()
                .filter(m -> (m[0] & 0xFF) == 0x90)
                .count());
    }

    // --- Exception propagation ---

    @Test
    void send_message_propagates_exception_from_provider()
    {
        var throwing = new ThrowingProvider();
        var p = new StandardPlaybackPipeline(throwing, 100, 0);
        assertThrows(Exception.class, () -> p.sendMessage(new byte[] { (byte) 0x90, 60, 80 }));
    }
}
