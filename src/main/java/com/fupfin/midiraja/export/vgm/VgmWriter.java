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
import java.util.List;
import javax.sound.midi.MetaMessage;

/**
 * Writes VGM (Video Game Music) binary data to an {@link OutputStream}.
 *
 * <p>
 * Supports arbitrary combinations of {@link ChipType}s specified at construction time.
 * All data is buffered in memory; the complete VGM file (with patched header) is written
 * to the output stream on {@link #close()}, making stdout output possible.
 *
 * <p>
 * Usage: construct with desired chip list, call write* and {@link #waitSamples} for each
 * chip event, then {@link #close()} to flush the complete VGM to the target stream.
 */
public final class VgmWriter implements AutoCloseable
{
    // ── Chip clock frequencies ────────────────────────────────────────────────

    static final int AY8910_CLOCK = 1_789_772;
    static final long AY8910_CLOCK_DUAL = (long) AY8910_CLOCK | 0x80000000L;
    static final int SN76489_CLOCK = 3_579_545;
    static final int YM2413_CLOCK = 3_579_545;
    static final int K051649_CLOCK = 3_579_545;
    static final int YMF262_CLOCK = 14_318_180;
    static final int VGM_SAMPLE_RATE = 44100;

    // ── Header sizes ──────────────────────────────────────────────────────────

    /** v1.50 header (64 bytes): SN76489, YM2413 */
    private static final int HEADER_SIZE_V150 = 0x40;
    /** v1.61 header (128 bytes): AY8910, OPL3, multi-chip */
    private static final int HEADER_SIZE_V161 = 0x80;
    /** v1.70 header (192 bytes): K051649/SCC and other extended chips */
    private static final int HEADER_SIZE_V170 = 0xC0;

    // ── VGM versions ─────────────────────────────────────────────────────────

    private static final int VERSION_150 = 0x00000150;
    private static final int VERSION_161 = 0x00000161;
    private static final int VERSION_170 = 0x00000170;

    private final OutputStream out;
    private final List<ChipType> chips;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(65536);
    private long totalSamples = 0;

    public VgmWriter(OutputStream out, List<ChipType> chips)
    {
        this.out = out;
        this.chips = chips;
        // Reserve header space (all zeros initially)
        buf.writeBytes(new byte[headerSize()]);
    }

    private boolean isVersion150()
    {
        // Only a single YM2413 or a single SN76489 uses the compact v1.50 header
        return chips.size() == 1
                && (chips.get(0) == ChipType.YM2413 || chips.get(0) == ChipType.SN76489);
    }

    private boolean hasScc()
    {
        return chips.contains(ChipType.SCC);
    }

    private int headerSize()
    {
        if (isVersion150())
            return HEADER_SIZE_V150;
        if (hasScc())
            return HEADER_SIZE_V170;
        return HEADER_SIZE_V161;
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

    /** K051649 (SCC) register write (command 0xD2, port 0). */
    public void writeScc(int reg, int data)
    {
        buf.write(0xD2);
        buf.write(0x00); // port 0
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
        int version = isVersion150() ? VERSION_150 : hasScc() ? VERSION_170 : VERSION_161;
        int32Le(data, 0x08, version);

        // SN76489 clock at 0x0C
        if (chips.contains(ChipType.SN76489))
            int32Le(data, 0x0C, SN76489_CLOCK);

        // YM2413 clock at 0x10
        if (chips.contains(ChipType.YM2413))
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

        // Extended header fields for v1.61+
        if (!isVersion150())
        {
            // YMF262 (OPL3) clock at 0x5C
            if (chips.contains(ChipType.OPL3))
                int32Le(data, 0x5C, YMF262_CLOCK);

            // AY-3-8910: first chip at 0x74; if two present, second at 0x78 with dual-chip flag
            long ayCount = chips.stream().filter(c -> c == ChipType.AY8910).count();
            if (ayCount >= 1)
                int32Le(data, 0x74, AY8910_CLOCK);
            if (ayCount >= 2)
                int32Le(data, 0x78, (int) AY8910_CLOCK_DUAL);
        }

        // K051649 (SCC) clock at 0x9C — requires v1.70 header (0xC0 bytes)
        if (hasScc())
            int32Le(data, 0x9C, K051649_CLOCK);
    }

    /**
     * Converts MIDI ticks to VGM samples for the given resolution and tempo.
     *
     * @param resolution
     *            MIDI ticks per beat (PPQ from {@code Sequence.getResolution()})
     * @param usPerBeat
     *            microseconds per beat (500000 = 120 BPM)
     */
    static double ticksPerSample(int resolution, int usPerBeat)
    {
        return resolution * 1_000_000.0 / ((double) usPerBeat * VGM_SAMPLE_RATE);
    }

    /**
     * Returns microseconds-per-beat from a MIDI Set Tempo meta event, or -1 if the
     * message is not a Set Tempo event (type 0x51).
     */
    static int tempoUsPerBeat(MetaMessage meta)
    {
        if (meta.getType() != 0x51)
            return -1;
        byte[] d = meta.getData();
        if (d.length < 3)
            return -1;
        return ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
    }

    private static void int32Le(byte[] data, int offset, int value)
    {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
