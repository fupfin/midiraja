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
 * {@link ChipHandler} for YMF262 (OPL3) — 14 melodic FM channels + 4 drum round-robin slots.
 *
 * <p>
 * Melodic slots 0-8 → OPL3 bank 0 channels 0-8; slots 9-13 → bank 1 channels 0-4.
 * Drum slots 14-17 → bank 1 channels 5-8. Each channel uses a 2-operator FM patch from the
 * libADLMIDI WOPL bank file.
 */
final class Opl3Handler implements ChipHandler
{
    private static final int MELODIC_SLOTS = 14;
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
        return ChipType.OPL3;
    }

    @Override
    public int slotCount()
    {
        return TOTAL_SLOTS;
    }

    @Override
    public boolean supportsRhythm()
    {
        return true;
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        w.writeOpl3Bank1(0x05, 0x01); // Enable OPL3 mode
        for (int ch = 0; ch < 9; ch++)
            w.writeOpl3(0xB0 + ch, 0);
        for (int ch = 0; ch < 9; ch++)
            w.writeOpl3Bank1(0xB0 + ch, 0);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        WoplBankReader.Patch patch = (localSlot >= MELODIC_SLOTS)
                ? drumPatch(note)
                : WOPL_BANK.melodicPatch(program);
        writePatch(localSlot, patch, velocity, w);
        int freqNote = (localSlot >= MELODIC_SLOTS) ? patch.percussionKeyNumber() : note;
        writeFreqKeyOn(localSlot, freqNote, w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        int bankSlot = bankAndChannelIndex(localSlot);
        int bank = bankSlot >> 8;
        int ch = bankSlot & 0xFF;
        if (bank == 0)
            w.writeOpl3(0xB0 + ch, 0);
        else
            w.writeOpl3Bank1(0xB0 + ch, 0);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        int slot = MELODIC_SLOTS + drumRoundRobin;
        drumRoundRobin = (drumRoundRobin + 1) % DRUM_SLOTS;
        startNote(slot, note, velocity, 0, w);
    }

    private void writePatch(int slot, WoplBankReader.Patch patch, int velocity, VgmWriter w)
    {
        int bankSlot = bankAndChannelIndex(slot);
        int bank = bankSlot >> 8;
        int ch = bankSlot & 0xFF;
        int modOff = CH_TO_MOD_OFF[ch];
        int carOff = CH_TO_CAR_OFF[ch];

        int modAVEKM = patch.modulator().avekf();
        int modKSLTL = patch.modulator().ksltl();
        int modARDR = patch.modulator().atdec();
        int modSLRR = patch.modulator().susrel();
        int modWS = patch.modulator().wave();
        int carAVEKM = patch.carrier().avekf();
        int carKSLTL = (patch.carrier().ksltl() & 0xC0) | ((127 - velocity) * 40 / 127);
        int carARDR = patch.carrier().atdec();
        int carSLRR = patch.carrier().susrel();
        int carWS = patch.carrier().wave();
        int fbcnt = patch.fbConn();

        if (bank == 0)
        {
            w.writeOpl3(0x20 + modOff, modAVEKM);
            w.writeOpl3(0x40 + modOff, modKSLTL);
            w.writeOpl3(0x60 + modOff, modARDR);
            w.writeOpl3(0x80 + modOff, modSLRR);
            w.writeOpl3(0xE0 + modOff, modWS);
            w.writeOpl3(0x20 + carOff, carAVEKM);
            w.writeOpl3(0x40 + carOff, carKSLTL);
            w.writeOpl3(0x60 + carOff, carARDR);
            w.writeOpl3(0x80 + carOff, carSLRR);
            w.writeOpl3(0xE0 + carOff, carWS);
            w.writeOpl3(0xC0 + ch, fbcnt | 0x30);
        }
        else
        {
            w.writeOpl3Bank1(0x20 + modOff, modAVEKM);
            w.writeOpl3Bank1(0x40 + modOff, modKSLTL);
            w.writeOpl3Bank1(0x60 + modOff, modARDR);
            w.writeOpl3Bank1(0x80 + modOff, modSLRR);
            w.writeOpl3Bank1(0xE0 + modOff, modWS);
            w.writeOpl3Bank1(0x20 + carOff, carAVEKM);
            w.writeOpl3Bank1(0x40 + carOff, carKSLTL);
            w.writeOpl3Bank1(0x60 + carOff, carARDR);
            w.writeOpl3Bank1(0x80 + carOff, carSLRR);
            w.writeOpl3Bank1(0xE0 + carOff, carWS);
            w.writeOpl3Bank1(0xC0 + ch, fbcnt | 0x30);
        }
    }

    private void writeFreqKeyOn(int slot, int note, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp(note / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * (1L << (20 - block)) / 49716.0);
        fnum = Math.clamp(fnum, 0, 0x3FF);

        int bankSlot = bankAndChannelIndex(slot);
        int bank = bankSlot >> 8;
        int ch = bankSlot & 0xFF;

        if (bank == 0)
        {
            w.writeOpl3(0xA0 + ch, fnum & 0xFF);
            w.writeOpl3(0xB0 + ch, 0x20 | (block << 2) | ((fnum >> 8) & 0x03));
        }
        else
        {
            w.writeOpl3Bank1(0xA0 + ch, fnum & 0xFF);
            w.writeOpl3Bank1(0xB0 + ch, 0x20 | (block << 2) | ((fnum >> 8) & 0x03));
        }
    }

    /**
     * Returns bank (upper byte) and OPL3 channel index (lower byte) for a local slot.
     * Slots 0-8 → bank 0, ch 0-8; slots 9-13 → bank 1, ch 0-4; slots 14-17 → bank 1, ch 5-8.
     */
    static int bankAndChannelIndex(int slot)
    {
        if (slot < 9)
            return slot;
        if (slot < MELODIC_SLOTS)
            return (1 << 8) | (slot - 9);
        return (1 << 8) | (slot - MELODIC_SLOTS + 5);
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
