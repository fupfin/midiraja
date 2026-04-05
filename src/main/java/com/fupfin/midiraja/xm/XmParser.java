/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.xm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.fupfin.midiraja.tracker.TrackerEvent;
import com.fupfin.midiraja.tracker.TrackerInstrument;
import com.fupfin.midiraja.tracker.TrackerParseResult;

/**
 * Parses FastTracker 2 XM files into an {@link TrackerParseResult}.
 *
 * <p>
 * Global header layout (all integers little-endian):
 * <ul>
 * <li>Offset 0: "Extended Module: " (17 bytes magic)
 * <li>Offset 17: Module name (20 bytes)
 * <li>Offset 37: 0x1A
 * <li>Offset 38: Tracker name (20 bytes)
 * <li>Offset 58: Version (uint16)
 * <li>Offset 60: Header size (uint32) — size from here to end of order table
 * <li>Offset 64: Song length (uint16)
 * <li>Offset 66: Restart position (uint16)
 * <li>Offset 68: Channels (uint16)
 * <li>Offset 70: Patterns (uint16)
 * <li>Offset 72: Instruments (uint16)
 * <li>Offset 74: Flags (uint16) — bit 0: linear frequency table
 * <li>Offset 76: Default tempo / speed (uint16, ticks per row)
 * <li>Offset 78: Default BPM (uint16)
 * <li>Offset 80: Order table (256 bytes)
 * </ul>
 *
 * <p>
 * XM note encoding: value 1–96 maps to MIDI 12–107
 * ({@code midiNote = xmNote + 11}). Value 97 = key-off.
 */
public class XmParser
{
    private static final int NOTE_KEYOFF = 97;
    private static final int DEFAULT_BPM = 125;
    private static final int DEFAULT_SPEED = 6;

    // Effect command constants
    private static final int FX_VOL_SLIDE = 0x0A;
    private static final int FX_PAT_JUMP = 0x0B;
    private static final int FX_SET_VOL = 0x0C;
    private static final int FX_PAT_BREAK = 0x0D;
    private static final int FX_SPEED_BPM = 0x0F;

    public TrackerParseResult parse(File file) throws IOException
    {
        byte[] data = new FileInputStream(file).readAllBytes();
        return parseBytes(data);
    }

