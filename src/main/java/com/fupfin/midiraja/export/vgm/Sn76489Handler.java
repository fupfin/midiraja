/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for the SN76489 PSG — 3 tone melody slots and a noise percussion channel.
 *
 * <p>
 * The three tone channels use period-based frequency encoding; the noise channel uses a
 * periodic or white-noise register. Frequency period: {@code round(SN76489_CLOCK / (32 * freq))}.
 */
final class Sn76489Handler implements ChipHandler
{
    private static final int MELODIC_SLOTS = 3;
    private static final int NOISE_CH = 3;

    @Override
    public ChipType chipType()
    {
        return ChipType.SN76489;
    }

    @Override
    public int slotCount()
    {
        return MELODIC_SLOTS;
    }

    @Override
    public int percussionPriority()
    {
        return 1; // PSG noise channel
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Silence all three tone channels and noise channel (max attenuation = 0x0F)
        for (int ch = 0; ch < MELODIC_SLOTS; ch++)
            w.writePsg(0x90 | (ch << 5) | 0x0F);
        w.writePsg(0x90 | (NOISE_CH << 5) | 0x0F);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int period = (int) Math.round(VgmWriter.SN76489_CLOCK / (32.0 * freq));
        period = Math.clamp(period, 1, 1023);

        // Tone frequency: latch byte then data byte
        w.writePsg(0x80 | (localSlot << 5) | (period & 0x0F));
        w.writePsg((period >> 4) & 0x3F);

        // Volume: attenuation 0 = max, 15 = silent
        int atten = 15 - (int) Math.round(velocity * 15.0 / 127.0);
        w.writePsg(0x90 | (localSlot << 5) | atten);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        w.writePsg(0x90 | (localSlot << 5) | 0x0F); // max attenuation
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
        {
            w.writePsg(0x90 | (NOISE_CH << 5) | 0x0F);
            return;
        }
        // Noise mode: white noise (0xE7) for most drums; periodic for toms
        int noiseMode = isPeriodicDrum(note) ? 0xE3 : 0xE7;
        w.writePsg(noiseMode);
        int atten = 15 - (int) Math.round(velocity * 15.0 / 127.0);
        w.writePsg(0x90 | (NOISE_CH << 5) | atten);
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        initSilence(w);
    }

    private static boolean isPeriodicDrum(int note)
    {
        // Tom-toms (GM notes 41, 43, 45, 47, 48, 50) use periodic noise for pitched quality
        return note == 41 || note == 43 || note == 45 || note == 47 || note == 48 || note == 50;
    }
}
