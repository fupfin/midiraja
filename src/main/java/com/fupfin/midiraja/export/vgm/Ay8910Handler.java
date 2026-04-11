/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for one AY-3-8910 chip (3 tone channels + shared noise).
 *
 * <p>
 * Slot 2 (the last slot) is reserved for percussion noise. The chip index (0 or 1) selects
 * which VGM write method is used ({@code writeAy} vs {@code writeAy2}).
 */
final class Ay8910Handler implements ChipHandler
{
    private static final int SLOTS = 3;
    private static final int NOISE_SLOT = 2; // local index of the percussion slot

    // General MIDI percussion note numbers → noise period
    private static final int GM_BASS_DRUM = 36;
    private static final int GM_CLOSED_HIHAT = 42;
    private static final int GM_SNARE = 38;
    private static final int NOISE_KICK = 31;
    private static final int NOISE_HIHAT = 4;
    private static final int NOISE_SNARE = 14;

    private final int chipIndex; // 0 = primary AY, 1 = secondary AY
    private int mixer = 0x38; // initial: all noise/tone off

    Ay8910Handler(int chipIndex)
    {
        this.chipIndex = chipIndex;
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.AY8910;
    }

    @Override
    public int slotCount()
    {
        return SLOTS;
    }

    @Override
    public boolean supportsRhythm()
    {
        return true;
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        for (int ch = 0; ch < SLOTS; ch++)
            writeAmp(w, ch, 0);
        writeReg(w, 7, 0x3F); // all bits set = mute all
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int tp = (int) Math.round(VgmWriter.AY8910_CLOCK / (16.0 * freq));
        tp = Math.clamp(tp, 1, 4095);
        writeReg(w, localSlot * 2, tp & 0xFF);
        writeReg(w, localSlot * 2 + 1, (tp >> 8) & 0x0F);

        int amp = (int) Math.round(velocity * 15.0 / 127.0);
        mixer &= ~(1 << localSlot); // enable tone
        mixer |= (1 << (localSlot + 3)); // disable noise
        writeReg(w, 7, mixer);
        writeAmp(w, localSlot, amp);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        writeAmp(w, localSlot, 0);
        mixer |= (1 << localSlot) | (1 << (localSlot + 3)); // disable tone + noise
        writeReg(w, 7, mixer);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        int noisePeriod = drumNoisePeriod(note);
        writeReg(w, 6, noisePeriod); // noise period register
        int amp = (int) Math.round(velocity * 15.0 / 127.0);
        mixer &= ~(1 << (NOISE_SLOT + 3)); // enable noise on slot 2
        mixer |= (1 << NOISE_SLOT); // disable tone on slot 2
        writeReg(w, 7, mixer);
        writeAmp(w, NOISE_SLOT, amp);
    }

    private void writeAmp(VgmWriter w, int ch, int amp)
    {
        writeReg(w, 8 + ch, amp & 0x0F);
    }

    private void writeReg(VgmWriter w, int reg, int data)
    {
        if (chipIndex == 0)
            w.writeAy(reg, data);
        else
            w.writeAy2(reg, data);
    }

    private static int drumNoisePeriod(int note)
    {
        if (note == GM_BASS_DRUM)
            return NOISE_KICK;
        if (note == GM_CLOSED_HIHAT)
            return NOISE_HIHAT;
        if (note == GM_SNARE)
            return NOISE_SNARE;
        return NOISE_SNARE; // default
    }
}
