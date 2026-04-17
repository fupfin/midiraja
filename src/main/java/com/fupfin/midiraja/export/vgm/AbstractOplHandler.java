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
 * Abstract base class for OPL2 ({@link Ym3812Handler}) and OPL3 ({@link Opl3Handler}) handlers.
 *
 * <p>
 * Both chips share the same 2-operator register layout, WOPL bank format, and melodic/percussion
 * slot model. Subclasses differ only in:
 * <ul>
 *   <li>Which VGM write method is used ({@link #writeOpl})
 *   <li>How a local slot maps to a bank + channel index ({@link #channelBankIndex})
 *   <li>Whether the feedback/connection register needs the OPL3 stereo bits ({@link #fbConnFlags})
 * </ul>
 */
abstract class AbstractOplHandler implements ChipHandler
{
    static final int[] CH_TO_MOD_OFF = {
            0x00, 0x01, 0x02, 0x08, 0x09, 0x0A, 0x10, 0x11, 0x12
    };
    static final int[] CH_TO_CAR_OFF = {
            0x03, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x13, 0x14, 0x15
    };

    private static final int DRUM_SLOTS = 4;
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

    protected final int melodicSlots;
    protected final int totalSlots;
    private int drumRoundRobin = 0;
    private final WoplBankReader.Patch[] slotPatch;

    AbstractOplHandler(int melodicSlots)
    {
        this.melodicSlots = melodicSlots;
        this.totalSlots = melodicSlots + DRUM_SLOTS;
        this.slotPatch = new WoplBankReader.Patch[totalSlots];
    }

    /**
     * Writes one OPL register via the appropriate VGM command.
     * {@code bank=0} is the primary (or only) bank; {@code bank=1} is the OPL3 secondary bank.
     */
    abstract void writeOpl(int bank, int reg, int data, VgmWriter w);

    /**
     * Returns {@code (bank << 8) | channelIndex} for the given local slot.
     * OPL2 implementations always return {@code bank=0}.
     */
    abstract int channelBankIndex(int slot);

    /**
     * Returns the bitmask OR-ed into the feedback/connection (0xC0+ch) register.
     * OPL3 returns {@code 0x30} (stereo L+R); OPL2 returns {@code 0x00}.
     */
    abstract int fbConnFlags();

    @Override
    public int percussionPriority()
    {
        return 2; // FM rhythm mode
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        WoplBankReader.Patch patch = (localSlot >= melodicSlots)
                ? drumPatch(note)
                : WOPL_BANK.melodicPatch(program);
        slotPatch[localSlot] = patch;
        writePatch(localSlot, patch, velocity, w);
        int freqNote = (localSlot >= melodicSlots)
                ? (patch.percussionKeyNumber() > 0 ? patch.percussionKeyNumber() : note)
                        + patch.noteOffset()
                : note + patch.noteOffset();
        writeFreqKeyOn(localSlot, freqNote, w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        int bankCh = channelBankIndex(localSlot);
        writeOpl(bankCh >> 8, 0xB0 + (bankCh & 0xFF), 0, w);
    }

    @Override
    public void updatePitch(int localSlot, int note, int pitchBend, int bendRangeSemitones,
            VgmWriter w)
    {
        WoplBankReader.Patch patch = slotPatch[localSlot];
        if (patch == null)
            return;
        double effNote = ChipHandler.bentNote(note + patch.noteOffset(), pitchBend,
                bendRangeSemitones);
        double freq = 440.0 * Math.pow(2.0, (effNote - 69) / 12.0);
        int block = Math.clamp((int) effNote / 12 - 1, 0, 7);
        int fnum = Math.clamp(
                (int) Math.round(freq * (1L << (20 - block)) / 49716.0), 0, 0x3FF);
        int bankCh = channelBankIndex(localSlot);
        int bank = bankCh >> 8;
        int ch = bankCh & 0xFF;
        writeOpl(bank, 0xA0 + ch, fnum & 0xFF, w);
        writeOpl(bank, 0xB0 + ch, 0x20 | (block << 2) | ((fnum >> 8) & 0x03), w);
    }

    @Override
    public void updateVolume(int localSlot, int velocity, VgmWriter w)
    {
        WoplBankReader.Patch patch = slotPatch[localSlot];
        if (patch == null)
            return;
        int bankCh = channelBankIndex(localSlot);
        int bank = bankCh >> 8;
        int ch = bankCh & 0xFF;
        int carOff = CH_TO_CAR_OFF[ch];
        int carKSLTL = (patch.carrier().ksltl() & 0xC0)
                | scaleTl(patch.carrier().ksltl() & 0x3F, velocity);
        writeOpl(bank, 0x40 + carOff, carKSLTL, w);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
            return;
        int slot = melodicSlots + drumRoundRobin;
        drumRoundRobin = (drumRoundRobin + 1) % DRUM_SLOTS;
        startNote(slot, note, velocity, 0, w);
    }

    private void writePatch(int slot, WoplBankReader.Patch patch, int velocity, VgmWriter w)
    {
        int bankCh = channelBankIndex(slot);
        int bank = bankCh >> 8;
        int ch = bankCh & 0xFF;
        int modOff = CH_TO_MOD_OFF[ch];
        int carOff = CH_TO_CAR_OFF[ch];
        int carKSLTL = (patch.carrier().ksltl() & 0xC0)
                | scaleTl(patch.carrier().ksltl() & 0x3F, velocity);
        int fbcnt = patch.fbConn() | fbConnFlags();

        writeOpl(bank, 0x20 + modOff, patch.modulator().avekf(), w);
        writeOpl(bank, 0x40 + modOff, patch.modulator().ksltl(), w);
        writeOpl(bank, 0x60 + modOff, patch.modulator().atdec(), w);
        writeOpl(bank, 0x80 + modOff, patch.modulator().susrel(), w);
        writeOpl(bank, 0xE0 + modOff, patch.modulator().wave(), w);
        writeOpl(bank, 0x20 + carOff, patch.carrier().avekf(), w);
        writeOpl(bank, 0x40 + carOff, carKSLTL, w);
        writeOpl(bank, 0x60 + carOff, patch.carrier().atdec(), w);
        writeOpl(bank, 0x80 + carOff, patch.carrier().susrel(), w);
        writeOpl(bank, 0xE0 + carOff, patch.carrier().wave(), w);
        writeOpl(bank, 0xC0 + ch, fbcnt, w);
    }

    private void writeFreqKeyOn(int slot, int note, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp(note / 12 - 1, 0, 7);
        int fnum = Math.clamp(
                (int) Math.round(freq * (1L << (20 - block)) / 49716.0), 0, 0x3FF);
        int bankCh = channelBankIndex(slot);
        int bank = bankCh >> 8;
        int ch = bankCh & 0xFF;
        writeOpl(bank, 0xA0 + ch, fnum & 0xFF, w);
        writeOpl(bank, 0xB0 + ch, 0x20 | (block << 2) | ((fnum >> 8) & 0x03), w);
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

    /**
     * Scales carrier total-level by velocity using the same logarithmic formula as
     * libADLMIDI's {@code opnModel_genericVolume}.
     *
     * <p>
     * OPL TL is 6-bit (0 = loudest, 63 = most attenuated); the KSL bits in the upper 2 bits of
     * {@code ksltl} must be extracted and preserved by the caller.
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
        return Math.clamp(63 - volume * (63 - (tl & 63)) / 127, 0, 63);
    }
}
