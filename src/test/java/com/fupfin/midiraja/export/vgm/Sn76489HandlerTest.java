/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class Sn76489HandlerTest
{
    private static final List<ChipType> CHIPS = List.of(ChipType.YM2612, ChipType.SN76489);

    // ── slotCount / percussionPriority ────────────────────────────────────────

    @Test
    void slotCount_returnsThree()
    {
        assertEquals(3, new Sn76489Handler().slotCount());
    }

    @Test
    void percussionPriority_returnsOne()
    {
        assertEquals(1, new Sn76489Handler().percussionPriority());
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_silencesAllThreeToneChannelsPlusNoise() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Sn76489Handler().initSilence(w);
        }
        byte[] vgm = out.toByteArray();
        List<Integer> psgBytes = psgWrites(vgm);

        // Each silence = one volume command (0x90 | ch<<5 | 0x0F)
        // ch0: 0x9F, ch1: 0xBF, ch2: 0xDF, ch3 (noise): 0xFF
        assertTrue(psgBytes.contains(0x9F), "ch0 must be silenced");
        assertTrue(psgBytes.contains(0xBF), "ch1 must be silenced");
        assertTrue(psgBytes.contains(0xDF), "ch2 must be silenced");
        assertTrue(psgBytes.contains(0xFF), "noise channel must be silenced");
    }

    // ── startNote ─────────────────────────────────────────────────────────────

    @Test
    void startNote_producesThreePsgBytes() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Sn76489Handler().startNote(0, 69, 100, 0, w); // A4, ch 0
        }
        byte[] vgm = out.toByteArray();
        List<Integer> psgBytes = psgWrites(vgm);

        // tone freq (latch + data byte) + volume byte = 3 PSG bytes
        assertEquals(3, psgBytes.size(), "startNote must emit latch byte + data byte + volume byte");
    }

    @Test
    void startNote_periodCalculation_a4() throws Exception
    {
        // A4 = 440 Hz; period = round(3_579_545 / (32 * 440)) = round(254.25) = 254
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Sn76489Handler().startNote(0, 69, 127, 0, w);
        }
        byte[] vgm = out.toByteArray();
        List<Integer> psgBytes = psgWrites(vgm);

        // Latch byte: 0x80 | (0 << 5) | (254 & 0x0F) = 0x80 | 0x0E = 0x8E
        // Data byte:  (254 >> 4) & 0x3F = 15 = 0x0F
        assertEquals(0x8E, psgBytes.get(0), "A4 latch byte for ch0 must be 0x8E");
        assertEquals(0x0F, psgBytes.get(1), "A4 data byte must be 0x0F");
    }

    @Test
    void startNote_maxVelocity_producesZeroAttenuation() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Sn76489Handler().startNote(0, 69, 127, 0, w);
        }
        byte[] vgm = out.toByteArray();
        List<Integer> psgBytes = psgWrites(vgm);

        // volume cmd = 0x90 | (ch<<5) | atten; velocity 127 → atten = 0
        int volByte = psgBytes.get(2);
        assertEquals(0x00, volByte & 0x0F, "Max velocity must produce attenuation 0");
    }

    // ── silenceSlot ───────────────────────────────────────────────────────────

    @Test
    void silenceSlot_writesMaxAttenuation() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Sn76489Handler().silenceSlot(1, w); // ch 1
        }
        byte[] vgm = out.toByteArray();
        List<Integer> psgBytes = psgWrites(vgm);

        assertEquals(1, psgBytes.size());
        // 0x90 | (1 << 5) | 0x0F = 0xBF
        assertEquals(0xBF, psgBytes.get(0), "Silence for ch1 must write 0xBF");
    }

    // ── handlePercussion ──────────────────────────────────────────────────────

    @Test
    void handlePercussion_bass_drum_producesWhiteNoise() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Sn76489Handler().handlePercussion(36, 100, w); // GM bass drum
        }
        byte[] vgm = out.toByteArray();
        List<Integer> psgBytes = psgWrites(vgm);

        assertFalse(psgBytes.isEmpty());
        // noise mode: white noise = 0xE7
        assertTrue(psgBytes.contains(0xE7), "Bass drum must use white noise (0xE7)");
    }

    @Test
    void handlePercussion_velocity0_silencesNoiseChannel() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Sn76489Handler().handlePercussion(36, 0, w);
        }
        byte[] vgm = out.toByteArray();
        List<Integer> psgBytes = psgWrites(vgm);

        // silence = noise volume cmd with max attenuation = 0xFF
        assertTrue(psgBytes.contains(0xFF), "velocity 0 must silence noise channel");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Returns data bytes from PSG commands (0x50) in the VGM body (after v1.61 header). */
    private static List<Integer> psgWrites(byte[] vgm)
    {
        var result = new ArrayList<Integer>();
        int pos = 0x80; // v1.61 header size
        while (pos < vgm.length)
        {
            int cmd = vgm[pos] & 0xFF;
            if (cmd == 0x50 && pos + 1 < vgm.length)
            {
                result.add(vgm[pos + 1] & 0xFF);
                pos += 2;
            }
            else if (cmd == 0x66)
                break;
            else if (cmd == 0x61)
                pos += 3;
            else if ((cmd == 0x52 || cmd == 0x53) && pos + 2 < vgm.length)
                pos += 3;
            else
                pos += 1;
        }
        return result;
    }
}
