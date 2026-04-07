/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.it;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.fupfin.midiraja.format.tracker.TrackerEvent;
import com.fupfin.midiraja.format.tracker.TrackerInstrument;
import com.fupfin.midiraja.format.tracker.TrackerParseResult;

/**
 * Parses Impulse Tracker (.it) files into an {@link TrackerParseResult}.
 *
 * <p>
 * Global header layout (all integers little-endian):
 * <ul>
 * <li>Offset 0: "IMPM" magic
 * <li>Offset 4: Song name (26 bytes)
 * <li>Offset 32: OrdNum (uint16)
 * <li>Offset 34: InsNum (uint16)
 * <li>Offset 36: SmpNum (uint16)
 * <li>Offset 38: PatNum (uint16)
 * <li>Offset 42: Cmwt (uint16) — compatible-with tracker version
 * <li>Offset 50: InitialSpeed (uint8, default 6)
 * <li>Offset 51: InitialTempo (uint8, default 125)
 * <li>Offset 64: Channel panning table (64 bytes; 0x80 = disabled)
 * <li>Offset 128: Channel volume table (64 bytes)
 * <li>Offset 192: Order list (OrdNum bytes; 0xFF=end, 0xFE=skip)
 * <li>After orders: InsNum × uint32 instrument file offsets
 * <li>After that: SmpNum × uint32 sample file offsets
 * <li>After that: PatNum × uint32 pattern file offsets
 * </ul>
 *
 * <p>
 * Pattern compression: each row is a series of channel packets terminated by 0.
 * Each packet uses a mask-variable scheme:
 * <ul>
 * <li>Bits 0-6 of the channel-variable byte: channel (1-based)
 * <li>Bit 7: reuse last mask for this channel; otherwise read new mask byte
 * <li>Mask bits 0-3: new note / instrument / volume / effect follow in stream
 * <li>Mask bits 4-7: repeat last note / instrument / volume / effect
 * </ul>
 *
 * <p>
 * IT note encoding: 0–119 map directly to MIDI notes 0–119.
 * Note 120 = note cut; 254 = note fade — both treated as key-off.
 * Note 255 = empty (no note).
 */
public class ItParser
{
    private static final int NOTE_CUT = 120;
    private static final int NOTE_FADE = 254;
    private static final int NOTE_EMPTY = 255;
    private static final int DEFAULT_SPEED = 6;
    private static final int DEFAULT_TEMPO = 125;

    // IT effect command numbers (A=1 … Z=26)
    private static final int FX_SET_SPEED = 1; // A
    private static final int FX_PAT_JUMP = 2; // B
    private static final int FX_PAT_BREAK = 3; // C
    private static final int FX_VOL_SLIDE = 4; // D
    private static final int FX_SET_TEMPO = 20; // T

    public TrackerParseResult parse(File file) throws IOException
    {
        byte[] data = new FileInputStream(file).readAllBytes();
        return parseBytes(data);
    }

    /** Parse from a raw byte array (useful for testing). */
    public TrackerParseResult parseBytes(byte[] data) throws IOException
    {
        if (data.length < 192)
            throw new IOException("File too small to be a valid IT file");

        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Verify magic
        if (data[0] != 'I' || data[1] != 'M' || data[2] != 'P' || data[3] != 'M')
            throw new IOException("Not a valid IT file (bad magic)");

        String title = readAsciiTrimmed(data, 4, 26);
        int ordNum = buf.getShort(32) & 0xFFFF;
        int insNum = buf.getShort(34) & 0xFFFF;
        int smpNum = buf.getShort(36) & 0xFFFF;
        int patNum = buf.getShort(38) & 0xFFFF;
        int cmwt = buf.getShort(42) & 0xFFFF;
        int initSpeed = data[50] & 0xFF;
        int initTempo = data[51] & 0xFF;

        if (initSpeed == 0)
            initSpeed = DEFAULT_SPEED;
        if (initTempo == 0)
            initTempo = DEFAULT_TEMPO;

        // Active channel count from channel panning table (offset 64, 64 bytes)
        int channelCount = 0;
        for (int i = 0; i < 64; i++)
            if ((data[64 + i] & 0xFF) != 0x80)
                channelCount++;
        channelCount = Math.min(channelCount, 64);

        // Order list at offset 192
        if (192 + ordNum > data.length)
            ordNum = data.length - 192;
        int[] orders = new int[ordNum];
        for (int i = 0; i < ordNum; i++)
            orders[i] = data[192 + i] & 0xFF;

        // Offset tables
        int insOffBase = 192 + ordNum;
        int smpOffBase = insOffBase + insNum * 4;
        int patOffBase = smpOffBase + smpNum * 4;

        // Instruments
        var instruments = readInstruments(data, buf, insNum, smpNum, insOffBase, smpOffBase, cmwt);

        // Pattern offsets
        int[] patOffsets = new int[patNum];
        for (int i = 0; i < patNum; i++)
        {
            int off = patOffBase + i * 4;
            patOffsets[i] = off + 4 <= data.length ? buf.getInt(off) : 0;
        }

        var events = linearize(data, buf, orders, ordNum, patOffsets, patNum,
                channelCount, initSpeed, initTempo);

        return new TrackerParseResult(title, channelCount, List.copyOf(instruments), events);
    }

