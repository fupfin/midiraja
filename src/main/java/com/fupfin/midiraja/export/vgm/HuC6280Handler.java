/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.IOException;
import java.util.Arrays;

/**
 * {@link ChipHandler} for the HuC6280 (PC Engine PSG).
 *
 * <p>
 * Channel layout: channels 0-4 are melodic wavetable voices; channel 5 is reserved for DDA
 * (Direct D/A) PCM percussion. All 6 channels share a single 32-sample × 5-bit unsigned wave
 * RAM each.
 *
 * <p>
 * Register map (accessed via VGM command 0xB9):
 * <ul>
 *   <li>0x00 — channel select (0-5)</li>
 *   <li>0x01 — global amplitude: bits 7-4 = L, bits 3-0 = R (0xF = max)</li>
 *   <li>0x02 — frequency divider low byte (bits 7-0)</li>
 *   <li>0x03 — frequency divider high nibble (bits 3-0)</li>
 *   <li>0x04 — channel control: bit 7 = enable, bit 6 = DDA mode, bits 4-0 = amplitude (0-31)</li>
 *   <li>0x05 — per-channel balance: bits 7-4 = L, bits 3-0 = R</li>
 *   <li>0x06 — wave data (wavetable) / direct DAC output (DDA mode)</li>
 * </ul>
 *
 * <p>
 * Frequency encoding: {@code period = round(HUC6280_CLOCK / (32 × freq_hz))}, clamped to [1, 0xFFF].
 *
 * <p>
 * DDA percussion: channel 5 is configured in DDA mode ({@code REG_CONTROL} bit 6 = 1). Seven
 * 5-bit unsigned PCM drum samples (values 0–31, center = 16) derived from FluidR3_GM.sf3 are embedded as PCM data
 * blocks (type {@code 0x05}) at {@link #initSilence} time, then triggered via DAC stream play-block
 * commands ({@code 0x95}) on each percussion hit.
 *
 * <p>
 * DAC stream channel handling: the VGM DAC stream setup command encodes the DDA channel number
 * (5) in the {@code pp} byte. libvgm's {@code daccontrol} reads the current channel-select
 * register, switches to channel 5, writes each PCM byte to reg 0x06, then restores the previous
 * channel — so melodic channels are never disturbed by the DAC stream.
 */
final class HuC6280Handler implements ChipHandler
{
    private static final int SLOTS = 5; // channels 0-4 for melodic; channel 5 reserved for DDA
    private static final int DDA_CHANNEL = 5;
    private static final int STREAM_ID = 0;
    /** VGM chip-type byte for HuC6280 in the DAC stream setup command (VGM spec §2.2, chip table). */
    private static final int VGM_CHIP_HUC6280 = 0x1B;
    /** PCM bank type for HuC6280 data blocks (VGM PCM block type 0x05). */
    private static final int PCM_BANK_HUC6280 = 0x05;
    /** DDA sample rate: 22050 Hz gives enough bandwidth for drum synthesis on a 5-bit DAC. */
    static final int PCM_RATE = 22_050;

    // Register offsets
    private static final int REG_CH_SELECT  = 0x00;
    private static final int REG_MAIN_VOL   = 0x01; // global: bits 7-4 = L, bits 3-0 = R
    private static final int REG_FREQ_LO    = 0x02;
    private static final int REG_FREQ_HI    = 0x03;
    private static final int REG_CONTROL    = 0x04; // bit7=enable, bit6=DDA, bits4-0=amplitude
    private static final int REG_CH_BAL     = 0x05; // per-channel balance
    private static final int REG_WAVE_DATA  = 0x06;

