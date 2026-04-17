/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for the Game Boy DMG (LR35902) APU.
 *
 * <p>
 * Channel layout:
 * <ul>
 *   <li>Slot 0 — CH1 pulse+sweep (NR10-NR14, reg offsets 0x00-0x04)</li>
 *   <li>Slot 1 — CH2 pulse      (NR21-NR24, reg offsets 0x06-0x09)</li>
 *   <li>Slot 2 — CH3 wave       (NR30-NR34, reg offsets 0x0A-0x0E; wave RAM 0x20-0x2F)</li>
 *   <li>CH4 noise               (NR41-NR44, reg offsets 0x10-0x13) — percussion only</li>
 * </ul>
 *
 * <p>
 * Frequency encoding:
 * <ul>
 *   <li>Pulse (CH1/CH2): {@code x = 2048 - round(131072 / freq_hz)}, clamped to [0, 2047]</li>
 *   <li>Wave  (CH3):     {@code x = 2048 - round(65536  / freq_hz)}, clamped to [0, 2047]</li>
 * </ul>
 *
 * <p>
 * All register offsets are relative to 0xFF10 as per the VGM spec for command 0xB3.
 */
final class DmgHandler implements ChipHandler
{
    private static final int MELODIC_SLOTS = 3;

    // Register offsets from 0xFF10 base
    // CH1
    private static final int NR10 = 0x00; // sweep
    private static final int NR11 = 0x01; // length/duty
    private static final int NR12 = 0x02; // volume envelope
    private static final int NR13 = 0x03; // freq low
    private static final int NR14 = 0x04; // freq high + trigger

    // CH2 (0x05 = NR20 unused)
    private static final int NR21 = 0x06; // length/duty
    private static final int NR22 = 0x07; // volume envelope
    private static final int NR23 = 0x08; // freq low
    private static final int NR24 = 0x09; // freq high + trigger

    // CH3
    private static final int NR30 = 0x0A; // DAC enable
    private static final int NR31 = 0x0B; // length
    private static final int NR32 = 0x0C; // output level
    private static final int NR33 = 0x0D; // freq low
    private static final int NR34 = 0x0E; // freq high + trigger

    // CH4
    private static final int NR41 = 0x10; // length
    private static final int NR42 = 0x11; // volume envelope
    private static final int NR43 = 0x12; // noise params (clock shift, width, divisor)
    private static final int NR44 = 0x13; // trigger

    // Master
    private static final int NR50 = 0x14; // channel control / volume
    private static final int NR51 = 0x15; // sound output selection
    private static final int NR52 = 0x16; // sound on/off

    // Wave RAM: 16 bytes at offsets 0x20-0x2F (two 4-bit samples per byte)
    private static final int WAVE_RAM_START = 0x20;
    private static final int WAVE_RAM_SIZE = 16;

    /** 4-bit sine-wave approximation packed into 16 bytes (32 samples, 4-bit each). */
    private static final byte[] WAVE_DATA = buildWaveData();

    private static byte[] buildWaveData()
    {
        byte[] w = new byte[WAVE_RAM_SIZE];
        // 32 4-bit sine samples, two per byte (high nibble = first sample)
        int[] samples = {
            8, 11, 13, 14, 15, 14, 13, 11,
            8, 5,  3,  2,  1,  2,  3,  5,
            8, 11, 13, 14, 15, 14, 13, 11,
            8, 5,  3,  2,  1,  2,  3,  5
        };
        for (int i = 0; i < WAVE_RAM_SIZE; i++)
            w[i] = (byte) ((samples[i * 2] << 4) | (samples[i * 2 + 1] & 0x0F));
        return w;
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.DMG;
    }

    @Override
    public int slotCount()
    {
        return MELODIC_SLOTS;
    }

