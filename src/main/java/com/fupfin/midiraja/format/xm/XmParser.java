/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.xm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.fupfin.midiraja.format.tracker.TrackerEvent;
import com.fupfin.midiraja.format.tracker.TrackerInstrument;
import com.fupfin.midiraja.format.tracker.TrackerParseResult;

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
        var patterns = new PatternData[patternCount];

        int pos = patternBase;
        for (int p = 0; p < patternCount && pos < data.length; p++)
        {
            if (pos + 9 > data.length)
                break;
            int patHdrSize = buf.getInt(pos);
            int rows = buf.getShort(pos + 5) & 0xFFFF;
            int packedSize = buf.getShort(pos + 7) & 0xFFFF;
            int dataStart = pos + patHdrSize;

            patterns[p] = new PatternData(rows, channelCount);
            unpackPattern(data, dataStart, packedSize, patterns[p]);

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

        var events = linearize(orders, songLength, patterns, patternCount, channelCount,
                initSpeed, initBpm);

        return new TrackerParseResult(title, channelCount, List.copyOf(instruments), events);
    }

    private void unpackPattern(byte[] data, int start, int packedSize, PatternData pat)
    {
        int pos = start;
        int end = start + packedSize;
        for (int r = 0; r < pat.rows && pos < end; r++)
        {
            for (int c = 0; c < pat.notes[r].length && pos < end; c++)
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
                pat.notes[r][c] = note;
                pat.instrs[r][c] = instr;
                // volume column: 0x10-0x50 = volume 0-64; 0 or 0x0F = empty
                pat.vols[r][c] = (vol >= 0x10 && vol <= 0x50) ? vol - 0x10 : -1;
                pat.fxCmd[r][c] = fx;
                pat.fxPar[r][c] = fxp;
            }
        }
    }

    private List<TrackerEvent> linearize(int[] orders, int songLength, PatternData[] patterns,
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
            if (patIdx >= patternCount || patterns[patIdx] == null)
            {
                orderPos++;
                row = 0;
                continue;
            }

            PatternData pat = patterns[patIdx];
            if (pat.rows == 0)
            {
                orderPos++;
                row = 0;
                continue;
            }
            if (row >= pat.rows)
            {
                row = 0;
                orderPos++;
                continue;
            }

            long key = ((long) orderPos << 16) | row;
            if (!visited.add(key))
                break;

            // Pre-scan for tempo changes (Fxx effect) — must run before emitting events
            int[] tempoResult = prescanTempoChanges(pat, row, channelCount, speed, bpm);
            int nextSpeed = tempoResult[0];
            int nextBpm = tempoResult[1];

            emitEvents(events, pat, row, channelCount, currentMicrosecond);

            speed = nextSpeed;
            bpm = nextBpm;
            long rowDuration = (long) speed * 2_500_000L / bpm;
            currentMicrosecond += rowDuration;

            int[] nav = resolveNavigation(pat, row, channelCount);
            int jumpToOrder = nav[0];
            int breakToRow = nav[1];

            if (jumpToOrder >= 0 || breakToRow >= 0)
            {
                orderPos = jumpToOrder >= 0 ? jumpToOrder : orderPos + 1;
                row = breakToRow >= 0 ? Math.min(breakToRow, pat.rows - 1) : 0;
            }
            else
            {
                row++;
                if (row >= pat.rows)
                {
                    row = 0;
                    orderPos++;
                }
            }
        }

        return List.copyOf(events);
    }

    /**
     * Scans effect column for Fxx commands and returns the updated [speed, bpm] pair.
     * Values below 32 set speed (ticks/row); values 32+ set BPM.
     */
    private static int[] prescanTempoChanges(PatternData pat, int row, int channelCount,
            int speed, int bpm)
    {
        int nextSpeed = speed;
        int nextBpm = bpm;
        for (int ch = 0; ch < channelCount; ch++)
        {
            int fx = pat.fxCmd[row][ch];
            int fxp = pat.fxPar[row][ch];
            if (fx == FX_SPEED_BPM && fxp > 0)
            {
                if (fxp < 32)
                    nextSpeed = fxp;
                else
                    nextBpm = fxp;
            }
        }
        return new int[] { nextSpeed, nextBpm };
    }

    /** Emits TrackerEvents for all channels in the given row at the given timestamp. */
    private static void emitEvents(List<TrackerEvent> events, PatternData pat, int row,
            int channelCount, long currentMicrosecond)
    {
        for (int ch = 0; ch < channelCount; ch++)
        {
            int xmNote = pat.notes[row][ch];
            int instr = pat.instrs[row][ch];
            int vol = pat.vols[row][ch];
            int fx = pat.fxCmd[row][ch];
            int fxp = pat.fxPar[row][ch];

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
    }

    /**
     * Scans for Bxx (pattern jump) and Dxx (pattern break) effects.
     * Returns [jumpToOrder, breakToRow]; -1 means not set.
     */
    private static int[] resolveNavigation(PatternData pat, int row, int channelCount)
    {
        int jumpToOrder = -1;
        int breakToRow = -1;
        for (int ch = 0; ch < channelCount; ch++)
        {
            int fx = pat.fxCmd[row][ch];
            int fxp = pat.fxPar[row][ch];
            if (fx == FX_PAT_JUMP)
                jumpToOrder = fxp;
            if (fx == FX_PAT_BREAK)
                breakToRow = ((fxp >> 4) * 10) + (fxp & 0x0F);
        }
        return new int[] { jumpToOrder, breakToRow };
    }

    /**
     * Holds all per-pattern cell data for one XM pattern.
     * Consolidates 5 parallel 2D arrays (notes/instrs/vols/fxCmd/fxPar) + row count.
     * Volume is initialized to -1 (empty) for all cells.
     */
    private static final class PatternData
    {
        final int rows;
        final int[][] notes;
        final int[][] instrs;
        final int[][] vols;
        final int[][] fxCmd;
        final int[][] fxPar;

        PatternData(int rows, int channels)
        {
            this.rows = rows;
            this.notes = new int[rows][channels];
            this.instrs = new int[rows][channels];
            this.vols = new int[rows][channels];
            this.fxCmd = new int[rows][channels];
            this.fxPar = new int[rows][channels];
            // XM volume column: -1 = empty (no explicit volume set)
            for (int r = 0; r < rows; r++)
                java.util.Arrays.fill(vols[r], -1);
        }
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
