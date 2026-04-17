/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for the HuC6280 (PC Engine PSG).
 *
 * <p>
 * Channel layout: 6 independent wavetable channels, each with its own 32-sample × 5-bit
 * unsigned wave RAM. All 6 channels are used for melodic voices; there is no dedicated
 * percussion channel.
 *
 * <p>
 * Register map (accessed via VGM command 0xB9):
 * <ul>
 *   <li>0x00 — channel select (0-5)</li>
 *   <li>0x02 — frequency divider low byte (bits 7-0)</li>
 *   <li>0x03 — frequency divider high nibble (bits 3-0)</li>
 *   <li>0x04 — channel control: bit 7 = enable, bits 4-0 = amplitude (0-31)</li>
 *   <li>0x05 — per-channel balance: bits 7-4 = L, bits 3-0 = R</li>
 *   <li>0x06 — wave data (auto-increment pointer; 32 writes per channel)</li>
 * </ul>
 *
 * <p>
 * Frequency encoding: {@code period = round(HUC6280_CLOCK / (32 × freq_hz))}, clamped to [1, 0xFFF].
 */
final class HuC6280Handler implements ChipHandler
{
    private static final int SLOTS = 6;
    private static final int WAVE_SIZE = 32;

    // Register offsets
    private static final int REG_CH_SELECT = 0x00;
    private static final int REG_FREQ_LO   = 0x02;
    private static final int REG_FREQ_HI   = 0x03;
    private static final int REG_CONTROL   = 0x04; // bit7=enable, bits4-0=amplitude
    private static final int REG_CH_BAL    = 0x05; // per-channel balance
    private static final int REG_WAVE_DATA = 0x06;

    /** 32-sample 5-bit unsigned sine wave approximation (values 0-31). */
    private static final int[] SINE_WAVE = {
        16, 19, 22, 24, 27, 29, 30, 31,
        31, 31, 30, 29, 27, 24, 22, 19,
        16, 13, 10, 8, 5, 3, 2, 1,
        1, 1, 2, 3, 5, 8, 10, 13
    };

    @Override
    public ChipType chipType()
    {
        return ChipType.HUC6280;
    }

    @Override
    public int slotCount()
    {
        return SLOTS;
    }

    @Override
    public int percussionPriority()
    {
        return 0; // HuC6280 has no hardware noise channel suitable for percussion
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        for (int ch = 0; ch < SLOTS; ch++)
        {
            w.writeHuC(REG_CH_SELECT, ch);
            w.writeHuC(REG_CONTROL, 0x00);    // disable and reset wave pointer
            w.writeHuC(REG_CH_BAL, 0xFF);     // max left and right balance
            for (int sample : SINE_WAVE)
                w.writeHuC(REG_WAVE_DATA, sample);
        }
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int period = (int) Math.round(VgmWriter.HUC6280_CLOCK / (32.0 * freq));
        period = Math.clamp(period, 1, 0xFFF);
        int vol5 = (int) Math.round(velocity * 31.0 / 127.0);

        w.writeHuC(REG_CH_SELECT, localSlot);
        w.writeHuC(REG_CONTROL, 0x80 | vol5);         // enable with volume
        w.writeHuC(REG_FREQ_LO, period & 0xFF);
        w.writeHuC(REG_FREQ_HI, (period >> 8) & 0x0F);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        w.writeHuC(REG_CH_SELECT, localSlot);
        w.writeHuC(REG_CONTROL, 0x00); // disable
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        // No hardware percussion support
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < SLOTS; slot++)
            silenceSlot(slot, w);
    }
}