    /**
     * GM percussion note → drum sample index (0-6), or -1 if not mapped.
     * Array is indexed by MIDI note number (0-127).
     */
    private static final int[] NOTE_TO_DRUM = buildNoteMap();
    /** 5-bit PCM drum samples loaded from classpath resources (values 0-31 stored as bytes). */
    private static final byte[][] DRUM_SAMPLES = loadDrumSamples();

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
        return 3; // PCM > FM (2) > PSG noise (1)
    }

    /**
     * Initialises all channels, embeds drum PCM data blocks, and configures channel 5 for DDA
     * playback via a DAC stream.
     */
    @Override
    public void initSilence(VgmWriter w)
    {
        w.writeHuC(REG_MAIN_VOL, 0xFF); // max global L+R volume
        for (int ch = 0; ch < SLOTS; ch++)
        {
            w.writeHuC(REG_CH_SELECT, ch);
            w.writeHuC(REG_CONTROL, 0x00);    // disable and reset wave pointer
            w.writeHuC(REG_CH_BAL, 0xFF);     // max left and right balance
            for (int sample : SINE_WAVE)
                w.writeHuC(REG_WAVE_DATA, sample);
        }

        // Embed all 7 drum samples as PCM data blocks (block index = drum type index)
        for (byte[] sample : DRUM_SAMPLES)
            w.writePcmDataBlock(PCM_BANK_HUC6280, sample);

        // Configure channel 5 for DDA playback
        w.writeHuC(REG_CH_SELECT, DDA_CHANNEL);
        w.writeHuC(REG_CH_BAL, 0xFF);
        w.writeHuC(REG_CONTROL, 0xDF); // enable (bit 7) + DDA mode (bit 6) + max amplitude (0x1F)

        // Stop any previously running stream, then configure DAC stream.
        // The 'port' byte (DDA_CHANNEL) in the setup command tells libvgm's daccontrol to
        // select channel 5 before each PCM byte write and restore the previous channel
        // afterwards — so melodic channels are never disturbed by the DAC stream.
        w.writeDacStreamStop(STREAM_ID);
        w.writeDacStreamSetup(STREAM_ID, VGM_CHIP_HUC6280, DDA_CHANNEL, REG_WAVE_DATA);
        w.writeDacStreamData(STREAM_ID, PCM_BANK_HUC6280, 0x01, 0x00);
        w.writeDacStreamFrequency(STREAM_ID, PCM_RATE);
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
        w.writeHuC(REG_CH_SELECT, DDA_CHANNEL);        // restore: DAC stream targets channel 5
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        w.writeHuC(REG_CH_SELECT, localSlot);
        w.writeHuC(REG_CONTROL, 0x00); // disable
        w.writeHuC(REG_CH_SELECT, DDA_CHANNEL); // restore: DAC stream targets channel 5
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
        {
            w.writeDacStreamStop(STREAM_ID);
            return;
        }
        int drumIdx = note >= 0 && note < NOTE_TO_DRUM.length ? NOTE_TO_DRUM[note] : -1;
        if (drumIdx < 0)
            return; // unmapped GM percussion note — ignore

        // Re-select channel 5 and ensure DDA mode in case melodic writes changed the channel
        w.writeHuC(REG_CH_SELECT, DDA_CHANNEL);
        w.writeHuC(REG_CONTROL, 0xDF); // enable + DDA + max amplitude
        w.writeDacStreamStop(STREAM_ID);
        w.writeDacStreamPlayBlock(STREAM_ID, drumIdx, 0x00);
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        w.writeDacStreamStop(STREAM_ID);
        for (int slot = 0; slot < SLOTS; slot++)
        {
            w.writeHuC(REG_CH_SELECT, slot);
            w.writeHuC(REG_CONTROL, 0x00);
        }
        w.writeHuC(REG_CH_SELECT, DDA_CHANNEL);
        w.writeHuC(REG_CONTROL, 0x00); // silence DDA channel
    }

    // ── Note map builder ──────────────────────────────────────────────────────

    private static int[] buildNoteMap()
    {
        int[] map = new int[128];
        Arrays.fill(map, -1);

        // 0 = Bass drum (GM 35, 36)
        map[35] = 0;
        map[36] = 0;

        // 1 = Snare (GM 38, 40)
        map[38] = 1;
        map[40] = 1;

        // 2 = Crash / cymbal (GM 49, 51, 52, 53, 55, 57, 59)
        for (int n : new int[] { 49, 51, 52, 53, 55, 57, 59 })
            map[n] = 2;

        // 3 = Closed hi-hat (GM 42, 44)
        map[42] = 3;
        map[44] = 3;

        // 4 = Tom (GM 41, 43, 45, 47, 48, 50)
        for (int n : new int[] { 41, 43, 45, 47, 48, 50 })
            map[n] = 4;

        // 5 = Rim shot (GM 37, 39)
        map[37] = 5;
        map[39] = 5;

        // 6 = Open hi-hat (GM 46)
        map[46] = 6;

        return map;
    }

    /**
     * Loads 5-bit PCM drum samples from classpath resources.
     *
     * <p>
     * Resources are generated from FluidR3_GM.sf3 by
     * {@code scripts/extract_drum_samples.py}.
     */
    private static byte[][] loadDrumSamples()
    {
        byte[][] samples = new byte[7][];
        for (int i = 0; i < 7; i++)
        {
            String name = "huc6280_drum_" + i + ".bin";
            try (var in = HuC6280Handler.class.getResourceAsStream(name))
            {
                if (in == null)
                    throw new IllegalStateException(name + " not found in classpath;"
                            + " run: python3 scripts/extract_drum_samples.py");
                samples[i] = in.readAllBytes();
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Failed to load " + name, e);
            }
        }
        return samples;
    }
}
