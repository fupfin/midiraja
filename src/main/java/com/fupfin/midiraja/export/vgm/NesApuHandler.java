/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for the NES APU (RP2A03).
 *
 * <p>
 * Channel layout:
 * <ul>
 *   <li>Slot 0 — CH1 pulse (regs 0x00-0x03)</li>
 *   <li>Slot 1 — CH2 pulse (regs 0x04-0x07)</li>
 *   <li>Slot 2 — CH3 triangle (regs 0x08-0x0B)</li>
 *   <li>CH4 noise (regs 0x0C-0x0F) — percussion only</li>
 * </ul>
 *
 * <p>
 * Frequency encoding:
 * <ul>
 *   <li>Pulse (CH1/CH2): {@code timer = round(NES_APU_CLOCK / (16 × freq_hz)) - 1}, clamped to [0, 2047]</li>
 *   <li>Triangle (CH3): {@code timer = round(NES_APU_CLOCK / (32 × freq_hz)) - 1}, clamped to [0, 2047]</li>
 * </ul>
 *
 * <p>
 * Register map (accessed via VGM command 0xB4):
 * <ul>
 *   <li>0x00 — CH1 duty/length/envelope: bits 7-6=duty, bit 5=length halt, bit 4=constant vol, bits 3-0=volume</li>
 *   <li>0x01 — CH1 sweep (disabled = 0x00)</li>
 *   <li>0x02 — CH1 timer low</li>
 *   <li>0x03 — CH1 timer high + length counter load</li>
 *   <li>0x04-0x07 — CH2 (same layout)</li>
 *   <li>0x08 — CH3 linear counter control</li>
 *   <li>0x0A — CH3 timer low</li>
 *   <li>0x0B — CH3 timer high + length counter load</li>
 *   <li>0x0C — CH4 length halt + envelope</li>
 *   <li>0x0E — CH4 noise mode + period</li>
 *   <li>0x0F — CH4 length counter load (trigger)</li>
 *   <li>0x15 — status: bits 3-0 = CH4/CH3/CH2/CH1 enable</li>
 *   <li>0x17 — frame counter (0x40 = 5-step mode, IRQ inhibit)</li>
 * </ul>
 */
final class NesApuHandler implements ChipHandler
{
    private static final int MELODIC_SLOTS = 3;

    // Pulse CH1 registers
    private static final int P1_VOL = 0x00; // duty/length/vol
    private static final int P1_SWEEP = 0x01;
    private static final int P1_TIMER_LO = 0x02;
    private static final int P1_TIMER_HI = 0x03; // high 3 bits + length load

    // Pulse CH2 registers
    private static final int P2_VOL = 0x04;
    private static final int P2_SWEEP = 0x05;
    private static final int P2_TIMER_LO = 0x06;
    private static final int P2_TIMER_HI = 0x07;

    // Triangle CH3 registers
    private static final int TRI_LINEAR = 0x08; // linear counter control
    private static final int TRI_TIMER_LO = 0x0A;
    private static final int TRI_TIMER_HI = 0x0B;

    // Noise CH4 registers
    private static final int NOISE_VOL = 0x0C;
    private static final int NOISE_PERIOD = 0x0E; // mode + period index
    private static final int NOISE_LEN = 0x0F; // length counter load (trigger)

    // Global registers
    private static final int STATUS = 0x15; // channel enable bits 3-0
    private static final int FRAME_CTR = 0x17; // 0x40 = 5-step, IRQ inhibit

    // Constant-volume flag: bit 4; duty = 50% (10): bits 7-6 = 0b10
    private static final int DUTY_50 = 0x80; // bits 7-6 = 10 (50% duty)
    private static final int CONSTANT_VOL = 0x10; // bit 4

    // Noise period indices for GM percussion notes (index 0-15 maps to APU period table)
    // Higher index = lower frequency noise
    private static final int[] GM_NOTE_TO_NOISE_PERIOD = buildNoisePeriodMap();