    private List<TrackerInstrument> readInstruments(byte[] data, ByteBuffer buf,
            int insNum, int smpNum, int insOffBase, int smpOffBase, int cmwt)
    {
        var instruments = new ArrayList<TrackerInstrument>(insNum);

        if (cmwt >= 0x200)
        {
            // New format: IMPI instrument headers
            // Instrument name at offset +32 (26 bytes), global volume at offset +24
            for (int i = 0; i < insNum; i++)
            {
                int off = insOffBase + i * 4;
                if (off + 4 > data.length)
                {
                    instruments.add(new TrackerInstrument("", 64));
                    continue;
                }
                int insOff = buf.getInt(off);
                if (insOff + 58 > data.length)
                {
                    instruments.add(new TrackerInstrument("", 64));
                    continue;
                }
                String name = readAsciiTrimmed(data, insOff + 32, 26);
                int vol = data[insOff + 24] & 0xFF;
                instruments.add(new TrackerInstrument(name, Math.min(vol, 64)));
            }
        }
        else
        {
            // Old format: treat samples as instruments (IMPS headers)
            // Sample name at offset +20 (26 bytes), default volume at offset +19
            for (int i = 0; i < smpNum; i++)
            {
                int off = smpOffBase + i * 4;
                if (off + 4 > data.length)
                {
                    instruments.add(new TrackerInstrument("", 64));
                    continue;
                }
                int smpOff = buf.getInt(off);
                if (smpOff + 46 > data.length)
                {
                    instruments.add(new TrackerInstrument("", 64));
                    continue;
                }
                String name = readAsciiTrimmed(data, smpOff + 20, 26);
                int vol = data[smpOff + 19] & 0xFF;
                instruments.add(new TrackerInstrument(name, Math.min(vol, 64)));
            }
        }

        return instruments;
    }

    private List<TrackerEvent> linearize(byte[] data, ByteBuffer buf, int[] orders, int ordNum,
            int[] patOffsets, int patNum, int channelCount, int initSpeed, int initTempo)
    {
        var events = new ArrayList<TrackerEvent>();
        int speed = initSpeed;
        int tempo = initTempo;
        long currentMicrosecond = 0;

        var visited = new HashSet<Long>();
        int orderPos = 0;
        int row = 0;

        while (orderPos < ordNum)
        {
            int orderVal = orders[orderPos];
            if (orderVal == 0xFF)
                break;
            if (orderVal == 0xFE)
            {
                orderPos++;
                row = 0;
                continue;
            }

            int patIdx = orderVal;
            if (patIdx >= patNum || patOffsets[patIdx] == 0)
            {
                orderPos++;
                row = 0;
                continue;
            }

            int patOff = patOffsets[patIdx];
            if (patOff + 8 > data.length)
            {
                orderPos++;
                row = 0;
                continue;
            }

            int packedSize = buf.getShort(patOff) & 0xFFFF;
            int rowCount = buf.getShort(patOff + 2) & 0xFFFF;

            if (row >= rowCount)
            {
                row = 0;
                orderPos++;
                continue;
            }

            long key = ((long) orderPos << 16) | row;
            if (!visited.add(key))
                break;

            // Unpack full pattern then process just the target row
            int[][] rowNotes = new int[rowCount][64];
            int[][] rowInstrs = new int[rowCount][64];
            int[][] rowVols = new int[rowCount][64];
            int[][] rowFxCmd = new int[rowCount][64];
            int[][] rowFxPar = new int[rowCount][64];
            for (int r = 0; r < rowCount; r++)
            {
                Arrays.fill(rowNotes[r], NOTE_EMPTY);
                Arrays.fill(rowVols[r], -1);
            }

            unpackPattern(data, patOff + 8, packedSize, rowCount,
                    rowNotes, rowInstrs, rowVols, rowFxCmd, rowFxPar);

            // Pre-scan for tempo changes (effect A = set speed, T = set tempo)
            int nextSpeed = speed;
            int nextTempo = tempo;
            for (int ch = 0; ch < 64; ch++)
            {
                int fx = rowFxCmd[row][ch];
                int fxp = rowFxPar[row][ch];
                if (fx == FX_SET_SPEED && fxp > 0)
                    nextSpeed = fxp;
                if (fx == FX_SET_TEMPO && fxp >= 32)
                    nextTempo = fxp;
            }

            // Emit events for active channels only
            for (int ch = 0; ch < 64 && ch < channelCount; ch++)
            {
                int rawNote = rowNotes[row][ch];
                int instr = rowInstrs[row][ch];
                int vol = rowVols[row][ch];
                int fxCmd = rowFxCmd[row][ch];
                int fxPar = rowFxPar[row][ch];

                int midiNote = rawNote == NOTE_EMPTY
                        ? -1
                        : (rawNote == NOTE_CUT || rawNote == NOTE_FADE)
                                ? -2
                                : Math.clamp(rawNote, 0, 127);

                // Volume column 0-64 → set volume; anything else (vol slides etc.) ignore
                int cellVol = (vol >= 0 && vol <= 64) ? vol : -1;

                if (midiNote != -1 || instr != 0 || cellVol != -1 || fxCmd != 0)
                    events.add(new TrackerEvent(currentMicrosecond, ch, midiNote, instr,
                            cellVol, fxCmd, fxPar));
            }

            speed = nextSpeed;
            tempo = nextTempo;
            long rowDuration = (long) speed * 2_500_000L / tempo;
            currentMicrosecond += rowDuration;

            // Navigation effects
            int jumpToOrder = -1;
            int breakToRow = -1;
            for (int ch = 0; ch < 64; ch++)
            {
                int fx = rowFxCmd[row][ch];
                int fxp = rowFxPar[row][ch];
                if (fx == FX_PAT_JUMP)
                    jumpToOrder = fxp;
                if (fx == FX_PAT_BREAK)
                    breakToRow = ((fxp >> 4) * 10) + (fxp & 0x0F);
            }

            if (jumpToOrder >= 0 || breakToRow >= 0)
            {
                orderPos = jumpToOrder >= 0 ? jumpToOrder : orderPos + 1;
                row = breakToRow >= 0 ? Math.min(breakToRow, rowCount - 1) : 0;
            }
            else
            {
                row++;
                if (row >= rowCount)
                {
                    row = 0;
                    orderPos++;
                }
            }
        }

        return List.copyOf(events);
    }

