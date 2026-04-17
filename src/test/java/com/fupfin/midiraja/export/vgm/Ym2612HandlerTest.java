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

class Ym2612HandlerTest
{
    private static final List<ChipType> CHIPS = List.of(ChipType.YM2612, ChipType.SN76489);

    // ── slotCount / percussionPriority ───────────────────────────────────────

    @Test
    void slotCount_returnsFive()
    {
        assertEquals(5, new Ym2612Handler().slotCount());
    }

    @Test
    void percussionPriority_returnsTwo()
    {
        assertEquals(2, new Ym2612Handler().percussionPriority());
    }

    // ── initSilence ───────────────────────────────────────────────────────────

    @Test
    void initSilence_writesKeyOffForAllSixChannels() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().initSilence(w);
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);

        // Key-off writes are at port 0, reg 0x28, value = ch_addr without slot mask
        // ch_addr values: 0, 1, 2 (port 0) and 4, 5, 6 (port 1)
        long keyOffCount = writes.stream()
                .filter(w -> w[0] == 0 && w[1] == 0x28 && (w[2] & 0xF0) == 0)
                .count();
        assertEquals(6, keyOffCount, "initSilence must emit key-off for all 6 channels");
    }

    // ── startNote ─────────────────────────────────────────────────────────────

    @Test
    void startNote_producesNonEmptyOutput() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().startNote(0, 69, 100, 0, w); // A4, program 0
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);
        assertFalse(writes.isEmpty(), "startNote must emit YM2612 register writes");
    }

    @Test
    void startNote_setsKeyOn() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().startNote(0, 69, 100, 0, w);
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);

        boolean hasKeyOn = writes.stream()
                .anyMatch(w -> w[0] == 0 && w[1] == 0x28 && (w[2] & 0xF0) != 0);
        assertTrue(hasKeyOn, "startNote must emit a key-on command (reg 0x28 with slot bits set)");
    }

    @Test
    void startNote_writesFrequencyRegisters() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().startNote(0, 69, 100, 0, w); // A4 = 440 Hz
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);

        boolean hasA0 = writes.stream().anyMatch(w -> w[1] == 0xA0);
        boolean hasA4 = writes.stream().anyMatch(w -> w[1] == 0xA4);
        assertTrue(hasA0 && hasA4, "startNote must write frequency (0xA0) and block (0xA4) registers");
    }

    @Test
    void startNote_port1Channel_usesPort1Commands() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().startNote(3, 69, 100, 0, w); // slot 3 → port 1
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);

        boolean hasPort1 = writes.stream().anyMatch(w -> w[0] == 1);
        assertTrue(hasPort1, "Slot 3+ must produce port-1 VGM commands");
    }

    // ── silenceSlot ───────────────────────────────────────────────────────────

    @Test
    void silenceSlot_writesKeyOff() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().silenceSlot(0, w);
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);

        boolean hasKeyOff = writes.stream()
                .anyMatch(w -> w[0] == 0 && w[1] == 0x28 && (w[2] & 0xF0) == 0);
        assertTrue(hasKeyOff, "silenceSlot must emit key-off (reg 0x28, no slot bits)");
    }

    // ── handlePercussion ─────────────────────────────────────────────────────

    @Test
    void handlePercussion_noteOn_writesKeyOn() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().handlePercussion(38, 100, w); // GM snare
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);

        boolean hasKeyOn = writes.stream()
                .anyMatch(w -> w[0] == 0 && w[1] == 0x28 && (w[2] & 0xF0) != 0);
        assertTrue(hasKeyOn, "handlePercussion must emit a key-on command");
    }

    @Test
    void handlePercussion_noteOff_writesKeyOff() throws Exception
    {
        var out = new ByteArrayOutputStream();
        try (var w = new VgmWriter(out, CHIPS))
        {
            new Ym2612Handler().handlePercussion(38, 0, w); // velocity 0 = note-off
        }
        byte[] vgm = out.toByteArray();
        List<int[]> writes = ym2612Writes(vgm);

        boolean hasKeyOff = writes.stream()
                .anyMatch(w -> w[0] == 0 && w[1] == 0x28 && (w[2] & 0xF0) == 0);
        assertTrue(hasKeyOff, "handlePercussion with velocity 0 must emit key-off");
    }

    // ── isCarrier / scaleTl ────────────────────────────────────────────────────

    @Test
    void isCarrier_alg0_onlyOp3IsCarrier()
    {
        assertFalse(Ym2612Handler.isCarrier(0, 0));
        assertFalse(Ym2612Handler.isCarrier(0, 1));
        assertFalse(Ym2612Handler.isCarrier(0, 2));
        assertTrue(Ym2612Handler.isCarrier(0, 3));
    }

    @Test
    void isCarrier_alg7_allOpsAreCarriers()
    {
        for (int i = 0; i < 4; i++)
            assertTrue(Ym2612Handler.isCarrier(7, i), "alg 7: op " + i + " should be carrier");
    }

    @Test
    void isCarrier_alg4_ops2and3AreCarriers()
    {
        assertFalse(Ym2612Handler.isCarrier(4, 0));
        assertFalse(Ym2612Handler.isCarrier(4, 1)); // S3 is modulator in alg 4
        assertTrue(Ym2612Handler.isCarrier(4, 2));  // S2 is carrier in alg 4
        assertTrue(Ym2612Handler.isCarrier(4, 3));  // S4 is carrier in alg 4
    }

    @Test
    void scaleTl_maxVelocity_returnsNearPatchTl()
    {
        // Velocity 127 → volume ≈ 127 on log curve → TL ≈ patch TL (within ±1)
        int result = Ym2612Handler.scaleTl(64, 127);
        assertTrue(result >= 63 && result <= 65,
                "Velocity 127 should return TL close to patch TL; got " + result);
    }

    @Test
    void scaleTl_zeroVelocity_maximisesAttenuation()
    {
        // Velocity 0 → volume = 0 → tl = 127 (maximum attenuation)
        assertEquals(127, Ym2612Handler.scaleTl(64, 0));
    }

    @Test
    void scaleTl_patchTlZero_maxVelocity_returnsNearZero()
    {
        // tl_patch = 0, velocity = 127 → should return ~0 (full output)
        int result = Ym2612Handler.scaleTl(0, 127);
        assertTrue(result <= 1, "tl_patch=0 at max velocity should stay near 0; got " + result);
    }

    @Test
    void scaleTl_anyPatchTl_zeroVelocity_returns127()
    {
        assertEquals(127, Ym2612Handler.scaleTl(127, 0));
    }

    // ── Helper: extract YM2612 writes from VGM bytes ──────────────────────────

    /** Returns [port, reg, data] tuples for YM2612 commands (0x52/0x53) in the VGM body. */
    private static List<int[]> ym2612Writes(byte[] vgm)
    {
        var result = new ArrayList<int[]>();
        // v1.61 header is 0x80 bytes; YM2612+SN76489 is v1.61
        int pos = 0x80;
        while (pos < vgm.length - 1)
        {
            int cmd = vgm[pos] & 0xFF;
            if ((cmd == 0x52 || cmd == 0x53) && pos + 2 < vgm.length)
            {
                int port = cmd == 0x52 ? 0 : 1;
                result.add(new int[] { port, vgm[pos + 1] & 0xFF, vgm[pos + 2] & 0xFF });
                pos += 3;
            }
            else if (cmd == 0x66)
                break;
            else if (cmd == 0x61)
                pos += 3;
            else if (cmd == 0x50)
                pos += 2;
            else
                pos += 1;
        }
        return result;
    }
}