    @Override
    public int percussionPriority()
    {
        return 1; // CH4 noise channel, PSG-quality percussion
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Power on APU (NR52 bit 7 = 1)
        w.writeDmg(NR52, 0x80);
        // Max volume on both left and right (NR50 = 0x77: SO2 vol 7, SO1 vol 7)
        w.writeDmg(NR50, 0x77);
        // All channels to both outputs (NR51 = 0xFF)
        w.writeDmg(NR51, 0xFF);

        // CH1: sweep off, duty 50%, volume 0, trigger
        w.writeDmg(NR10, 0x00); // no sweep
        w.writeDmg(NR11, 0x80); // 50% duty, max length
        w.writeDmg(NR12, 0x00); // volume 0, no envelope
        w.writeDmg(NR13, 0x00);
        w.writeDmg(NR14, 0x80); // trigger with freq 0

        // CH2: duty 50%, volume 0, trigger
        w.writeDmg(NR21, 0x80); // 50% duty
        w.writeDmg(NR22, 0x00); // volume 0
        w.writeDmg(NR23, 0x00);
        w.writeDmg(NR24, 0x80); // trigger

        // CH3: DAC on, output level mute, trigger
        w.writeDmg(NR30, 0x80); // DAC enable
        w.writeDmg(NR31, 0x00);
        w.writeDmg(NR32, 0x00); // mute (output level = 0)
        w.writeDmg(NR33, 0x00);
        w.writeDmg(NR34, 0x80); // trigger

        // Write wave RAM (must happen when CH3 is stopped / after trigger with mute)
        for (int i = 0; i < WAVE_RAM_SIZE; i++)
            w.writeDmg(WAVE_RAM_START + i, WAVE_DATA[i] & 0xFF);

        // CH4: volume 0 (no trigger — channel is idle at startup)
        w.writeDmg(NR41, 0x00);
        w.writeDmg(NR42, 0x00); // volume 0
        w.writeDmg(NR43, 0x00);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int vol4 = (int) Math.round(velocity * 15.0 / 127.0); // 0-15

        switch (localSlot)
        {
            case 0 -> writePulseNote(NR12, NR13, NR14, freq, vol4, w);
            case 1 -> writePulseNote(NR22, NR23, NR24, freq, vol4, w);
            case 2 -> writeWaveNote(freq, vol4, w);
        }
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        switch (localSlot)
        {
            case 0 ->
            {
                w.writeDmg(NR12, 0x00);
                w.writeDmg(NR14, 0x80);
            }
            case 1 ->
            {
                w.writeDmg(NR22, 0x00);
                w.writeDmg(NR24, 0x80);
            }
            case 2 ->
            {
                w.writeDmg(NR32, 0x00);
                w.writeDmg(NR34, 0x80);
            }
        }
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
            return;

        // Noise params: choose coarser or finer noise based on drum type
        int noiseParams = isLowDrum(note) ? 0x57 : 0x22; // coarse/fine white noise
        int vol4 = (int) Math.round(velocity * 15.0 / 127.0);

        w.writeDmg(NR42, (vol4 << 4)); // volume, no envelope
        w.writeDmg(NR43, noiseParams);
        w.writeDmg(NR44, 0x80); // trigger
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < MELODIC_SLOTS; slot++)
            silenceSlot(slot, w);
        // Silence CH4 noise (set volume to 0; no trigger needed)
        w.writeDmg(NR42, 0x00);
    }

    private static void writePulseNote(int regVol, int regFreqLo, int regFreqHi,
            double freq, int vol4, VgmWriter w)
    {
        int x = 2048 - (int) Math.round(131072.0 / freq);
        x = Math.clamp(x, 0, 2047);

        w.writeDmg(regVol, vol4 << 4); // initial volume, no envelope
        w.writeDmg(regFreqLo, x & 0xFF);
        w.writeDmg(regFreqHi, ((x >> 8) & 0x07) | 0x80); // trigger bit
    }

    private static void writeWaveNote(double freq, int vol4, VgmWriter w)
    {
        int x = 2048 - (int) Math.round(65536.0 / freq);
        x = Math.clamp(x, 0, 2047);

        // Output level: 0=mute, 1=100%, 2=50%, 3=25%
        int level = vol4 == 0 ? 0 : vol4 >= 86 ? 1 : vol4 >= 43 ? 2 : 3;
        w.writeDmg(NR30, 0x80); // DAC on
        w.writeDmg(NR32, level << 5); // output level
        w.writeDmg(NR33, x & 0xFF);
        w.writeDmg(NR34, ((x >> 8) & 0x07) | 0x80); // trigger bit
    }

    /** Low-pitched drums (bass drum, floor tom) use coarser noise for deeper sound. */
    private static boolean isLowDrum(int note)
    {
        return note == 35 || note == 36 || note == 41 || note == 43 || note == 45;
    }
}
