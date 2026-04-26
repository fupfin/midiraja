/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for YM2608 (OPNA) — 6 melodic FM channels + ADPCM-A native percussion.
 *
 * <p>
 * YM2608 uses the same OPN 4-operator FM engine as YM2612. Channels 0-2 are on port 0 and
 * channels 3-5 are on port 1. VGM commands are 0x56 (port 0) and 0x57 (port 1), and the
 * clock is 7,987,200 Hz (PC-98 standard). The SSG section (AY-3-8910 compatible, 3 tone +
 * noise, registers 0x00-0x0F via port 0) is handled by an embedded {@link Ay8910Handler}.
 *
 * <p>
 * Percussion uses the ADPCM-A rhythm section: a 6-channel playback unit backed by an 8 KB
 * internal ROM ({@code ym2608_adpcm_a.bin}) with fixed samples for Bass Drum, Snare, Top
 * Cymbal, Hi-Hat, Tom Tom, and Rim Shot. The ROM data block is embedded in the VGM stream
 * during {@link #initSilence} so that players can load it into the emulated chip.
 *
 * <p>
 * VGM register mapping for ADPCM-A (all written via port 0, 0x56):
 * <ul>
 *   <li>0x10 — key on/off (bit 7=0: key-on channel mask bits 5-0; 0xBF = all off)
 *   <li>0x11 — master total level (0x3F = max volume)
 *   <li>0x18+ch — per-channel L/R flags (bits 7-6) + individual level (bits 4-0)
 *   <li>0x20+ch — per-channel start address low (ROM byte addr >> 8)
 *   <li>0x28+ch — per-channel start address high (ROM byte addr >> 16; always 0)
 *   <li>0x30+ch — per-channel end address low
 *   <li>0x38+ch — per-channel end address high (always 0)
 * </ul>
 */
final class Ym2608Handler extends AbstractOpnHandler
{
    private static final int SLOTS = 6;
    private final Ay8910Handler ssg = Ay8910Handler.forYm2608Ssg();

    /**
     * Per-channel ADPCM-A ROM address table: {startLow, startHigh, endLow, endHigh}.
     * Values are ROM byte address >> 8 (per ADPCMA_ADDRESS_SHIFT = 8 in the chip).
     * Address pairs from YM2608_ADPCM_ROM_addr[] in fmopn.c.
     */
    private static final int[][] ADPCM_A_ADDRS = {
        { 0x00, 0x00, 0x01, 0x00 }, // ch0 Bass Drum:  0x0000-0x01BF
        { 0x01, 0x00, 0x04, 0x00 }, // ch1 Snare:      0x01C0-0x043F
        { 0x04, 0x00, 0x1B, 0x00 }, // ch2 Top Cymbal: 0x0440-0x1B7F
        { 0x1B, 0x00, 0x1C, 0x00 }, // ch3 High Hat:   0x1B80-0x1CFF
        { 0x1D, 0x00, 0x1F, 0x00 }, // ch4 Tom Tom:    0x1D00-0x1F7F
        { 0x1F, 0x00, 0x1F, 0x00 }, // ch5 Rim Shot:   0x1F80-0x1FFF
    };

    /** Maps GM percussion note (0-127) to ADPCM-A channel (0-5), or -1 if unmapped. */
    private static final int[] GM_NOTE_TO_ADPCM_CH = AdpcmARhythmSupport.buildNoteMap();

    Ym2608Handler()
    {
        super(SLOTS);
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.YM2608;
    }

    @Override
    public int slotCount()
    {
        return SLOTS + ssg.slotCount(); // 6 FM + 2 SSG melodic channels
    }

    @Override
    public int percussionPriority()
    {
        return 3; // ADPCM-A native rhythm section
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // YM2608 ADPCM-A ROM is hardcoded inside libvgm (fmopn_2608rom.h); no data block needed.
        var cfg = new AdpcmARhythmSupport.InitConfig(0, 0x10, 0x11, 0x20, 0x28, 0x30, 0x38,
                ADPCM_A_ADDRS);
        AdpcmARhythmSupport.initAdpcmA(w::writeOpna, cfg);

        // FM section init
        // Set LFO frequency as specified by the bank (bit 3 = enable, bits 2-0 = frequency)
        w.writeOpna(0, 0x22, wopnBank().lfoFreq());
        // Channel 3 normal mode (disable CSM / 3-slot special mode)
        w.writeOpna(0, 0x27, 0x00);
        // Key-off all 6 FM channels (ch_addr: ch 0-2 → 0-2, ch 3-5 → 4-6)
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeOpna(0, 0x28, chAddr(slot));
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
            w.writeOpna(0, 0x28, chAddr(slot));
        ssg.finalSilence(w);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        AdpcmARhythmSupport.triggerPercussion(w::writeOpna, 0, note, velocity,
                GM_NOTE_TO_ADPCM_CH, 0x18, 0x10);
    }

    @Override
    void writeFm(int port, int reg, int data, VgmWriter w)
    {
        w.writeOpna(port, reg, data);
    }

    @Override
    int fmClock()
    {
        return VgmWriter.YM2608_CLOCK;
    }

    @Override
    int chAddr(int slot)
    {
        return slot < 3 ? slot : slot - 3 + 4;
    }

    @Override
    int portOf(int slot)
    {
        return slot < 3 ? 0 : 1;
    }

    @Override
    int chOf(int slot)
    {
        return slot < 3 ? slot : slot - 3;
    }
}
