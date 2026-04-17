/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.util.Arrays;
import java.util.Random;

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
 * synthetic 5-bit unsigned PCM drum samples (values 0–31, center = 16) are embedded as PCM data
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
    /** Pre-generated 5-bit PCM drum samples (values 0-31 stored as bytes). */
    private static final byte[][] DRUM_SAMPLES = generateDrumSamples();

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

    // ── 5-bit PCM drum sample generators ─────────────────────────────────────
    //
    // Values are 5-bit unsigned (range 0-31, center/silence = 16).
    // Amplitude 14 gives headroom: [16-14, 16+14] = [2, 30].

    private static byte[][] generateDrumSamples()
    {
        return new byte[][] {
                bassDrum(),
                snare(),
                cymbal(),
                closedHiHat(),
                tom(),
                rimShot(),
                openHiHat()
        };
    }

    /** Encodes a double sample in [-1, 1] to a 5-bit unsigned byte (center=16). */
    private static byte pcm5(double sample, double amplitude)
    {
        return (byte) Math.clamp(16 + (int) Math.round(sample * amplitude), 0, 31);
    }

    /**
     * Bass drum: low-frequency sine sweep (80 Hz → 20 Hz) with punchy decay + brief noise attack.
     */
    private static byte[] bassDrum()
    {
        var rng = new Random(0x1A2B3C4DL);
        int n = (int) (0.20 * PCM_RATE);
        byte[] pcm = new byte[n];
        double phase = 0;
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / PCM_RATE;
            double freq = 80 * Math.pow(20.0 / 80.0, t / 0.20);
            phase += 2 * Math.PI * freq / PCM_RATE;
            double toneEnv = Math.exp(-t * 10) * (1 + 2 * Math.exp(-t * 40)) / 3.0;
            double tone = Math.sin(phase) * toneEnv;
            double noise = (rng.nextDouble() * 2.0 - 1.0) * Math.exp(-t * 150) * 0.25;
            pcm[i] = pcm5(Math.clamp(tone + noise, -1.0, 1.0), 14.0);
        }
        return pcm;
    }

    /** Snare: 380 Hz snap + 220 Hz body + noise buzz. */
    private static byte[] snare()
    {
        var rng = new Random(0x5E6F7A8BL);
        int n = (int) (0.10 * PCM_RATE);
        byte[] pcm = new byte[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / PCM_RATE;
            double snap = Math.sin(2 * Math.PI * 380 * t) * Math.exp(-t * 90);
            double body = Math.sin(2 * Math.PI * 220 * t) * Math.exp(-t * 28) * 0.5;
            double buzz = (rng.nextDouble() * 2.0 - 1.0) * Math.exp(-t * 22) * 0.8;
            double sample = Math.clamp((snap + body + buzz) / 2.3, -1.0, 1.0);
            pcm[i] = pcm5(sample, 14.0);
        }
        return pcm;
    }

    /** Crash / cymbal: broadband noise with slow decay + slight inharmonic tonal coloring. */
    private static byte[] cymbal()
    {
        var rng = new Random(0x9C0D1E2FL);
        int n = (int) (0.30 * PCM_RATE);
        byte[] pcm = new byte[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / PCM_RATE;
            double env = Math.exp(-t * 9);
            double noise = (rng.nextDouble() * 2.0 - 1.0) * 0.75;
            double tones = (Math.sin(2 * Math.PI * 230 * t)
                    + 0.6 * Math.sin(2 * Math.PI * 390 * t)) * 0.25 / 1.6;
            pcm[i] = pcm5(Math.clamp(env * (noise + tones), -1.0, 1.0), 14.0);
        }
        return pcm;
    }

    /** Closed hi-hat: short noise burst (~8 ms). */
    private static byte[] closedHiHat()
    {
        var rng = new Random(0x3F4A5B6CL);
        int n = (int) (0.04 * PCM_RATE);
        byte[] pcm = new byte[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / PCM_RATE;
            double env = Math.exp(-t * 180);
            double noise = rng.nextDouble() * 2.0 - 1.0;
            pcm[i] = pcm5(noise * env, 14.0);
        }
        return pcm;
    }

    /** Tom: mid-frequency sine sweep (150 Hz → 60 Hz) with moderate decay. */
    private static byte[] tom()
    {
        int n = (int) (0.20 * PCM_RATE);
        byte[] pcm = new byte[n];
        double phase = 0;
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / PCM_RATE;
            double freq = 150 * Math.pow(60.0 / 150.0, t / 0.20);
            phase += 2 * Math.PI * freq / PCM_RATE;
            double env = Math.exp(-t * 14) * (1 + Math.exp(-t * 30)) / 2.0;
            pcm[i] = pcm5(Math.sin(phase) * env, 14.0);
        }
        return pcm;
    }

    /** Rim shot: noise burst + inharmonic sines (320/540 Hz) with fast decay. */
    private static byte[] rimShot()
    {
        var rng = new Random(0x7D8E9FA0L);
        int n = (int) (0.05 * PCM_RATE);
        byte[] pcm = new byte[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / PCM_RATE;
            double env = Math.exp(-t * 60);
            double tones = (0.6 * Math.sin(2 * Math.PI * 320 * t)
                    + 0.45 * Math.sin(2 * Math.PI * 540 * t)) / 1.05;
            double noise = (rng.nextDouble() * 2.0 - 1.0) * 0.4;
            pcm[i] = pcm5(Math.clamp(env * (tones + noise), -1.0, 1.0), 14.0);
        }
        return pcm;
    }

    /** Open hi-hat: broadband noise with moderate decay (~90 ms). */
    private static byte[] openHiHat()
    {
        var rng = new Random(0xB1C2D3E4L);
        int n = (int) (0.20 * PCM_RATE);
        byte[] pcm = new byte[n];
        for (int i = 0; i < n; i++)
        {
            double t = (double) i / PCM_RATE;
            double env = Math.exp(-t * 10);
            double noise = rng.nextDouble() * 2.0 - 1.0;
            double shimmer = (Math.sin(2 * Math.PI * 420 * t)
                    + 0.5 * Math.sin(2 * Math.PI * 710 * t)) * 0.2 / 1.5;
            pcm[i] = pcm5(Math.clamp(env * (noise * 0.85 + shimmer), -1.0, 1.0), 14.0);
        }
        return pcm;
    }
}
