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
 * {@link ChipHandler} for YM2151 (OPM) — 7 melodic FM channels + 1 percussion FM channel.
 *
 * <p>
 * OPM differs from OPN in its note-addressing scheme: instead of fnum+block, it uses a
 * key-code (KC) byte where bits 6-4 are the octave (0-7) and bits 3-0 are a 4-bit note
 * index (skipping values 3, 7, 11 to match the 12-note scale). Key-on is written to
 * register 0x08 as {@code (opMask << 3) | ch}.
 *
 * <p>
 * WOPN2 patches from the bundled GM bank are mapped to OPM operator registers:
 * DT1/MUL (0x40+), TL (0x60+), KS/AR (0x80+), AM-EN/D1R (0xA0+), DT2/D2R (0xC0+),
 * D1L/RR (0xE0+). Channel configuration (RL/FL/CON) is at 0x20+ch.
 *
 * <p>
 * Slot 7 (channel 7) is reserved for percussion. MIDI channel 9 events use WOPN percussion
 * patches played at the patch's {@code percussionKeyNumber} tuning note.
 */
final class Ym2151Handler implements ChipHandler
{
    private static final int SLOTS = 8;

    /**
     * Maps MIDI note % 12 to OPM KC note nibble.
     * OPM skips values 3, 7, 11 (unused codes), giving 13 KC values for 12 semitones.
     */
    private static final int[] NOTE_TO_KC = { 0, 1, 2, 4, 5, 6, 8, 9, 0xA, 0xC, 0xD, 0xE };

    /** Operator layout within a channel: slot order for reg offset per-operator. */
    private static final int[] OP_OFFSETS = { 0, 8, 16, 24 };

    /** Cached patch per slot for mid-note updatePitch/updateVolume. */
    private final WopnBankReader.Patch[] slotPatch = new WopnBankReader.Patch[SLOTS];

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
        return ChipType.YM2151;
    }

    @Override
    public int slotCount()
    {
        return SLOTS;
    }

    @Override
    public int percussionPriority()
    {
        return 2; // FM patch percussion
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Key-off all 8 channels (opMask = 0, so bits 6-3 = 0)
        for (int ch = 0; ch < SLOTS; ch++)
            w.writeOpm(0x08, ch); // key-off: opMask=0 → no operators keyed on
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        WopnBankReader.Patch patch = WOPN_BANK.melodicPatch(program);
        slotPatch[localSlot] = patch;
        writePatch(localSlot, patch, velocity, w);
        writeKeyOn(localSlot, note + patch.noteOffset(), w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        w.writeOpm(0x08, localSlot); // key-off: opMask=0
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
        int baseNote = (int) Math.floor(effNote);
        int octave = Math.clamp(baseNote / 12 - 1, 0, 7);
        int kc = (octave << 4) | NOTE_TO_KC[baseNote % 12];
        int kf = (int) ((effNote - baseNote) * 64) & 0x3F;
        w.writeOpm(0x28 + localSlot, kc);
        w.writeOpm(0x30 + localSlot, kf << 2); // KF in bits 7-2
    }

    @Override
    public void updateVolume(int localSlot, int velocity, VgmWriter w)
    {
        WopnBankReader.Patch patch = slotPatch[localSlot];
        if (patch == null)
            return;
        int alg = patch.fbalg() & 0x07;
        for (int l = 0; l < 4; l++)
        {
            if (!Ym2612Handler.isCarrier(alg, l))
                continue;
            WopnBankReader.Operator op = patch.operators()[l];
            int regOff = OP_OFFSETS[l] + localSlot;
            w.writeOpm(0x60 + regOff, scaleTl(op.level(), velocity) & 0x7F);
        }
    }

    /** Slot used for FM-patch percussion when YM2151 is the sole handler (no MSM6258). */
    private static final int PERC_SLOT = SLOTS - 1;

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
        writeKeyOn(PERC_SLOT, keyNote + patch.noteOffset(), w);
    }

    private void writePatch(int slot, WopnBankReader.Patch patch, int velocity, VgmWriter w)
    {
        int alg = patch.fbalg() & 0x07;
        int fb = (patch.fbalg() >> 3) & 0x07;

        // Channel config: RL=11 (stereo), FL (feedback), CON (algorithm)
        w.writeOpm(0x20 + slot, 0xC0 | (fb << 3) | alg);

        // LFO sensitivity (PMS bits 6-4, AMS bits 1-0)
        w.writeOpm(0x38 + slot, patch.lfosens() & 0x77);

        for (int l = 0; l < 4; l++)
        {
            WopnBankReader.Operator op = patch.operators()[l];
            int regOff = OP_OFFSETS[l] + slot;
            int tl = Ym2612Handler.isCarrier(alg, l) ? scaleTl(op.level(), velocity) : op.level();

            // DT1/MUL: WOPN dtfm byte — high nibble = DT1 (maps to OPM DT1), low nibble = MUL
            w.writeOpm(0x40 + regOff, op.dtfm());
            // TL: 7-bit total level (0 = loudest)
            w.writeOpm(0x60 + regOff, tl & 0x7F);
            // KS/AR: same encoding in WOPN and OPM
            w.writeOpm(0x80 + regOff, op.rsatk());
            // AM-EN/D1R: AM flag (bit 7) + D1R (bits 4-0)
            w.writeOpm(0xA0 + regOff, op.amdecay1());
            // DT2/D2R: DT2 = 0, D2R from patch
            w.writeOpm(0xC0 + regOff, op.decay2() & 0x1F);
            // D1L/RR: same encoding as SL/RR in WOPN
            w.writeOpm(0xE0 + regOff, op.susrel());
        }
    }

    private void writeKeyOn(int slot, int note, VgmWriter w)
    {
        int octave = Math.clamp(note / 12 - 1, 0, 7);
        int kc = (octave << 4) | NOTE_TO_KC[note % 12];
        w.writeOpm(0x28 + slot, kc);        // KC: octave + note code
        w.writeOpm(0x30 + slot, 0);          // KF: no fine pitch offset
        w.writeOpm(0x08, (0xF << 3) | slot); // key-on: all 4 operators
    }

    /**
     * Scales 7-bit OPM total level by velocity using the same logarithmic formula as
     * Ym2612Handler. TL is 7-bit (0 = loudest, 127 = most attenuated).
     */
    private static int scaleTl(int tl, int velocity)
    {
        return Ym2612Handler.scaleTl(tl, velocity);
    }
}
