/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * Writes VGM (Video Game Music) binary data to an {@link OutputStream}.
 *
 * <p>
 * Supports AY-3-8910, SN76489, YM2413, MSX (AY+YM2413), and OPL3 (YMF262) chip modes.
 * All data is buffered in memory; the complete VGM file (with patched header) is written
 * to the output stream on {@link #close()}, making stdout output possible.
 *
 * <p>
 * Usage: construct, call write* and {@link #waitSamples} for each chip event, then
 * {@link #close()} to flush the complete VGM to the target stream.
 */
public final class VgmWriter implements AutoCloseable
{
    // ── Chip clock frequencies ────────────────────────────────────────────────

    static final int AY8910_CLOCK = 1_789_772;
    static final long AY8910_CLOCK_DUAL = (long) AY8910_CLOCK | 0x80000000L;
    static final int SN76489_CLOCK = 3_579_545;
    static final int YM2413_CLOCK = 3_579_545;
    static final int YMF262_CLOCK = 14_318_180;
    static final int VGM_SAMPLE_RATE = 44100;

    // ── Header sizes ──────────────────────────────────────────────────────────

    /** v1.50 header (64 bytes): SN76489, YM2413 */
    private static final int HEADER_SIZE_V150 = 0x40;
    /** v1.61 header (128 bytes): AY8910, MSX, OPL3 */
    private static final int HEADER_SIZE_AY = 0x80;

    // ── VGM versions ─────────────────────────────────────────────────────────

    private static final int VERSION_150 = 0x00000150;
    private static final int VERSION_161 = 0x00000161;

    public enum ChipMode
    {
        AY8910, SN76489, YM2413, MSX, OPL3
    }

    private final OutputStream out;
    private final ChipMode mode;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(65536);
    private long totalSamples = 0;

    public VgmWriter(OutputStream out, ChipMode mode)
    {
        this.out = out;
        this.mode = mode;
        // Reserve header space (all zeros initially)
        buf.writeBytes(new byte[headerSize()]);
    }

    private int headerSize()
    {
        return switch (mode)
        {
            case SN76489, YM2413 -> HEADER_SIZE_V150;
            default -> HEADER_SIZE_AY;
        };
    }

    // ── VGM chip write commands ───────────────────────────────────────────────

    /** AY-3-8910 chip 0 register write (command 0xA0). */
    public void writeAy(int reg, int data)
    {
        buf.write(0xA0);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** AY-3-8910 chip 1 register write (command 0xA0, reg bit 7 set). */
    public void writeAy2(int reg, int data)
    {
        buf.write(0xA0);
        buf.write((reg | 0x80) & 0xFF);
        buf.write(data & 0xFF);
    }

    /** SN76489 PSG chip 0 data byte (command 0x50). */
    public void writePsg(int data)
    {
        buf.write(0x50);
        buf.write(data & 0xFF);
    }

    /** SN76489 PSG chip 1 data byte (command 0x30). */
    public void writePsg2(int data)
    {
        buf.write(0x30);
        buf.write(data & 0xFF);
    }

    /** YM2413 (OPLL) register write (command 0x51). */
    public void writeYm2413(int reg, int data)
    {
        buf.write(0x51);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** YMF262 (OPL3) bank 0 register write (command 0x5E). */
    public void writeOpl3(int reg, int data)
    {
        buf.write(0x5E);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** YMF262 (OPL3) bank 1 register write (command 0x5F). */
    public void writeOpl3Bank1(int reg, int data)
    {
        buf.write(0x5F);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /**
     * Emits wait commands for the given number of 44100 Hz samples.
     * Automatically chunks into 65535-sample segments (command 0x61 nn nn).
     */
    public void waitSamples(int samples)
    {
        if (samples <= 0)
            return;
        totalSamples += samples;
        while (samples > 0)
        {
            int chunk = Math.min(samples, 65535);
            buf.write(0x61);
            buf.write(chunk & 0xFF);
            buf.write((chunk >> 8) & 0xFF);
            samples -= chunk;
        }
    }

    /**
     * Finalises the VGM stream and writes the complete file to the output stream.
     * Patches all dynamic header fields (EOF offset, total samples, chip clocks).
     */
    @Override
    public void close()
    {
        try
        {
            buf.write(0x66); // end of sound data
            byte[] data = buf.toByteArray();
            patchHeader(data);
            out.write(data);
            out.flush();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private void patchHeader(byte[] data)
    {
        // Magic "Vgm "
        data[0] = 'V';
        data[1] = 'g';
        data[2] = 'm';
        data[3] = ' ';

        // EOF offset at 0x04 (relative to position 4, so total_size - 4)
        int32Le(data, 0x04, data.length - 4);

        // Version at 0x08
        int version = (mode == ChipMode.SN76489 || mode == ChipMode.YM2413) ? VERSION_150 : VERSION_161;
        int32Le(data, 0x08, version);

        // SN76489 clock at 0x0C
        if (mode == ChipMode.SN76489)
            int32Le(data, 0x0C, SN76489_CLOCK);

        // YM2413 clock at 0x10
        if (mode == ChipMode.YM2413 || mode == ChipMode.MSX)
            int32Le(data, 0x10, YM2413_CLOCK);

        // GD3 offset at 0x14 (0 = no tag)
        int32Le(data, 0x14, 0);

        // Total samples at 0x18
        int32Le(data, 0x18, (int) totalSamples);

        // Loop offset at 0x1C (0 = no loop)
        int32Le(data, 0x1C, 0);

        // Loop samples at 0x20
        int32Le(data, 0x20, 0);

        // Rate at 0x24 (60 Hz NTSC)
        int32Le(data, 0x24, 60);

        // VGM data offset at 0x34 (relative to position 0x34)
        int32Le(data, 0x34, headerSize() - 0x34);

        // Extended header fields for v1.61
        if (version == VERSION_161)
        {
            // AY-3-8910 clock at 0x74
            if (mode == ChipMode.AY8910)
            {
                int32Le(data, 0x74, AY8910_CLOCK);
                // Second AY chip: same clock with bit 31 set (dual-chip flag)
                int32Le(data, 0x78, (int) AY8910_CLOCK_DUAL);
            }
            else if (mode == ChipMode.MSX)
            {
                int32Le(data, 0x74, AY8910_CLOCK);
            }

            // YMF262 (OPL3) clock at 0x5C
            if (mode == ChipMode.OPL3)
                int32Le(data, 0x5C, YMF262_CLOCK);
        }
    }

    private static void int32Le(byte[] data, int offset, int value)
    {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
