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
 * {@link ChipHandler} for YM2612 (OPN2) — 5 melodic FM channels + 1 percussion FM channel.
 *
 * <p>
 * Channels 0-2 are on port 0, channels 3-5 are on port 1. Each channel uses a 4-operator
 * FM patch loaded from the WOPN2 GM bank bundled with libOPNMIDI. WOPN operators are stored
 * in hardware slot order [S1, S3, S2, S4] with offsets 0, 4, 8, 12 per channel.
 *
 * <p>
 * Slot 5 (port 1, channel 2) is reserved for percussion. MIDI channel 9 events use WOPN
 * percussion patches and are played at the patch's {@code percussionKeyNumber} tuning note.
 */
final class Ym2612Handler implements ChipHandler
{
    private static final int SLOTS = 6;
    /** Slot reserved for percussion (port 1, ch 2). Excluded from melodic slotCount. */
    private static final int PERC_SLOT = SLOTS - 1;
    /** Hardware slot offsets within a port for operators S1, S3, S2, S4. */
    private static final int[] OP_SLOT_OFFSETS = { 0, 4, 8, 12 };

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

    @Override
    public ChipType chipType()
    {
        return ChipType.YM2612;
    }

    @Override
    public int slotCount()
    {
        return SLOTS - 1; // slot 5 reserved for percussion
    }

    @Override
    public int percussionPriority()
    {
        return 2; // FM patch percussion
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Set LFO frequency as specified by the bank (bit 3 = enable, bits 2-0 = frequency)
        w.writeYm2612(0, 0x22, WOPN_BANK.lfoFreq());
        // Channel 3 normal mode (disable CSM / 3-slot special mode)
        w.writeYm2612(0, 0x27, 0x00);
        // Disable DAC so channel 5 is available for FM synthesis
        w.writeYm2612(0, 0x2B, 0x00);
        // Key-off all 6 channels (ch_addr: ch 0-2 → 0-2, ch 3-5 → 4-6)
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeYm2612(0, 0x28, chAddr(slot));
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        WopnBankReader.Patch patch = WOPN_BANK.melodicPatch(program);
        writePatch(localSlot, patch, velocity, w);
        writeFreqKeyOn(localSlot, note + patch.noteOffset(), w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        w.writeYm2612(0, 0x28, chAddr(localSlot)); // key-off
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
        {
            silenceSlot(PERC_SLOT, w);
            return;
        }
        WopnBankReader.Patch patch;
        try
        {
            patch = WOPN_BANK.percussionPatch(note);
        }
        catch (IllegalArgumentException e)
        {
            return; // no percussion patch for this GM note
        }
        writePatch(PERC_SLOT, patch, velocity, w);
        int keyNote = patch.percussionKeyNumber() > 0 ? patch.percussionKeyNumber() : note;
        writeFreqKeyOn(PERC_SLOT, keyNote + patch.noteOffset(), w);
    }

    private void writePatch(int slot, WopnBankReader.Patch patch, int velocity, VgmWriter w)
    {
        int port = slot < 3 ? 0 : 1;
        int ch = slot < 3 ? slot : slot - 3;
        int alg = patch.fbalg() & 0x07;

        for (int l = 0; l < 4; l++)
        {
            WopnBankReader.Operator op = patch.operators()[l];
            int regOff = ch + OP_SLOT_OFFSETS[l];
            int tl = isCarrier(alg, l) ? scaleTl(op.level(), velocity) : op.level();
            w.writeYm2612(port, 0x30 + regOff, op.dtfm());
            w.writeYm2612(port, 0x40 + regOff, tl);
            w.writeYm2612(port, 0x50 + regOff, op.rsatk());
            w.writeYm2612(port, 0x60 + regOff, op.amdecay1());
            w.writeYm2612(port, 0x70 + regOff, op.decay2());
            w.writeYm2612(port, 0x80 + regOff, op.susrel());
            w.writeYm2612(port, 0x90 + regOff, op.ssgeg());
        }

        // Feedback + algorithm
        w.writeYm2612(port, 0xB0 + ch, patch.fbalg() & 0x3F);
        // Enable left + right output; apply LFO sensitivity
        w.writeYm2612(port, 0xB4 + ch, 0xC0 | (patch.lfosens() & 0x37));
    }

    private void writeFreqKeyOn(int slot, int note, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp(note / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * 144.0 * (1 << (21 - block)) / VgmWriter.YM2612_CLOCK);
        fnum = Math.clamp(fnum, 0, 0x7FF);

        int port = slot < 3 ? 0 : 1;
        int ch = slot < 3 ? slot : slot - 3;

        // Write FnumHi+block before FnumLo (hardware requirement)
        w.writeYm2612(port, 0xA4 + ch, (block << 3) | (fnum >> 8));
        w.writeYm2612(port, 0xA0 + ch, fnum & 0xFF);
        // Key-on all four operator slots
        w.writeYm2612(0, 0x28, (0xF << 4) | chAddr(slot));
    }

    /** Returns the key-on ch_addr for a given slot (0-5). */
    private static int chAddr(int slot)
    {
        return slot < 3 ? slot : slot - 3 + 4;
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
