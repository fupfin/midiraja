/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for YM2413 (OPLL) — 9 FM melodic channels with optional rhythm mode.
 *
 * <p>
 * Slots 0-8 map directly to YM2413 channels 0-8. When MIDI channel 9 percussion arrives,
 * rhythm mode is enabled (register 0x0E bit 5) and individual rhythm instruments are triggered.
 */
final class Ym2413Handler implements ChipHandler
{
    private static final int SLOTS = 9;
    private boolean rhythmMode = false;
    private int rhythmReg = 0; // current value of register 0x0E
    private final int[] vol = new int[5]; // per-instrument attenuation: 0=loud, 15=silent
    private final int[] slotInstrument = new int[SLOTS]; // cached instrument index per slot

    @Override
    public ChipType chipType()
    {
        return ChipType.YM2413;
    }

    @Override
    public int slotCount()
    {
        return SLOTS;
    }

    @Override
    public int percussionPriority()
    {
        return 2; // FM rhythm mode
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        for (int ch = 0; ch < SLOTS; ch++)
        {
            w.writeYm2413(0x20 + ch, 0); // key off
            w.writeYm2413(0x30 + ch, 0x0F); // instrument 0, silent volume
        }
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        int instrument = Ym2413PatchMap.lookup(program);
        slotInstrument[localSlot] = instrument;
        int vol = 15 - (int) Math.round(velocity * 15.0 / 127.0);

        int[] fb = computeFnumBlock(note);
        w.writeYm2413(0x30 + localSlot, (instrument << 4) | vol);
        w.writeYm2413(0x10 + localSlot, fb[0] & 0xFF);
        w.writeYm2413(0x20 + localSlot, 0x10 | (fb[1] << 1) | ((fb[0] >> 8) & 0x01)); // key on
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        w.writeYm2413(0x20 + localSlot, 0); // key off + clear freq
    }

    @Override
    public void updatePitch(int localSlot, int note, int pitchBend, int bendRangeSemitones,
            VgmWriter w)
    {
        double effNote = ChipHandler.bentNote(note, pitchBend, bendRangeSemitones);
        int[] fb = computeFnumBlock(effNote);
        w.writeYm2413(0x10 + localSlot, fb[0] & 0xFF);
        w.writeYm2413(0x20 + localSlot, 0x10 | (fb[1] << 1) | ((fb[0] >> 8) & 0x01)); // key-on preserved
    }

    @Override
    public void updateVolume(int localSlot, int velocity, VgmWriter w)
    {
        int newVol = 15 - (int) Math.round(velocity * 15.0 / 127.0);
        w.writeYm2413(0x30 + localSlot, (slotInstrument[localSlot] << 4) | newVol);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        int bit = rhythmBit(note);
        if (bit < 0)
            return;
        if (!rhythmMode && velocity > 0)
        {
            rhythmMode = true;
            rhythmReg = 0x20;
            w.writeYm2413(0x0E, rhythmReg);
        }
        if (!rhythmMode)
            return;
        int mask = 1 << bit;
        if (velocity > 0)
        {
            vol[bit] = 15 - (int) Math.round(velocity * 15.0 / 127.0);
            writeRhythmVolume(bit, w);
            if ((rhythmReg & mask) != 0) // clear for rising-edge retrigger
                w.writeYm2413(0x0E, rhythmReg & ~mask);
            rhythmReg |= mask;
            w.writeYm2413(0x0E, rhythmReg);
        }
        else
        {
            rhythmReg &= ~mask;
            w.writeYm2413(0x0E, rhythmReg);
        }
    }

    /**
     * Writes the volume register(s) for the given rhythm bit.
     *
     * <p>
     * YM2413 rhythm volume layout:
     * <ul>
     *   <li>0x36: BD — both nibbles equal: {@code (vol[4] << 4) | vol[4]}</li>
     *   <li>0x37: HH (upper nibble) + SD (lower nibble)</li>
     *   <li>0x38: TOM (upper nibble) + CYM (lower nibble)</li>
     * </ul>
     */
    private void writeRhythmVolume(int bit, VgmWriter w)
    {
        switch (bit)
        {
            case 4 -> w.writeYm2413(0x36, (vol[4] << 4) | vol[4]);
            case 0, 3 -> w.writeYm2413(0x37, (vol[0] << 4) | vol[3]); // HH(upper) | SD(lower)
            case 2, 1 -> w.writeYm2413(0x38, (vol[2] << 4) | vol[1]); // TOM(upper) | CYM(lower)
        }
    }

    private static int[] computeFnumBlock(int note)
    {
        return computeFnumBlock((double) note);
    }

    private static int[] computeFnumBlock(double note)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp((int) note / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * 72.0 * (1L << (19 - block)) / VgmWriter.YM2413_CLOCK);
        fnum = Math.clamp(fnum, 0, 0x1FF);
        return new int[] { fnum, block };
    }

    private static int rhythmBit(int note)
    {
        return switch (note)
        {
            case 35, 36 -> 4; // Bass drum
            case 38, 40 -> 3; // Snare drum
            case 41, 43, 45, 47, 48, 50 -> 2; // Tom
            case 49, 51, 53, 55, 57, 59 -> 1; // Cymbal
            case 42, 44, 46 -> 0; // Hi-hat
            default -> -1;
        };
    }
}
