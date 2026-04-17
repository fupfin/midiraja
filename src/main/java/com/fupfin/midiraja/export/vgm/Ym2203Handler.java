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
 * {@link ChipHandler} for YM2203 (OPN) — 3 melodic FM channels + AY-3-8910 SSG.
 *
 * <p>
 * YM2203 is the original OPN chip used in the NEC PC-88. It has 3 FM channels with the same
 * 4-operator engine as YM2612, but uses a single port (no port switching). VGM command is
 * {@code 0x55} and the clock is 3,993,600 Hz (PC-88 standard). The SSG section
 * (AY-3-8910 compatible, 3 tone + noise, registers 0x00-0x0F) is handled by an embedded
 * {@link Ay8910Handler}.
 *
 * <p>
 * Channel addressing: {@code ch_addr = slot} (0, 1, 2). There is no port 1 on YM2203.
 */
final class Ym2203Handler implements ChipHandler
{
    private static final int SLOTS = 3;
    /** Hardware slot offsets within a channel for operators S1, S3, S2, S4. */
    private static final int[] OP_SLOT_OFFSETS = { 0, 4, 8, 12 };

    private final Ay8910Handler ssg = Ay8910Handler.forYm2203Ssg();
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
        return ChipType.YM2203;
    }

    @Override
    public int slotCount()
    {
        return SLOTS + ssg.slotCount(); // 3 FM + 2 SSG melodic channels
    }

    @Override
    public int percussionPriority()
    {
        return 0; // no dedicated percussion hardware
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Set LFO frequency as specified by the bank (bit 3 = enable, bits 2-0 = frequency)
        w.writeOpn(0x22, WOPN_BANK.lfoFreq());
        // Channel 3 normal mode (disable CSM / 3-slot special mode)
        w.writeOpn(0x27, 0x00);
        // Key-off all 3 FM channels
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeOpn(0x28, slot);
        // SSG section init (registers 0x00-0x0F)
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
        WopnBankReader.Patch patch = WOPN_BANK.melodicPatch(program);
        writePatch(localSlot, patch, velocity, w);
        writeFreqKeyOn(localSlot, note + patch.noteOffset(), w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.silenceSlot(localSlot - SLOTS, w);
            return;
        }
        w.writeOpn(0x28, localSlot); // FM key-off
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeOpn(0x28, slot);
        ssg.finalSilence(w);
    }

    private void writePatch(int slot, WopnBankReader.Patch patch, int velocity, VgmWriter w)
    {
        int alg = patch.fbalg() & 0x07;
        for (int l = 0; l < 4; l++)
        {
            WopnBankReader.Operator op = patch.operators()[l];
            int regOff = slot + OP_SLOT_OFFSETS[l];
            int tl = Ym2612Handler.isCarrier(alg, l) ? Ym2612Handler.scaleTl(op.level(), velocity) : op.level();
            w.writeOpn(0x30 + regOff, op.dtfm());
            w.writeOpn(0x40 + regOff, tl);
            w.writeOpn(0x50 + regOff, op.rsatk());
            w.writeOpn(0x60 + regOff, op.amdecay1());
            w.writeOpn(0x70 + regOff, op.decay2());
            w.writeOpn(0x80 + regOff, op.susrel());
            w.writeOpn(0x90 + regOff, op.ssgeg());
        }
        // Feedback + algorithm
        w.writeOpn(0xB0 + slot, patch.fbalg() & 0x3F);
        // Enable left + right output; apply LFO sensitivity
        w.writeOpn(0xB4 + slot, 0xC0 | (patch.lfosens() & 0x37));
    }

    private void writeFreqKeyOn(int slot, int note, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp(note / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * 144.0 * (1 << (21 - block)) / VgmWriter.YM2203_CLOCK);
        fnum = Math.clamp(fnum, 0, 0x7FF);

        // Write FnumHi+block before FnumLo (hardware requirement)
        w.writeOpn(0xA4 + slot, (block << 3) | (fnum >> 8));
        w.writeOpn(0xA0 + slot, fnum & 0xFF);
        // Key-on all four operator slots
        w.writeOpn(0x28, (0xF << 4) | slot);
    }
}