    /** Parse from a raw byte array (useful for testing). */
    public TrackerParseResult parseBytes(byte[] data) throws IOException
    {
        if (data.length < 80)
            throw new IOException("File too small to be a valid XM file");

        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Verify magic
        String magic = new String(data, 0, 17, StandardCharsets.US_ASCII);
        if (!magic.equals("Extended Module: "))
            throw new IOException("Not a valid XM file (bad magic)");

        String title = readAsciiTrimmed(data, 17, 20);
        int headerSize = buf.getInt(60);
        int songLength = buf.getShort(64) & 0xFFFF;
        int channelCount = buf.getShort(68) & 0xFFFF;
        int patternCount = buf.getShort(70) & 0xFFFF;
        int insCount = buf.getShort(72) & 0xFFFF;
        int initSpeed = buf.getShort(76) & 0xFFFF;
        int initBpm = buf.getShort(78) & 0xFFFF;

        if (initSpeed == 0)
            initSpeed = DEFAULT_SPEED;
        if (initBpm == 0)
            initBpm = DEFAULT_BPM;

        // Order table
        int[] orders = new int[songLength];
        for (int i = 0; i < songLength; i++)
            orders[i] = data[80 + i] & 0xFF;

        // Patterns start after header
        int patternBase = 60 + headerSize;

        // Read all pattern data
        int[][][] patternNotes = new int[patternCount][][];
        int[][][] patternInstrs = new int[patternCount][][];
        int[][][] patternVols = new int[patternCount][][];
        int[][][] patternFxCmd = new int[patternCount][][];
        int[][][] patternFxPar = new int[patternCount][][];
        int[] patternRows = new int[patternCount];

        int pos = patternBase;
        for (int p = 0; p < patternCount && pos < data.length; p++)
        {
            if (pos + 9 > data.length)
                break;
            int patHdrSize = buf.getInt(pos);
            int rows = buf.getShort(pos + 5) & 0xFFFF;
            int packedSize = buf.getShort(pos + 7) & 0xFFFF;
            int dataStart = pos + patHdrSize;

            patternRows[p] = rows;
            patternNotes[p] = new int[rows][channelCount];
            patternInstrs[p] = new int[rows][channelCount];
            patternVols[p] = new int[rows][channelCount];
            patternFxCmd[p] = new int[rows][channelCount];
            patternFxPar[p] = new int[rows][channelCount];
            for (int r = 0; r < rows; r++)
            {
                for (int c = 0; c < channelCount; c++)
                    patternVols[p][r][c] = -1;
            }

            unpackPattern(data, dataStart, packedSize, rows, channelCount,
                    patternNotes[p], patternInstrs[p], patternVols[p],
                    patternFxCmd[p], patternFxPar[p]);

            pos = dataStart + packedSize;
        }

        // Read instruments
        var instruments = new ArrayList<TrackerInstrument>(insCount);
        for (int i = 0; i < insCount && pos < data.length; i++)
        {
            if (pos + 29 > data.length)
            {
                instruments.add(new TrackerInstrument("", 64));
                continue;
            }
            int insHdrSize = buf.getInt(pos);
            String insName = readAsciiTrimmed(data, pos + 4, 22);
            int numSamples = pos + 29 < data.length ? (buf.getShort(pos + 27) & 0xFFFF) : 0;
            int sampleVol = 64;
            if (numSamples > 0 && pos + 263 <= data.length)
            {
                // First sample header is at pos + insHdrSize
                int sampleBase = pos + insHdrSize;
                if (sampleBase + 14 <= data.length)
                    sampleVol = data[sampleBase + 12] & 0xFF;
            }
            instruments.add(new TrackerInstrument(insName, Math.min(sampleVol, 64)));

            // Skip past instrument header + all sample headers + all sample data
            int skipSize = insHdrSize;
            if (numSamples > 0 && pos + insHdrSize + 4 <= data.length)
            {
                // Calculate total sample data size to skip
                // Each sample header is 40 bytes; skip headers then data
                int sampleDataTotal = 0;
                for (int s = 0; s < numSamples; s++)
                {
                    int sh = pos + insHdrSize + s * 40;
                    if (sh + 4 <= data.length)
                        sampleDataTotal += buf.getInt(sh);
                }
                skipSize = insHdrSize + numSamples * 40 + sampleDataTotal;
            }
            pos += skipSize;
        }

        var events = linearize(orders, songLength, patternRows, patternNotes, patternInstrs,
                patternVols, patternFxCmd, patternFxPar, patternCount, channelCount,
                initSpeed, initBpm);

        return new TrackerParseResult(title, channelCount, List.copyOf(instruments), events);
    }

    private void unpackPattern(byte[] data, int start, int packedSize, int rows, int channels,
            int[][] notes, int[][] instrs, int[][] vols, int[][] fxCmd, int[][] fxPar)
    {
        int pos = start;
        int end = start + packedSize;
        for (int r = 0; r < rows && pos < end; r++)
        {
            for (int c = 0; c < channels && pos < end; c++)
            {
                int first = data[pos++] & 0xFF;
                int note = 0, instr = 0, vol = -1, fx = 0, fxp = 0;
                if ((first & 0x80) != 0) // compressed
                {
                    if ((first & 0x01) != 0 && pos < end)
                        note = data[pos++] & 0xFF;
                    if ((first & 0x02) != 0 && pos < end)
                        instr = data[pos++] & 0xFF;
                    if ((first & 0x04) != 0 && pos < end)
                        vol = data[pos++] & 0xFF;
                    if ((first & 0x08) != 0 && pos < end)
                        fx = data[pos++] & 0xFF;
                    if ((first & 0x10) != 0 && pos < end)
                        fxp = data[pos++] & 0xFF;
                }
                else // uncompressed: first byte is the note
                {
                    note = first;
                    if (pos < end)
                        instr = data[pos++] & 0xFF;
                    if (pos < end)
                        vol = data[pos++] & 0xFF;
                    if (pos < end)
                        fx = data[pos++] & 0xFF;
                    if (pos < end)
                        fxp = data[pos++] & 0xFF;
                }
                notes[r][c] = note;
                instrs[r][c] = instr;
                // volume column: 0x10-0x50 = volume 0-64; 0 or 0x0F = empty
                vols[r][c] = (vol >= 0x10 && vol <= 0x50) ? vol - 0x10 : -1;
                fxCmd[r][c] = fx;
                fxPar[r][c] = fxp;
            }
        }
    }

