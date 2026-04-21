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
    /** K051649 clock with bit 31 set → activates K052539 (SCC-I) {@code mode_plus} in libvgm. */
    static final int K051649_CLOCK_SCCI = K051649_CLOCK | 0x80000000;
    static final int YM2612_CLOCK = 7_670_454;
    static final int YMF262_CLOCK = 14_318_180;
    static final int YM3812_CLOCK = 3_579_545;
    static final int YM2608_CLOCK = 7_987_200;
    static final int YM2151_CLOCK = 3_579_545;
    static final int YM2610_CLOCK = 8_000_000;
    static final int YM2203_CLOCK = 3_993_600;
    static final int DMG_CLOCK = 4_194_304;
    static final int HUC6280_CLOCK = 3_579_545;
    static final int NES_APU_CLOCK = 1_789_773;
    static final int MSM6258_CLOCK = 8_000_000;
    static final int MSM6258_SAMPLE_RATE = 15_625; // clock / 512 divider
    static final int RF5C68_CLOCK = 12_500_000;
    static final int RF5C68_SAMPLE_RATE = 16_000;
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
        return chips.contains(ChipType.SCC) || chips.contains(ChipType.SCCI);
    }

    private boolean needsV170Header()
    {
        return hasScc() || chips.contains(ChipType.DMG) || chips.contains(ChipType.HUC6280)
                || chips.contains(ChipType.NES_APU) || chips.contains(ChipType.MSM6258);
    }

    private int headerSize()
    {
        if (isVersion150())
            return HEADER_SIZE_V150;
        if (needsV170Header())
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

    /** YM2612 (OPN2) port 0 or port 1 register write (command 0x52 / 0x53). */
    public void writeYm2612(int port, int reg, int data)
    {
        buf.write(port == 0 ? 0x52 : 0x53);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** YM2413 (OPLL) register write (command 0x51). */
    public void writeYm2413(int reg, int data)
    {
        buf.write(0x51);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /**
     * K051649 (SCC) register write (command 0xD2).
     *
     * <p>
     * Port mapping:
     * <ul>
     *   <li>0 — waveform RAM (reg 0x00-0x9F, 32 bytes × 5 channels)
     *   <li>1 — frequency dividers (reg 0-9, 2 bytes per channel lo/hi)
     *   <li>2 — volumes (reg 0-4, 1 byte per channel, bits 3-0)
     *   <li>3 — channel enable mask (reg ignored, data bits 4-0)
     * </ul>
     */
    public void writeScc(int port, int reg, int data)
    {
        buf.write(0xD2);
        buf.write(port & 0xFF);
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

    /** YM3812 (OPL2) register write (command 0x5A). */
    public void writeOpl2(int reg, int data)
    {
        buf.write(0x5A);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** YM2608 (OPNA) port 0 or port 1 register write (command 0x56 / 0x57). */
    public void writeOpna(int port, int reg, int data)
    {
        buf.write(port == 0 ? 0x56 : 0x57);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** YM2151 (OPM) register write (command 0x54). */
    public void writeOpm(int reg, int data)
    {
        buf.write(0x54);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** YM2610 (OPNB) port 0 or port 1 register write (command 0x58 / 0x59). */
    public void writeYm2610(int port, int reg, int data)
    {
        buf.write(port == 0 ? 0x58 : 0x59);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** YM2203 (OPN) register write (command 0x55). */
    public void writeOpn(int reg, int data)
    {
        buf.write(0x55);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** Game Boy DMG (LR35902) APU register write (command 0xB3). */
    public void writeDmg(int reg, int data)
    {
        buf.write(0xB3);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** HuC6280 (PC Engine PSG) register write (command 0xB9). */
    public void writeHuC(int reg, int data)
    {
        buf.write(0xB9);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** NES APU (RP2A03) register write (command 0xB4). */
    public void writeNes(int reg, int data)
    {
        buf.write(0xB4);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** OKIM6258 (MSM6258) register write (command 0xB7). */
    public void writeMsm6258(int reg, int data)
    {
        buf.write(0xB7);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /** Ricoh RF5C68 PCM register write (command 0xB0). */
    public void writeRf5c68(int reg, int data)
    {
        buf.write(0xB0);
        buf.write(reg & 0xFF);
        buf.write(data & 0xFF);
    }

    /**
     * RF5C68 wave RAM data block (command 0x67 0x66 0xC0, 16-bit addressing).
     *
     * <p>
     * Writes data directly into the RF5C68 chip's wave RAM at the given start address.
     * libvgm routes type 0xC0 to {@code cDev->romWrite}, which updates the chip's
     * actual wave RAM — unlike type 0x01 which only fills an internal DAC streaming buffer.
     *
     * <p>
     * Format: {@code [0x67][0x66][0xC0][len:4LE][startAddr:2LE][data...]},
     * where {@code len = 2 + data.length}.
     *
     * @param startAddr
     *            byte address in RF5C68 wave RAM (0x0000–0xFFFF)
     * @param data
     *            raw sign-magnitude PCM bytes to write into wave RAM
     */
    public void writeRf5c68Ram(int startAddr, byte[] data)
    {
        int len = 2 + data.length;
        buf.write(0x67);
        buf.write(0x66);
        buf.write(0xC0);
        buf.write(len & 0xFF);
        buf.write((len >> 8) & 0xFF);
        buf.write((len >> 16) & 0xFF);
        buf.write((len >> 24) & 0xFF);
        buf.write(startAddr & 0xFF);
        buf.write((startAddr >> 8) & 0xFF);
        buf.writeBytes(data);
    }

    /**
     * VGM PCM data block (command 0x67 0x66, types 0x00-0x3F).
     *
     * <p>
     * Unlike ROM data blocks (0x80-0xBF), PCM bank blocks do NOT have an 8-byte prefix.
     * The {@code dblkLen} field equals {@code data.length} directly.
     *
     * @param type
     *            PCM bank type (0x00-0x3F); 0x04 = OKIM6258 PCM data
     * @param data
     *            raw PCM bytes (OKI ADPCM nibble stream for type 0x04)
     */
    public void writePcmDataBlock(int type, byte[] data)
    {
        int len = data.length;
        buf.write(0x67);
        buf.write(0x66);
        buf.write(type & 0xFF);
        buf.write(len & 0xFF);
        buf.write((len >> 8) & 0xFF);
        buf.write((len >> 16) & 0xFF);
        buf.write((len >> 24) & 0xFF);
        buf.writeBytes(data);
    }

    /**
     * DAC stream setup (command 0x90).
     *
     * @param streamId
     *            stream identifier (0-based)
     * @param chipType
     *            VGM chip-type constant; 0x17 = OKIM6258
     * @param port
     *            chip port (0 for OKIM6258)
     * @param reg
     *            register to write stream data to (0x01 for OKIM6258 control/data)
     */
    public void writeDacStreamSetup(int streamId, int chipType, int port, int reg)
    {
        buf.write(0x90);
        buf.write(streamId & 0xFF);
        buf.write(chipType & 0xFF);
        buf.write(port & 0xFF);
        buf.write(reg & 0xFF);
    }

    /**
     * DAC stream data bank assignment (command 0x91).
     *
     * @param streamId
     *            stream identifier
     * @param bankType
     *            PCM bank type; 0x04 = OKIM6258 PCM
     * @param stepSize
     *            number of bytes to advance per sample (1 for normal mono data)
     * @param stepBase
     *            byte offset into each block before reading (0 for normal; 0/1 for L/R interleaved)
     */
    public void writeDacStreamData(int streamId, int bankType, int stepSize, int stepBase)
    {
        buf.write(0x91);
        buf.write(streamId & 0xFF);
        buf.write(bankType & 0xFF);
        buf.write(stepSize & 0xFF);
        buf.write(stepBase & 0xFF);
    }

    /**
     * DAC stream playback frequency (command 0x92).
     *
     * @param streamId
     *            stream identifier
     * @param freq
     *            playback rate in Hz (e.g. {@link #MSM6258_SAMPLE_RATE})
     */
    public void writeDacStreamFrequency(int streamId, int freq)
    {
        buf.write(0x92);
        buf.write(streamId & 0xFF);
        buf.write(freq & 0xFF);
        buf.write((freq >> 8) & 0xFF);
        buf.write((freq >> 16) & 0xFF);
        buf.write((freq >> 24) & 0xFF);
    }

    /**
     * DAC stream stop (command 0x94).
     *
     * @param streamId
     *            stream identifier to stop
     */
    public void writeDacStreamStop(int streamId)
    {
        buf.write(0x94);
        buf.write(streamId & 0xFF);
    }

    /**
     * DAC stream play single block (command 0x95).
     *
     * @param streamId
     *            stream identifier
     * @param blockIdx
     *            0-based index into the PCM data block bank
     * @param flags
     *            playback flags (0x00 = play once, no loop)
     */
    public void writeDacStreamPlayBlock(int streamId, int blockIdx, int flags)
    {
        buf.write(0x95);
        buf.write(streamId & 0xFF);
        buf.write(blockIdx & 0xFF);
        buf.write((blockIdx >> 8) & 0xFF);
        buf.write(flags & 0xFF);
    }

    /**
     * VGM ROM data block (command 0x67 0x66, types 0x80-0xBF).
     *
     * <p>
     * ROM data blocks require an 8-byte prefix before the ROM payload:
     * {@code [romTotalSize:4LE][startOffset:4LE]}. The {@code dblkLen} field in the VGM
     * stream equals {@code 8 + data.length}. The libvgm {@code Cmd_DataBlock} handler reads
     * the prefix to determine the total ROM size and the byte offset to write {@code data} into.
     *
     * @param type
     *            data block type (0x80-0xBF); e.g. 0x82 = YM2610 ADPCM-A ROM
     * @param romTotalSize
     *            full size of the target ROM (bytes); usually equals {@code data.length} for a
     *            single full-ROM write
     * @param startOffset
     *            byte offset within the ROM at which {@code data} should be written (0 for a
     *            full-ROM write)
     * @param data
     *            ROM bytes to write
     */
    public void writeRomDataBlock(int type, int romTotalSize, int startOffset, byte[] data)
    {
        int dblkLen = 8 + data.length;
        buf.write(0x67);
        buf.write(0x66);
        buf.write(type & 0xFF);
        buf.write(dblkLen & 0xFF);
        buf.write((dblkLen >> 8) & 0xFF);
        buf.write((dblkLen >> 16) & 0xFF);
        buf.write((dblkLen >> 24) & 0xFF);
        // 8-byte ROM prefix: [romTotalSize:4LE][startOffset:4LE]
        buf.write(romTotalSize & 0xFF);
        buf.write((romTotalSize >> 8) & 0xFF);
        buf.write((romTotalSize >> 16) & 0xFF);
        buf.write((romTotalSize >> 24) & 0xFF);
        buf.write(startOffset & 0xFF);
        buf.write((startOffset >> 8) & 0xFF);
        buf.write((startOffset >> 16) & 0xFF);
        buf.write((startOffset >> 24) & 0xFF);
        buf.writeBytes(data);
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
        int version = isVersion150() ? VERSION_150 : needsV170Header() ? VERSION_170 : VERSION_161;
        int32Le(data, 0x08, version);

        // SN76489 clock at 0x0C
        if (chips.contains(ChipType.SN76489))
            int32Le(data, 0x0C, SN76489_CLOCK);

        // YM2612 clock at 0x2C
        if (chips.contains(ChipType.YM2612))
            int32Le(data, 0x2C, YM2612_CLOCK);

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

            // YM3812 (OPL2) clock at 0x50
            if (chips.contains(ChipType.YM3812))
                int32Le(data, 0x50, YM3812_CLOCK);

            // YM2608 (OPNA) clock at 0x48
            if (chips.contains(ChipType.YM2608))
                int32Le(data, 0x48, YM2608_CLOCK);

            // YM2151 (OPM) clock at 0x30
            if (chips.contains(ChipType.YM2151))
                int32Le(data, 0x30, YM2151_CLOCK);

            // YM2610 (OPNB) clock at 0x4C; YM2610B uses same offset with bit 31 set
            if (chips.contains(ChipType.YM2610B))
                int32Le(data, 0x4C, YM2610_CLOCK | 0x80000000);
            else if (chips.contains(ChipType.YM2610))
                int32Le(data, 0x4C, YM2610_CLOCK);

            // RF5C68 PCM clock at 0x40
            if (chips.contains(ChipType.RF5C68))
                int32Le(data, 0x40, RF5C68_CLOCK);

            // YM2203 (OPN) clock at 0x44
            if (chips.contains(ChipType.YM2203))
                int32Le(data, 0x44, YM2203_CLOCK);

            // AY-3-8910: first chip at 0x74; if two present, second at 0x78 with dual-chip flag
            long ayCount = chips.stream().filter(c -> c == ChipType.AY8910).count();
            if (ayCount >= 1)
                int32Le(data, 0x74, AY8910_CLOCK);
            if (ayCount >= 2)
                int32Le(data, 0x78, (int) AY8910_CLOCK_DUAL);
        }

        // K051649/K052539 clock at 0x9C — requires v1.70 header (0xC0 bytes)
        // Bit 31 activates K052539 (SCC-I) mode_plus in libvgm.
        if (chips.contains(ChipType.SCCI))
            int32Le(data, 0x9C, K051649_CLOCK_SCCI);
        else if (chips.contains(ChipType.SCC))
            int32Le(data, 0x9C, K051649_CLOCK);

        // DMG (Game Boy) clock at 0x80 — requires v1.70 header (0xC0 bytes)
        if (chips.contains(ChipType.DMG))
            int32Le(data, 0x80, DMG_CLOCK);

        // HuC6280 (PC Engine) clock at 0xA4 — requires v1.70 header (0xC0 bytes)
        if (chips.contains(ChipType.HUC6280))
            int32Le(data, 0xA4, HUC6280_CLOCK);

        // NES APU (RP2A03) clock at 0x84 — requires v1.70 header (0xC0 bytes)
        if (chips.contains(ChipType.NES_APU))
            int32Le(data, 0x84, NES_APU_CLOCK);

        // OKIM6258 (MSM6258) clock at 0x90; flags at 0x94 — requires v1.70 header (0xC0 bytes)
        if (chips.contains(ChipType.MSM6258))
        {
            int32Le(data, 0x90, MSM6258_CLOCK);
            // libvgm flag layout (bits 0-1 = divider index, bit2 = ADPCM type, bit3 = output bits):
            //   dividers[] = {1024, 768, 512, 512} → index 2 (bits 1-0 = 0b10) → /512 = 15 625 Hz
            //   bit2 = 1 → MSM6258_ADPCM_4B; bit3 = 0 → 10-bit output
            data[0x94] = 0x06;
        }
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