    private static int[] buildNoisePeriodMap()
    {
        int[] m = new int[128];
        java.util.Arrays.fill(m, 8); // default: mid-range noise
        // Bass drum: coarse noise
        for (int n : new int[] { 35, 36 })
            m[n] = 14;
        // Floor tom, low tom
        for (int n : new int[] { 41, 43, 45 })
            m[n] = 12;
        // Mid tom
        for (int n : new int[] { 47, 48 })
            m[n] = 10;
        // Snare
        for (int n : new int[] { 38, 40 })
            m[n] = 10;
        // Hi-hat closed
        for (int n : new int[] { 42, 44 })
            m[n] = 3;
        // Hi-hat open
        for (int n : new int[] { 46 })
            m[n] = 5;
        // Cymbal / splash / ride
        for (int n : new int[] { 49, 51, 52, 53, 55, 57, 59 })
            m[n] = 7;
        // High tom
        for (int n : new int[] { 50 })
            m[n] = 9;
        // Rim shot / side stick
        for (int n : new int[] { 37, 39 })
            m[n] = 6;
        // Cowbell, woodblock, etc.
        for (int n : new int[] { 56, 58, 60, 61, 76, 77 })
            m[n] = 2;
        return m;
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.NES_APU;
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
        // IRQ inhibit, 5-step sequencer
        w.writeNes(FRAME_CTR, 0x40);
        // Enable all four audio channels
        w.writeNes(STATUS, 0x0F);

        // CH1 pulse: constant vol 0, duty 50%, sweep off, timer 0
        w.writeNes(P1_VOL, DUTY_50 | CONSTANT_VOL);
        w.writeNes(P1_SWEEP, 0x08); // sweep off (bit 3 = reload flag clear; enable=0)
        w.writeNes(P1_TIMER_LO, 0x00);
        w.writeNes(P1_TIMER_HI, 0x08); // minimum length counter load (= 1)

        // CH2 pulse: same as CH1
        w.writeNes(P2_VOL, DUTY_50 | CONSTANT_VOL);
        w.writeNes(P2_SWEEP, 0x08);
        w.writeNes(P2_TIMER_LO, 0x00);
        w.writeNes(P2_TIMER_HI, 0x08);

        // CH3 triangle: linear counter = 0 (silence by running down)
        w.writeNes(TRI_LINEAR, 0x00);
        w.writeNes(TRI_TIMER_LO, 0x00);
        w.writeNes(TRI_TIMER_HI, 0x08);

        // CH4 noise: vol 0, period 0, no trigger
        w.writeNes(NOISE_VOL, CONSTANT_VOL);
        w.writeNes(NOISE_PERIOD, 0x00);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int vol4 = (int) Math.round(velocity * 15.0 / 127.0); // 0-15

        switch (localSlot)
        {
            case 0 -> writePulseNote(P1_VOL, P1_SWEEP, P1_TIMER_LO, P1_TIMER_HI, freq, vol4, w);
            case 1 -> writePulseNote(P2_VOL, P2_SWEEP, P2_TIMER_LO, P2_TIMER_HI, freq, vol4, w);
            case 2 -> writeTriangleNote(freq, w);
        }
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        switch (localSlot)
        {
            case 0 -> w.writeNes(P1_VOL, DUTY_50 | CONSTANT_VOL); // vol = 0
            case 1 -> w.writeNes(P2_VOL, DUTY_50 | CONSTANT_VOL);
            case 2 ->
            {
                // Silence triangle by setting linear counter to 0 (halt=0 so counter ticks down)
                w.writeNes(TRI_LINEAR, 0x00);
                w.writeNes(TRI_TIMER_HI, 0x08); // reload length=1, then it will expire
            }
        }
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
            return;

        int vol4 = (int) Math.round(velocity * 15.0 / 127.0);
        int periodIdx = note < 128 ? GM_NOTE_TO_NOISE_PERIOD[note] : 8;

        w.writeNes(NOISE_VOL, CONSTANT_VOL | vol4);
        w.writeNes(NOISE_PERIOD, periodIdx & 0x0F); // white noise (bit 7 = 0)
        w.writeNes(NOISE_LEN, 0x08); // load minimum length counter (1 step)
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < MELODIC_SLOTS; slot++)
            silenceSlot(slot, w);
        // Disable all channels
        w.writeNes(STATUS, 0x00);
    }

    private static void writePulseNote(int regVol, int regSweep, int regTimerLo, int regTimerHi,
            double freq, int vol4, VgmWriter w)
    {
        int timer = (int) Math.round(VgmWriter.NES_APU_CLOCK / (16.0 * freq)) - 1;
        timer = Math.clamp(timer, 0, 2047);

        w.writeNes(regVol, DUTY_50 | CONSTANT_VOL | vol4);
        w.writeNes(regSweep, 0x08); // sweep disabled
        w.writeNes(regTimerLo, timer & 0xFF);
        w.writeNes(regTimerHi, ((timer >> 8) & 0x07) | 0x08); // load length=1
    }

    private static void writeTriangleNote(double freq, VgmWriter w)
    {
        int timer = (int) Math.round(VgmWriter.NES_APU_CLOCK / (32.0 * freq)) - 1;
        timer = Math.clamp(timer, 0, 2047);

        // Control: halt=1, reload value=127 (keep triangle running)
        w.writeNes(TRI_LINEAR, 0xFF); // halt=1, linear counter=127
        w.writeNes(TRI_TIMER_LO, timer & 0xFF);
        w.writeNes(TRI_TIMER_HI, ((timer >> 8) & 0x07) | 0x08); // load length=1
    }
}
