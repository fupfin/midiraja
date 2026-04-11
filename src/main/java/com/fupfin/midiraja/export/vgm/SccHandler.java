/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for K051649 (SCC/SCC+) — 5 wavetable channels.
 *
 * <p>
 * The SCC has five channels each with an independent 32-byte waveform, frequency divider,
 * and volume register. This handler pre-loads a simple sine approximation as the default
 * waveform and programs frequency/volume on each note-on.
 *
 * <p>
 * SCC register map (VGM command 0xD2, port 0):
 * <ul>
 *   <li>0x00-0x9F: waveform data (32 bytes per channel × 5 channels)
 *   <li>0xA0-0xA9: frequency dividers (2 bytes per channel, lo then hi)
 *   <li>0xAA-0xAE: volumes (5 × 1 byte, bits 3-0)
 *   <li>0xAF: channel enable mask (bit per channel)
 * </ul>
 */
final class SccHandler implements ChipHandler
{
    private static final int SLOTS = 5;
    private static final int PERC_SLOT = 4; // local slot index dedicated to percussion

    /**
     * A simple 32-sample signed sine approximation loaded into melodic channels on init.
     * Values are 8-bit signed (-128..127).
     */
    private static final byte[] SINE_WAVE = {
            0, 25, 49, 71, 90, 106, 118, 126, 127, 126, 118, 106, 90, 71, 49, 25,
            0, -25, -49, -71, -90, -106, -118, -126, -127, -126, -118, -106, -90, -71, -49, -25
    };

    /** Sawtooth-style waveform for kick approximation (attack transient shape). */
    private static final byte[] KICK_WAVE = {
            127, 115, 102, 89, 76, 63, 50, 38, 25, 12, 0, -12, -25, -38, -50, -63,
            -76, -89, -102, -115, -127, -115, -89, -63, -38, -12, 12, 38, 63, 89, 115, 127
    };

    /** Irregular pseudo-noise pattern for snare approximation. */
    private static final byte[] SNARE_WAVE = {
            127, -127, 63, -63, 127, -63, 63, -127, 31, -31, 127, -127, 63, -63, 31, -31,
            -127, 127, -63, 63, -127, 63, -63, 127, -31, 31, -127, 127, -63, 63, -31, 31
    };

    /** Dense alternating pattern for hi-hat approximation. */
    private static final byte[] HIHAT_WAVE = {
            127, -127, 127, -127, 127, -127, 127, -127, 127, -127, 127, -127, 127, -127, 127, -127,
            127, -127, 127, -127, 127, -127, 127, -127, 127, -127, 127, -127, 127, -127, 127, -127
    };

    // GM percussion note numbers
    private static final int GM_BASS_DRUM_1 = 35;
    private static final int GM_BASS_DRUM_2 = 36;
    private static final int GM_SNARE_1 = 38;
    private static final int GM_SNARE_2 = 40;
    private static final int GM_CLOSED_HIHAT = 42;
    private static final int GM_PEDAL_HIHAT = 44;
    private static final int GM_OPEN_HIHAT = 46;

    private int enableMask = 0;

    @Override
    public ChipType chipType()
    {
        return ChipType.SCC;
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
        // Load sine wave into melodic channels 0-3 (0x00-0x7F)
        for (int ch = 0; ch < PERC_SLOT; ch++)
        {
            int base = ch * 32;
            for (int i = 0; i < 32; i++)
                w.writeScc(base + i, SINE_WAVE[i] & 0xFF);
        }
        // Zero all volumes
        for (int ch = 0; ch < SLOTS; ch++)
            w.writeScc(0xAA + ch, 0);
        // Disable all channels
        w.writeScc(0xAF, 0);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        // SCC frequency divider: Fout = Fclock / (2 * (divider + 1) * 32)
        int divider = (int) Math.round(VgmWriter.K051649_CLOCK / (64.0 * freq)) - 1;
        divider = Math.clamp(divider, 0, 0xFFF);

        w.writeScc(0xA0 + localSlot * 2, divider & 0xFF);
        w.writeScc(0xA0 + localSlot * 2 + 1, (divider >> 8) & 0x0F);

        int vol = (int) Math.round(velocity * 15.0 / 127.0);
        w.writeScc(0xAA + localSlot, vol & 0x0F);

        enableMask |= (1 << localSlot);
        w.writeScc(0xAF, enableMask & 0x1F);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        w.writeScc(0xAA + localSlot, 0);
        enableMask &= ~(1 << localSlot);
        w.writeScc(0xAF, enableMask & 0x1F);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        byte[] wave = percWave(note);
        int freq = percFreq(note);

        // Load waveform into percussion slot (0x60-0x7F = slot 4)
        int base = PERC_SLOT * 32;
        for (int i = 0; i < 32; i++)
            w.writeScc(base + i, wave[i] & 0xFF);

        int divider = (int) Math.round(VgmWriter.K051649_CLOCK / (64.0 * freq)) - 1;
        divider = Math.clamp(divider, 0, 0xFFF);
        w.writeScc(0xA0 + PERC_SLOT * 2, divider & 0xFF);
        w.writeScc(0xA0 + PERC_SLOT * 2 + 1, (divider >> 8) & 0x0F);

        int vol = (int) Math.round(velocity * 15.0 / 127.0);
        w.writeScc(0xAA + PERC_SLOT, vol & 0x0F);

        enableMask |= (1 << PERC_SLOT);
        w.writeScc(0xAF, enableMask & 0x1F);
    }

    private static byte[] percWave(int note)
    {
        if (note == GM_BASS_DRUM_1 || note == GM_BASS_DRUM_2)
            return KICK_WAVE;
        if (note == GM_CLOSED_HIHAT || note == GM_PEDAL_HIHAT || note == GM_OPEN_HIHAT)
            return HIHAT_WAVE;
        return SNARE_WAVE;
    }

    private static int percFreq(int note)
    {
        if (note == GM_BASS_DRUM_1 || note == GM_BASS_DRUM_2)
            return 80;
        if (note == GM_CLOSED_HIHAT || note == GM_PEDAL_HIHAT || note == GM_OPEN_HIHAT)
            return 2000;
        return 400; // snare and others
    }
}
