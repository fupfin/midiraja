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
 * {@link ChipHandler} for YM3812 (OPL2 / AdLib) — 9 melodic FM channels + 4 drum round-robin slots.
 *
 * <p>
 * OPL2 is a strict single-bank subset of OPL3: same 2-operator register layout, same WOPL bank
 * format, but only 9 channels with no bank 1. All writes use VGM command 0x5A.
 *
 * <p>
 * Drum slots 9-12 are mapped to OPL2 channels 5-8, round-robin, reusing the same channel
 * range as late melodic slots but with dedicated percussion patches.
 */
final class Ym3812Handler implements ChipHandler
{
    private static final int MELODIC_SLOTS = 9;
    private static final int DRUM_SLOTS = 4;
    static final int TOTAL_SLOTS = MELODIC_SLOTS + DRUM_SLOTS;

    private static final int[] CH_TO_MOD_OFF = {
            0x00, 0x01, 0x02, 0x08, 0x09, 0x0A, 0x10, 0x11, 0x12
    };
    private static final int[] CH_TO_CAR_OFF = {
            0x03, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x13, 0x14, 0x15
    };

    private static final WoplBankReader WOPL_BANK = loadWoplBank();

    private static WoplBankReader loadWoplBank()
    {
        try
        {
            return WoplBankReader.load(
                Path.of("ext/libADLMIDI/fm_banks/wopl_files/fatman-2op.wopl"));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private int drumRoundRobin = 0;

    @Override
    public ChipType chipType()
    {
        return ChipType.YM3812;
    }

    @Override
    public int slotCount()
    {
        return MELODIC_SLOTS;
    }

    @Override
    public int percussionPriority()
    {
        return 2; // FM rhythm mode
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        for (int ch = 0; ch < MELODIC_SLOTS; ch++)
            w.writeOpl2(0xB0 + ch, 0);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        WoplBankReader.Patch patch = (localSlot >= MELODIC_SLOTS)
                ? drumPatch(note)
                : WOPL_BANK.melodicPatch(program);
        writePatch(localSlot, patch, velocity, w);
        int freqNote = (localSlot >= MELODIC_SLOTS)
                ? (patch.percussionKeyNumber() > 0 ? patch.percussionKeyNumber() : note)
                        + patch.noteOffset()
                : note + patch.noteOffset();
        writeFreqKeyOn(localSlot, freqNote, w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        int ch = channelIndex(localSlot);
        w.writeOpl2(0xB0 + ch, 0);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
            return;
        int slot = MELODIC_SLOTS + drumRoundRobin;
        drumRoundRobin = (drumRoundRobin + 1) % DRUM_SLOTS;
        startNote(slot, note, velocity, 0, w);
    }

    private void writePatch(int slot, WoplBankReader.Patch patch, int velocity, VgmWriter w)
    {
        int ch = channelIndex(slot);
        int modOff = CH_TO_MOD_OFF[ch];
        int carOff = CH_TO_CAR_OFF[ch];

        int modAVEKM = patch.modulator().avekf();
        int modKSLTL = patch.modulator().ksltl();
        int modARDR = patch.modulator().atdec();
        int modSLRR = patch.modulator().susrel();
        int modWS = patch.modulator().wave();
        int carAVEKM = patch.carrier().avekf();
        int carKSLTL = (patch.carrier().ksltl() & 0xC0) | Opl3Handler.scaleTl(patch.carrier().ksltl() & 0x3F, velocity);
        int carARDR = patch.carrier().atdec();
        int carSLRR = patch.carrier().susrel();
        int carWS = patch.carrier().wave();
        int fbcnt = patch.fbConn();

        w.writeOpl2(0x20 + modOff, modAVEKM);
        w.writeOpl2(0x40 + modOff, modKSLTL);
        w.writeOpl2(0x60 + modOff, modARDR);
        w.writeOpl2(0x80 + modOff, modSLRR);
        w.writeOpl2(0xE0 + modOff, modWS);
        w.writeOpl2(0x20 + carOff, carAVEKM);
        w.writeOpl2(0x40 + carOff, carKSLTL);
        w.writeOpl2(0x60 + carOff, carARDR);
        w.writeOpl2(0x80 + carOff, carSLRR);
        w.writeOpl2(0xE0 + carOff, carWS);
        w.writeOpl2(0xC0 + ch, fbcnt);
    }

    private void writeFreqKeyOn(int slot, int note, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp(note / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * (1L << (20 - block)) / 49716.0);
        fnum = Math.clamp(fnum, 0, 0x3FF);

        int ch = channelIndex(slot);
        w.writeOpl2(0xA0 + ch, fnum & 0xFF);
        w.writeOpl2(0xB0 + ch, 0x20 | (block << 2) | ((fnum >> 8) & 0x03));
    }

    /**
     * Returns OPL2 channel index for a local slot.
     * Slots 0-8 → ch 0-8; drum slots 9-12 → ch 5-8.
     */
    static int channelIndex(int slot)
    {
        if (slot < MELODIC_SLOTS)
            return slot;
        return slot - MELODIC_SLOTS + 5; // drum slots 9-12 → ch 5-8
    }

    private static WoplBankReader.Patch drumPatch(int note)
    {
        try
        {
            return WOPL_BANK.percussionPatch(note);
        }
        catch (IllegalArgumentException e)
        {
            return WOPL_BANK.melodicPatch(0);
        }
    }
}