    private List<TrackerEvent> linearize(int[] orders, int songLength, int[] patternRows,
            int[][][] notes, int[][][] instrs, int[][][] vols,
            int[][][] fxCmd, int[][][] fxPar,
            int patternCount, int channelCount, int initSpeed, int initBpm)
    {
        var events = new ArrayList<TrackerEvent>();
        int speed = initSpeed;
        int bpm = initBpm;
        long currentMicrosecond = 0;

        var visited = new HashSet<Long>();
        int orderPos = 0;
        int row = 0;

        while (orderPos < songLength)
        {
            int patIdx = orders[orderPos];
            if (patIdx >= patternCount)
            {
                orderPos++;
                row = 0;
                continue;
            }

            int rows = patternRows[patIdx];
            if (rows == 0)
            {
                orderPos++;
                row = 0;
                continue;
            }
            if (row >= rows)
            {
                row = 0;
                orderPos++;
                continue;
            }

            long key = ((long) orderPos << 16) | row;
            if (!visited.add(key))
                break;

            // Pre-scan for tempo changes (Fxx)
            int nextSpeed = speed;
            int nextBpm = bpm;
            for (int ch = 0; ch < channelCount; ch++)
            {
                int fx = fxCmd[patIdx][row][ch];
                int fxp = fxPar[patIdx][row][ch];
                if (fx == FX_SPEED_BPM && fxp > 0)
                {
                    if (fxp < 32)
                        nextSpeed = fxp;
                    else
                        nextBpm = fxp;
                }
            }

            // Emit events
            for (int ch = 0; ch < channelCount; ch++)
            {
                int xmNote = notes[patIdx][row][ch];
                int instr = instrs[patIdx][row][ch];
                int vol = vols[patIdx][row][ch];
                int fx = fxCmd[patIdx][row][ch];
                int fxp = fxPar[patIdx][row][ch];

                int midiNote = xmNote == 0
                        ? -1
                        : xmNote == NOTE_KEYOFF
                                ? -2
                                : Math.clamp(xmNote + 11, 0, 127);

                // Volume set from effect Cxx overrides column
                int effectVol = (fx == FX_SET_VOL) ? Math.min(fxp, 64) : -1;
                int finalVol = effectVol >= 0 ? effectVol : vol;

                if (midiNote != -1 || instr != 0 || finalVol != -1 || fx != 0)
                    events.add(new TrackerEvent(currentMicrosecond, ch, midiNote, instr,
                            finalVol, fx, fxp));
            }

            speed = nextSpeed;
            bpm = nextBpm;
            long rowDuration = (long) speed * 2_500_000L / bpm;
            currentMicrosecond += rowDuration;

            // Navigation
            int jumpToOrder = -1;
            int breakToRow = -1;
            for (int ch = 0; ch < channelCount; ch++)
            {
                int fx = fxCmd[patIdx][row][ch];
                int fxp = fxPar[patIdx][row][ch];
                if (fx == FX_PAT_JUMP)
                    jumpToOrder = fxp;
                if (fx == FX_PAT_BREAK)
                    breakToRow = ((fxp >> 4) * 10) + (fxp & 0x0F);
            }

            if (jumpToOrder >= 0 || breakToRow >= 0)
            {
                orderPos = jumpToOrder >= 0 ? jumpToOrder : orderPos + 1;
                row = breakToRow >= 0 ? Math.min(breakToRow, rows - 1) : 0;
            }
            else
            {
                row++;
                if (row >= rows)
                {
                    row = 0;
                    orderPos++;
                }
            }
        }

        return List.copyOf(events);
    }

    /**
     * Converts an XM note value (1–96) to a MIDI note number.
     * XM note 1 = C0 = MIDI 12; XM note 49 = C4 = MIDI 60 (middle C).
     */
    static int xmNoteToMidi(int xmNote)
    {
        return Math.clamp(xmNote + 11, 0, 127);
    }

    private static String readAsciiTrimmed(byte[] data, int offset, int maxLen)
    {
        int end = offset;
        for (int i = offset; i < offset + maxLen && i < data.length; i++)
            if (data[i] != 0)
                end = i + 1;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }
}