    /**
     * Unpacks IT pattern data into per-row/channel arrays.
     *
     * <p>
     * IT uses a mask-variable compression scheme:
     * <ol>
     * <li>Each row is a sequence of channel packets terminated by byte 0.
     * <li>Each packet: channel-variable byte → optional mask byte → optional data bytes.
     * <li>Channel-variable bits 0-6 = channel (1-based); bit 7 = reuse last mask.
     * <li>Mask bits 0-3: new note/instrument/volume/effect data follows in stream.
     * <li>Mask bits 4-7: repeat last note/instrument/volume/effect for this channel.
     * </ol>
     */
    private static void unpackPattern(byte[] data, int start, int packedSize, int rowCount,
            int[][] notes, int[][] instrs, int[][] vols, int[][] fxCmd, int[][] fxPar)
    {
        // Per-channel state for mask-variable compression
        int[] maskVar = new int[64];
        int[] lastNote = new int[64];
        Arrays.fill(lastNote, NOTE_EMPTY);
        int[] lastInstr = new int[64];
        int[] lastVol = new int[64];
        Arrays.fill(lastVol, -1);
        int[] lastFx = new int[64];
        int[] lastFxPar = new int[64];

        int pos = start;
        int end = start + packedSize;
        int curRow = 0;

        while (curRow < rowCount && pos < end)
        {
            int cv = data[pos++] & 0xFF;
            if (cv == 0)
            {
                curRow++;
                continue;
            }

            int ch = (cv & 0x7F) - 1; // 0-based
            if (ch < 0 || ch >= 64)
                continue;

            int mask;
            if ((cv & 0x80) != 0)
            {
                mask = maskVar[ch];
            }
            else
            {
                mask = data[pos++] & 0xFF;
                maskVar[ch] = mask;
            }

            int cellNote = -1;
            int cellInstr = 0;
            int cellVol = -1;
            int cellFx = 0;
            int cellFxPar = 0;

            if ((mask & 0x01) != 0 && pos < end)
            {
                cellNote = data[pos++] & 0xFF;
                lastNote[ch] = cellNote;
            }
            if ((mask & 0x02) != 0 && pos < end)
            {
                cellInstr = data[pos++] & 0xFF;
                lastInstr[ch] = cellInstr;
            }
            if ((mask & 0x04) != 0 && pos < end)
            {
                cellVol = data[pos++] & 0xFF;
                lastVol[ch] = cellVol;
            }
            if ((mask & 0x08) != 0 && pos + 1 < end)
            {
                cellFx = data[pos++] & 0xFF;
                cellFxPar = data[pos++] & 0xFF;
                lastFx[ch] = cellFx;
                lastFxPar[ch] = cellFxPar;
            }
            if ((mask & 0x10) != 0 && cellNote == -1)
                cellNote = lastNote[ch];
            if ((mask & 0x20) != 0 && cellInstr == 0)
                cellInstr = lastInstr[ch];
            if ((mask & 0x40) != 0 && cellVol == -1)
                cellVol = lastVol[ch];
            if ((mask & 0x80) != 0 && cellFx == 0)
            {
                cellFx = lastFx[ch];
                cellFxPar = lastFxPar[ch];
            }

            if (curRow < rowCount)
            {
                notes[curRow][ch] = cellNote == -1 ? NOTE_EMPTY : cellNote;
                instrs[curRow][ch] = cellInstr;
                vols[curRow][ch] = cellVol;
                fxCmd[curRow][ch] = cellFx;
                fxPar[curRow][ch] = cellFxPar;
            }
        }
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
