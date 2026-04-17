/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * {@link ChipHandler} for YM2610 (OPNB) — 4 melodic FM channels + ADPCM-A native percussion.
 *
 * <p>
 * YM2610 uses the same OPN 4-operator FM engine as YM2612. The chip has 4 FM channels:
 * slots 0-1 → port 0 channels 1-2, slots 2-3 → port 1 channels 1-2 (channel 0 on each port
 * is reserved for ADPCM-B/DeltaT in the real hardware). VGM commands are 0x58 (port 0) and
 * 0x59 (port 1), and the clock is 8,000,000 Hz (Neo Geo standard). The SSG section
 * (AY-3-8910 compatible, 3 tone + noise, registers 0x00-0x0F via port 0) is handled by an
 * embedded {@link Ay8910Handler}.
 *
 * <p>
 * Percussion uses the ADPCM-A rhythm section: a 6-channel playback unit backed by an external
 * ROM ({@code ym2610_adpcm_a.bin} from the classpath) with fixed samples for Bass Drum, Snare,
 * Top Cymbal, Hi-Hat, Tom Tom, and Rim Shot. The ROM data block is embedded in the VGM stream
 * during {@link #initSilence} so that players can load it into the emulated chip.
 *
 * <p>
 * VGM register mapping for ADPCM-A (all written via port 1, 0x59):
 * <ul>
 *   <li>0x00 — key on/off (bit 7=0: key-on channel mask bits 5-0; 0xBF = all off)
 *   <li>0x01 — master total level (0x3F = max volume)
 *   <li>0x08+ch — per-channel L/R flags (bits 7-6) + individual level (bits 4-0)
 *   <li>0x10+ch — per-channel start address low (ROM byte addr >> 8)
 *   <li>0x18+ch — per-channel start address high (ROM byte addr >> 16; always 0)
 *   <li>0x20+ch — per-channel end address low
 *   <li>0x28+ch — per-channel end address high (always 0)
 * </ul>
 */
final class Ym2610Handler extends AbstractOpnHandler
{
    private static final int SLOTS = 4; // YM2610 has 4 FM channels (YM2610B has 6)
    private final Ay8910Handler ssg = Ay8910Handler.forYm2610Ssg();

    private static final byte[] ADPCM_A_ROM = loadAdpcmRom();

    /**
     * Per-channel ADPCM-A ROM address table: {startLow, startHigh, endLow, endHigh}.
     * Values are ROM byte address >> 8 (per ADPCMA_ADDRESS_SHIFT = 8 in the chip).
     * Address pairs from YM2610_ADPCM_ROM_addr[] in fmopn.c.
     */
    private static final int[][] ADPCM_A_ADDRS = {
        { 0x00, 0x00, 0x01, 0x00 }, // ch0 Bass Drum:  packed at 0x0000-0x01BF (256-byte aligned)
        { 0x02, 0x00, 0x04, 0x00 }, // ch1 Snare:      packed at 0x0200-0x047F
        { 0x05, 0x00, 0x1C, 0x00 }, // ch2 Top Cymbal: packed at 0x0500-0x1C3F
        { 0x1D, 0x00, 0x1E, 0x00 }, // ch3 High Hat:   packed at 0x1D00-0x1E7F
        { 0x1F, 0x00, 0x21, 0x00 }, // ch4 Tom Tom:    packed at 0x1F00-0x217F
        { 0x22, 0x00, 0x22, 0x00 }, // ch5 Rim Shot:   packed at 0x2200-0x227F
    };

    /** Maps GM percussion note (0-127) to ADPCM-A channel (0-5), or -1 if unmapped. */
    private static final int[] GM_NOTE_TO_ADPCM_CH = buildNoteMap();

    private static int[] buildNoteMap()
    {
        int[] m = new int[128];
        Arrays.fill(m, -1);
        // ch0 Bass Drum
        for (int n : new int[] { 35, 36 })
            m[n] = 0;
        // ch1 Snare
        for (int n : new int[] { 38, 40 })
            m[n] = 1;
        // ch2 Top Cymbal (crashes, rides, splash, open HH variant)
        for (int n : new int[] { 49, 51, 52, 53, 55, 57, 59 })
            m[n] = 2;
        // ch3 High Hat
        for (int n : new int[] { 42, 44, 46 })
            m[n] = 3;
        // ch4 Tom Tom
        for (int n : new int[] { 41, 43, 45, 47, 48, 50 })
            m[n] = 4;
        // ch5 Rim Shot / Side Stick / Hand Clap
        for (int n : new int[] { 37, 39 })
            m[n] = 5;
        return m;
    }

    private static byte[] loadAdpcmRom()
    {
        try (var in = Ym2610Handler.class.getResourceAsStream("ym2610_adpcm_a.bin"))
        {
            if (in == null)
                throw new IOException("ym2610_adpcm_a.bin not found in classpath");
            return in.readAllBytes();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    Ym2610Handler()
    {
        super(SLOTS);
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.YM2610;
    }

    @Override
    public int slotCount()
    {
        return SLOTS + ssg.slotCount(); // 4 FM + 2 SSG melodic channels
    }

    @Override
    public int percussionPriority()
    {
        return 3; // ADPCM-A native rhythm section
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Embed the ADPCM-A ROM data block — must precede any ADPCM-A register writes
        // Type 0x82 = YM2610 ADPCM-A ROM (type 0x83 would be YM2610 ADPCM-B/DeltaT)
        w.writeRomDataBlock(0x82, ADPCM_A_ROM.length, 0, ADPCM_A_ROM);

        // ADPCM-A registers are on port 1 (VGM cmd 0x59), addresses 0x00-0x2F.
        // Port 0 addresses 0x10-0x1C are DeltaT (ADPCM-B) — do not use port 0 for ADPCM-A.

        // All ADPCM-A channels off (bit 7=1 = key-off mode, bits 5-0 = channel mask 0x3F)
        w.writeYm2610(1, 0x00, 0xBF);
        // Master total level = max volume (0x3F; chip internally inverts this)
        w.writeYm2610(1, 0x01, 0x3F);
        // Per-channel start/end addresses
        for (int c = 0; c < 6; c++)
        {
            int[] a = ADPCM_A_ADDRS[c];
            w.writeYm2610(1, 0x10 + c, a[0]); // start address low
            w.writeYm2610(1, 0x18 + c, a[1]); // start address high
            w.writeYm2610(1, 0x20 + c, a[2]); // end address low
            w.writeYm2610(1, 0x28 + c, a[3]); // end address high
        }

        // FM section init
        // Set LFO frequency as specified by the bank (bit 3 = enable, bits 2-0 = frequency)
        w.writeYm2610(0, 0x22, wopnBank().lfoFreq());
        // Channel 3 normal mode (disable CSM / 3-slot special mode)
        w.writeYm2610(0, 0x27, 0x00);
        // Key-off all FM channels
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeYm2610(0, 0x28, chAddr(slot));
        // SSG section init (registers 0x00-0x0F via port 0)
        ssg.initSilence(w);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.startNote(localSlot - SLOTS, note, velocity, program, w);
            return;
        }
        super.startNote(localSlot, note, velocity, program, w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.silenceSlot(localSlot - SLOTS, w);
            return;
        }
        super.silenceSlot(localSlot, w);
    }

    @Override
    public void updatePitch(int localSlot, int note, int pitchBend, int bendRangeSemitones,
            VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.updatePitch(localSlot - SLOTS, note, pitchBend, bendRangeSemitones, w);
            return;
        }
        super.updatePitch(localSlot, note, pitchBend, bendRangeSemitones, w);
    }

    @Override
    public void updateVolume(int localSlot, int velocity, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.updateVolume(localSlot - SLOTS, velocity, w);
            return;
        }
        super.updateVolume(localSlot, velocity, w);
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeYm2610(0, 0x28, chAddr(slot));
        ssg.finalSilence(w);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
            return;
        int ch = note < 128 ? GM_NOTE_TO_ADPCM_CH[note] : -1;
        if (ch < 0)
            return;
        // Per-channel: L+R enabled (bits 7-6), individual level scaled from velocity
        int level = Math.max(1, (velocity * 31) / 127); // 1-31
        w.writeYm2610(1, 0x08 + ch, 0xC0 | level); // ADPCM-A per-ch level on port 1
        // Key-on: bit 7 = 0 (key-on mode), set the channel's bit
        w.writeYm2610(1, 0x00, 1 << ch); // ADPCM-A key-on on port 1
    }

    @Override
    void writeFm(int port, int reg, int data, VgmWriter w)
    {
        w.writeYm2610(port, reg, data);
    }

    @Override
    int fmClock()
    {
        return VgmWriter.YM2610_CLOCK;
    }

    @Override
    int chAddr(int slot)
    {
        return slot < 2 ? slot + 1 : slot + 3; // → 1, 2, 5, 6
    }

    @Override
    int portOf(int slot)
    {
        return slot < 2 ? 0 : 1;
    }

    @Override
    int chOf(int slot)
    {
        return (slot % 2) + 1; // 0→1, 1→2, 2→1, 3→2
    }
}
