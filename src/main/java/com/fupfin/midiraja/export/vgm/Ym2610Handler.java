/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.IOException;
import java.io.UncheckedIOException;

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
        { 0x00, 0x00, 0x09, 0x00 }, // ch0 Bass Drum:  0x0000-0x09FF
        { 0x0A, 0x00, 0x13, 0x00 }, // ch1 Snare:      0x0A00-0x13FF
        { 0x14, 0x00, 0x41, 0x00 }, // ch2 Top Cymbal: 0x1400-0x41FF
        { 0x42, 0x00, 0x48, 0x00 }, // ch3 Hi-Hat:     0x4200-0x48FF
        { 0x49, 0x00, 0x55, 0x00 }, // ch4 Tom Tom:    0x4900-0x55FF
        { 0x56, 0x00, 0x5D, 0x00 }, // ch5 Rim Shot:   0x5600-0x5DFF
    };

    /** Maps GM percussion note (0-127) to ADPCM-A channel (0-5), or -1 if unmapped. */
    private static final int[] GM_NOTE_TO_ADPCM_CH = AdpcmARhythmSupport.buildNoteMap();

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
        var cfg = new AdpcmARhythmSupport.InitConfig(1, 0x00, 0x01, 0x10, 0x18, 0x20, 0x28,
                ADPCM_A_ADDRS);
        AdpcmARhythmSupport.initAdpcmAWithRom(w::writeRomDataBlock, 0x82, ADPCM_A_ROM,
                w::writeYm2610, cfg);

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
        AdpcmARhythmSupport.triggerPercussion(w::writeYm2610, 1, note, velocity,
                GM_NOTE_TO_ADPCM_CH, 0x08, 0x00);
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
