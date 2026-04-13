/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import com.fupfin.midiraja.midi.psg.SccWaveforms;

/**
 * {@link ChipHandler} for K051649 (SCC) / K052539 (SCC-I) — 5 wavetable channels.
 *
 * <p>
 * All five channels are used for melody. Percussion is not supported: the SCC has no noise
 * generator and is intended to work alongside a PSG (AY8910) that handles rhythm.
 *
 * <p>
 * K051649 note: channels 3 and 4 share waveform RAM. Any write to waveform offset ≥ 0x60
 * updates both channels simultaneously. In non-plus mode the two channels will therefore
 * always share the same waveform; the last {@code startNote} call to either slot wins.
 *
 * <p>
 * K052539 (SCC-I, {@code plusMode=true}): all five channels have independent waveform RAM.
 * Activated by setting bit 31 of the K051649 clock field in the VGM header.
 *
 * <p>
 * SCC register map (VGM command 0xD2):
 * <ul>
 *   <li>port 0, reg 0x00-0x9F: waveform data (32 bytes × 5 channels)
 *   <li>port 1, reg 0-9: frequency dividers (lo/hi per channel; Fout = Fclock / (16 × (div+1)))
 *   <li>port 2, reg 0-4: volumes (bits 3-0)
 *   <li>port 3: channel enable mask (bits 4-0)
 * </ul>
 */
final class SccHandler implements ChipHandler
{
    private static final int SLOTS = 5;

    /** K051649 (SCC) only: channels 0-3 are written; ch4 inherits via shared waveram. */
    private static final int INIT_CHANNELS = 4;

    private final boolean plusMode; // true → K052539 (SCC-I), false → K051649 (SCC)

    private int enableMask = 0;

    /**
     * Last program written per slot; -1 means not yet written.
     * Used to avoid redundant waveform register writes on consecutive notes with the same program.
     */
    private final int[] slotProgram = new int[SLOTS];

    SccHandler()
    {
        this(false);
    }

    SccHandler(boolean plusMode)
    {
        this.plusMode = plusMode;
        java.util.Arrays.fill(slotProgram, -1);
    }

    @Override
    public ChipType chipType()
    {
        return plusMode ? ChipType.SCCI : ChipType.SCC;
    }

    @Override
    public int slotCount()
    {
        return SLOTS;
    }

    @Override
    public boolean supportsRhythm()
    {
        // SCC has no noise generator — percussion should be handled by an accompanying PSG (AY8910).
        return false;
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // K051649 (SCC): writing ch3 (offset 0x60-0x7F) also updates ch4 via shared waveram,
        // so only channels 0-3 need explicit writes.
        // K052539 (SCC-I): all five channels are independent — ch4 must be written explicitly.
        int initChannels = plusMode ? SLOTS : INIT_CHANNELS;
        for (int ch = 0; ch < initChannels; ch++)
        {
            int base = ch * 32;
            for (int i = 0; i < 32; i++)
                w.writeScc(0, base + i, SccWaveforms.SQUARE[i] & 0xFF);
        }
        // Zero all volumes (port 2, reg = channel index)
        for (int ch = 0; ch < SLOTS; ch++)
            w.writeScc(2, ch, 0);
        // Disable all channels (port 3)
        w.writeScc(3, 0, 0);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        // Waveform: port 0, regs slot*32 .. slot*32+31 — write only when program changes.
        if (slotProgram[localSlot] != program)
        {
            byte[] wave = SccWaveforms.forProgram(program);
            int base = localSlot * 32;
            for (int i = 0; i < 32; i++)
                w.writeScc(0, base + i, wave[i] & 0xFF);
            slotProgram[localSlot] = program;
        }

        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        // SCC frequency divider: Fout = Fclock / (16 * (divider + 1))
        int divider = (int) Math.round(VgmWriter.K051649_CLOCK / (16.0 * freq)) - 1;
        divider = Math.clamp(divider, 0, 0xFFF);

        // Frequency: port 1, reg = slot*2 (lo) and slot*2+1 (hi)
        w.writeScc(1, localSlot * 2, divider & 0xFF);
        w.writeScc(1, localSlot * 2 + 1, (divider >> 8) & 0x0F);

        // Volume: port 2, reg = slot index
        int vol = (int) Math.round(velocity * 15.0 / 127.0);
        w.writeScc(2, localSlot, vol & 0x0F);

        // Channel enable: port 3
        enableMask |= (1 << localSlot);
        w.writeScc(3, 0, enableMask & 0x1F);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        // Volume: port 2, reg = slot index
        w.writeScc(2, localSlot, 0);
        enableMask &= ~(1 << localSlot);
        // Channel enable: port 3
        w.writeScc(3, 0, enableMask & 0x1F);
    }

}
