/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Abstract base class for OPN-family FM chip handlers: YM2612, YM2608, YM2610, YM2203.
 *
 * <p>
 * All OPN chips share the same 4-operator FM engine, WOPN2 bank format, and fnum/block
 * frequency calculation. Subclasses differ only in:
 * <ul>
 *   <li>Which VGM write method is used ({@link #writeFm})
 *   <li>The chip master clock used for fnum calculation ({@link #fmClock})
 *   <li>How a local slot maps to a key-on ch_addr ({@link #chAddr})
 *   <li>How a local slot maps to a VGM port ({@link #portOf}) and hardware channel ({@link #chOf})
 * </ul>
 */
abstract class AbstractOpnHandler implements ChipHandler
{
    /** Hardware slot offsets within a port for operators S1, S3, S2, S4. */
    static final int[] OP_SLOT_OFFSETS = { 0, 4, 8, 12 };

    private static final WopnBankReader WOPN_BANK = loadWopnBank();

    private static WopnBankReader loadWopnBank()
    {
        try
        {
            return WopnBankReader.load(Path.of("ext/libOPNMIDI/fm_banks/gm.wopn"));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    protected final int fmSlots;
    private final WopnBankReader.Patch[] slotPatch;

    AbstractOpnHandler(int fmSlots)
    {
        this.fmSlots = fmSlots;
        this.slotPatch = new WopnBankReader.Patch[fmSlots];
    }

    /**
     * Writes one FM register to the chip at the given VGM port.
     * {@code port=0} is the primary port; {@code port=1} is the secondary (where applicable).
     */
    abstract void writeFm(int port, int reg, int data, VgmWriter w);

    /** Returns the chip master clock in Hz, used for fnum calculation. */
    abstract int fmClock();

    /**
     * Returns the key-on ch_addr for the given FM slot.
     * Used in the key-on/key-off register (0x28).
     */
    abstract int chAddr(int slot);

    /** Returns the VGM port (0 or 1) for the given FM slot. */
    abstract int portOf(int slot);

    /** Returns the hardware channel index within a port for the given FM slot. */
    abstract int chOf(int slot);

    /** Exposes the shared WOPN bank so subclasses (e.g. for LFO init) can read it. */
    static WopnBankReader wopnBank()
    {
        return WOPN_BANK;
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        startNoteWithPatch(localSlot, WOPN_BANK.melodicPatch(program), note, velocity, w);
    }

    /**
     * Starts a note using a pre-resolved patch (used by percussion handlers to supply a
     * percussion patch instead of calling {@code melodicPatch(program)}).
     */
    void startNoteWithPatch(int localSlot, WopnBankReader.Patch patch, int note, int velocity,
            VgmWriter w)
    {
        slotPatch[localSlot] = patch;
        writePatch(localSlot, patch, velocity, w);
        writeFreqKeyOn(localSlot, note + patch.noteOffset(), w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        writeFm(0, 0x28, chAddr(localSlot), w); // key-off (no operator mask)
    }

    @Override
    public void updatePitch(int localSlot, int note, int pitchBend, int bendRangeSemitones,
            VgmWriter w)
    {
        WopnBankReader.Patch patch = slotPatch[localSlot];
        if (patch == null)
            return;
        double effNote = ChipHandler.bentNote(note + patch.noteOffset(), pitchBend,
                bendRangeSemitones);
        double freq = 440.0 * Math.pow(2.0, (effNote - 69) / 12.0);
        int block = Math.clamp((int) effNote / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * 144.0 * (1 << (21 - block)) / fmClock());
        fnum = Math.clamp(fnum, 0, 0x7FF);
        int port = portOf(localSlot);
        int ch = chOf(localSlot);
        writeFm(port, 0xA4 + ch, (block << 3) | (fnum >> 8), w);
        writeFm(port, 0xA0 + ch, fnum & 0xFF, w);
        // No key-off/key-on — only frequency registers updated
    }

    @Override
    public void updateVolume(int localSlot, int velocity, VgmWriter w)
    {
        WopnBankReader.Patch patch = slotPatch[localSlot];
        if (patch == null)
            return;
        int port = portOf(localSlot);
        int ch = chOf(localSlot);
        int alg = patch.fbalg() & 0x07;
        for (int l = 0; l < 4; l++)
        {
            if (!isCarrier(alg, l))
                continue;
            WopnBankReader.Operator op = patch.operators()[l];
            int regOff = ch + OP_SLOT_OFFSETS[l];
            writeFm(port, 0x40 + regOff, scaleTl(op.level(), velocity), w);
        }
    }

    private void writePatch(int slot, WopnBankReader.Patch patch, int velocity, VgmWriter w)
    {
        int port = portOf(slot);
        int ch = chOf(slot);
        int alg = patch.fbalg() & 0x07;

        for (int l = 0; l < 4; l++)
        {
            WopnBankReader.Operator op = patch.operators()[l];
            int regOff = ch + OP_SLOT_OFFSETS[l];
            int tl = isCarrier(alg, l) ? scaleTl(op.level(), velocity) : op.level();
            writeFm(port, 0x30 + regOff, op.dtfm(), w);
            writeFm(port, 0x40 + regOff, tl, w);
            writeFm(port, 0x50 + regOff, op.rsatk(), w);
            writeFm(port, 0x60 + regOff, op.amdecay1(), w);
            writeFm(port, 0x70 + regOff, op.decay2(), w);
            writeFm(port, 0x80 + regOff, op.susrel(), w);
            writeFm(port, 0x90 + regOff, op.ssgeg(), w);
        }

        // Feedback + algorithm
        writeFm(port, 0xB0 + ch, patch.fbalg() & 0x3F, w);
        // Enable left + right output; apply LFO sensitivity
        writeFm(port, 0xB4 + ch, 0xC0 | (patch.lfosens() & 0x37), w);
    }

    private void writeFreqKeyOn(int slot, int note, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp(note / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * 144.0 * (1 << (21 - block)) / fmClock());
        fnum = Math.clamp(fnum, 0, 0x7FF);

        int port = portOf(slot);
        int ch = chOf(slot);

        // Write FnumHi+block before FnumLo (hardware requirement)
        writeFm(port, 0xA4 + ch, (block << 3) | (fnum >> 8), w);
        writeFm(port, 0xA0 + ch, fnum & 0xFF, w);
        // Key-on all four operator slots; reg 0x28 is always on port 0
        writeFm(0, 0x28, (0xF << 4) | chAddr(slot), w);
    }

    /**
     * Returns true if the operator at the given WOPN index is a carrier for the algorithm.
     *
     * <p>
     * WOPN stores operators as [S1, S3, S2, S4]; alg carriers:
     * alg 0-3: S4 (index 3); alg 4: S2+S4 (indices 2, 3); alg 5-6: S3+S2+S4 (indices 1-3);
     * alg 7: all.
     */
    static boolean isCarrier(int alg, int opIndex)
    {
        return switch (alg)
        {
            case 0, 1, 2, 3 -> opIndex == 3;
            case 4 -> opIndex == 2 || opIndex == 3;
            case 5, 6 -> opIndex >= 1;
            default -> true; // alg 7: all operators output directly
        };
    }

    /**
     * Scales carrier total-level by velocity using the same logarithmic formula as
     * libOPNMIDI's {@code opnModel_genericVolume} (model_generic.c).
     *
     * <p>
     * {@code volume} maps velocity 0→0 and velocity 127→127 on a log curve;
     * then {@code tl = 127 - volume*(127-tl_patch)/127} blends the patch TL toward
     * the maximum-attenuation extreme at low velocities.
     */
    static int scaleTl(int tl, int velocity)
    {
        final double c1 = 11.541560327111707;
        final double c2 = 160.1379199767093;
        final long minVolume = 1_108_075L; // 8725 * 127
        long vol = (long) velocity * 127L * 127L * 127L;
        int volume;
        if (vol > minVolume)
        {
            double lv = Math.log((double) vol);
            volume = Math.clamp((int) (lv * c1 - c2) * 2, 0, 127);
        }
        else
        {
            volume = 0;
        }
        return Math.clamp(127 - volume * (127 - (tl & 127)) / 127, 0, 127);
    }
}
