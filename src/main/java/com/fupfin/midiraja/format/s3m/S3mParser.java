/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.s3m;

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
 * Parses Scream Tracker 3 (.s3m) files into an {@link S3mParseResult}.
 *
 * <p>
 * Binary layout (all multi-byte integers are little-endian):
 * <ul>
 * <li>Offset 0: 28-byte song title
 * <li>Offset 28: 0x1A (EOF marker)
 * <li>Offset 29: type (0x10 = module)
 * <li>Offset 32: OrdNum (uint16) — number of orders
 * <li>Offset 34: InsNum (uint16) — number of instruments
 * <li>Offset 36: PatNum (uint16) — number of patterns
 * <li>Offset 44: "SCRM" magic
 * <li>Offset 48: global volume (uint8)
 * <li>Offset 49: initial speed (uint8, default 6)
 * <li>Offset 50: initial tempo (uint8, default 125)
 * <li>Offset 64: 32-byte channel settings (0xFF = disabled)
 * <li>Offset 96: order list (OrdNum bytes; 0xFF = end)
 * <li>After orders: InsNum × uint16 instrument parapointers (×16 = file offset)
 * <li>After that: PatNum × uint16 pattern parapointers (×16 = file offset)
 * </ul>
 *
 * <p>
 * S3M note encoding: {@code (octave << 4) | semitone} → MIDI note = {@code octave * 12 + semitone}.
 * C5 (octave 5, note 0) = MIDI 60 (middle C).
 */
public class S3mParser
{
    private static final int ROWS_PER_PATTERN = 64;
    private static final int DEFAULT_SPEED = 6;
    private static final int DEFAULT_TEMPO = 125;
    private static final int NOTE_KEYOFF = 254;
    private static final int NOTE_NONE = 255;

    // S3M effect command numbers (A=1 … Z=26)
    private static final int FX_SET_SPEED = 1; // A
    private static final int FX_PAT_JUMP = 2; // B
    private static final int FX_PAT_BREAK = 3; // C
    private static final int FX_VOL_SLIDE = 4; // D
    private static final int FX_SET_TEMPO = 20; // T
    private static final int FX_GLOBAL_VOL = 22; // V

    public TrackerParseResult parse(File file) throws IOException
    {
        byte[] data = new FileInputStream(file).readAllBytes();
        return parseBytes(data);
    }

    /** Parse from a raw byte array (useful for testing). */
    public TrackerParseResult parseBytes(byte[] data) throws IOException
    {
        if (data.length < 96)
            throw new IOException("File too small to be a valid S3M file");

        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        String title = readAsciiTrimmed(data, 0, 28);
        int ordNum = buf.getShort(32) & 0xFFFF;
        int insNum = buf.getShort(34) & 0xFFFF;
        int patNum = buf.getShort(36) & 0xFFFF;
        int initSpeed = data[49] & 0xFF;
        int initTempo = data[50] & 0xFF;

        if (initSpeed == 0)
            initSpeed = DEFAULT_SPEED;
        if (initTempo == 0)
            initTempo = DEFAULT_TEMPO;

        // Channel settings: 0-7=left PCM, 8-15=right PCM, 0xFF=disabled
        int[] chanSettings = new int[32];
        int channelCount = 0;
        for (int i = 0; i < 32; i++)
        {
            chanSettings[i] = data[64 + i] & 0xFF;
            if (chanSettings[i] != 0xFF && chanSettings[i] < 16)
                channelCount++;
        }
        channelCount = Math.min(channelCount, 16);

        // Build active channel index map: S3M ch → sequential index
        int[] chanIndexMap = new int[32];
        java.util.Arrays.fill(chanIndexMap, -1);
        int idx = 0;
        for (int i = 0; i < 32 && idx < 16; i++)
            if (chanSettings[i] != 0xFF && chanSettings[i] < 16)
                chanIndexMap[i] = idx++;

        // Orders
        int orderOffset = 96;
        int[] orders = new int[ordNum];
        for (int i = 0; i < ordNum; i++)
            orders[i] = data[orderOffset + i] & 0xFF;

        // Instrument parapointers
        int insParaOffset = orderOffset + ordNum;
        int[] insPara = new int[insNum];
        for (int i = 0; i < insNum; i++)
            insPara[i] = (buf.getShort(insParaOffset + i * 2) & 0xFFFF) * 16;

        // Pattern parapointers
        int patParaOffset = insParaOffset + insNum * 2;
        int[] patPara = new int[patNum];
        for (int i = 0; i < patNum; i++)
            patPara[i] = (buf.getShort(patParaOffset + i * 2) & 0xFFFF) * 16;

        // Instruments
        var instruments = new ArrayList<TrackerInstrument>(insNum);
        for (int i = 0; i < insNum; i++)
        {
            int base = insPara[i];
            if (base + 80 > data.length)
            {
                instruments.add(new TrackerInstrument("", 64));
                continue;
            }
            String insName = readAsciiTrimmed(data, base + 48, 28);
            int volume = data[base + 28] & 0xFF;
            instruments.add(new TrackerInstrument(insName, volume));
        }

        var events = linearize(data, orders, ordNum, patPara, chanIndexMap, channelCount,
                initSpeed, initTempo);

        return new TrackerParseResult(title, channelCount, List.copyOf(instruments), events);
    }

    private List<TrackerEvent> linearize(byte[] data, int[] orders, int ordNum, int[] patPara,
            int[] chanIndexMap, int channelCount, int initSpeed, int initTempo)
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
                break; // end marker
            if (orderVal == 0xFE)
            {
                orderPos++;
                row = 0;
                continue;
            } // skip marker

            long key = ((long) orderPos << 16) | row;
            if (!visited.add(key))
                break;

            int patIdx = orderVal;
            if (patIdx >= patPara.length || patPara[patIdx] == 0)
            {
                orderPos++;
                row = 0;
                continue;
            }

            int patOffset = patPara[patIdx];
            if (patOffset + 2 > data.length)
            {
                orderPos++;
                row = 0;
                continue;
            }

            PatternData pat = PatternData.unpack(data, patOffset);

            int[] tempoResult = prescanTempoChanges(pat, row, speed, tempo);
            int nextSpeed = tempoResult[0];
            int nextTempo = tempoResult[1];

            emitEvents(events, pat, row, chanIndexMap, currentMicrosecond);

            speed = nextSpeed;
            tempo = nextTempo;
            long rowDuration = (long) speed * 2_500_000L / tempo;
            currentMicrosecond += rowDuration;

            int[] nav = resolveNavigation(pat, row);
            int jumpToOrder = nav[0];
            int breakToRow = nav[1];

            if (jumpToOrder >= 0 || breakToRow >= 0)
            {
                orderPos = jumpToOrder >= 0 ? jumpToOrder : orderPos + 1;
                row = breakToRow >= 0 ? Math.min(breakToRow, ROWS_PER_PATTERN - 1) : 0;
            }
            else
            {
                row++;
                if (row >= ROWS_PER_PATTERN)
                {
                    row = 0;
                    orderPos++;
                }
            }
        }

        return List.copyOf(events);
    }

    /** Scans effect columns for speed/tempo changes; returns [speed, tempo]. */
    private static int[] prescanTempoChanges(PatternData pat, int row, int speed, int tempo)
    {
        int nextSpeed = speed;
        int nextTempo = tempo;
        for (int ch = 0; ch < 32; ch++)
        {
            int fx = pat.fxCmd[row][ch];
            int pa = pat.fxPar[row][ch];
            if (fx == FX_SET_SPEED && pa > 0)
                nextSpeed = pa;
            if (fx == FX_SET_TEMPO && pa >= 32)
                nextTempo = pa;
        }
        return new int[] { nextSpeed, nextTempo };
    }

    /** Emits TrackerEvents for all active channels in the given row. */
    private static void emitEvents(List<TrackerEvent> events, PatternData pat, int row,
            int[] chanIndexMap, long currentMicrosecond)
    {
        for (int ch = 0; ch < 32; ch++)
        {
            int seqCh = chanIndexMap[ch];
            if (seqCh < 0)
                continue;

            int note = pat.notes[row][ch];
            int instr = pat.instrs[row][ch];
            int vol = pat.vols[row][ch];
            int fxCmd = pat.fxCmd[row][ch];
            int fxPar = pat.fxPar[row][ch];

            int midiNote = note == NOTE_NONE
                    ? -1
                    : note == NOTE_KEYOFF
                            ? -2
                            : s3mNoteToMidi(note);

            if (midiNote != -1 || instr != 0 || vol != -1 || fxCmd != 0)
                events.add(new TrackerEvent(currentMicrosecond, seqCh, midiNote, instr, vol,
                        fxCmd, fxPar));
        }
    }

    /**
     * Scans for pattern jump (Bxx) and pattern break (Cxx) effects.
     * Returns [jumpToOrder, breakToRow]; -1 means not set.
     */
    private static int[] resolveNavigation(PatternData pat, int row)
    {
        int jumpToOrder = -1;
        int breakToRow = -1;
        for (int ch = 0; ch < 32; ch++)
        {
            int fx = pat.fxCmd[row][ch];
            int pa = pat.fxPar[row][ch];
            if (fx == FX_PAT_JUMP)
                jumpToOrder = pa;
            if (fx == FX_PAT_BREAK)
                breakToRow = ((pa >> 4) * 10) + (pa & 0x0F);
        }
        return new int[] { jumpToOrder, breakToRow };
    }

    /**
     * Holds all per-pattern cell data for one S3M pattern.
     * Consolidates 5 parallel 2D arrays (notes/instrs/vols/fxCmd/fxPar).
     * Notes default to NOTE_NONE (255); volumes default to -1 (empty).
     */
    private static final class PatternData
    {
        final int[][] notes;
        final int[][] instrs;
        final int[][] vols;
        final int[][] fxCmd;
        final int[][] fxPar;

        PatternData()
        {
            this.notes = new int[ROWS_PER_PATTERN][32];
            this.instrs = new int[ROWS_PER_PATTERN][32];
            this.vols = new int[ROWS_PER_PATTERN][32];
            this.fxCmd = new int[ROWS_PER_PATTERN][32];
            this.fxPar = new int[ROWS_PER_PATTERN][32];
            for (int r = 0; r < ROWS_PER_PATTERN; r++)
            {
                java.util.Arrays.fill(notes[r], NOTE_NONE);
                java.util.Arrays.fill(vols[r], -1);
            }
        }

        static PatternData unpack(byte[] data, int patOffset)
        {
            var pat = new PatternData();
            int pos = patOffset + 2; // skip packed-length word
            int curRow = 0;
            while (curRow < ROWS_PER_PATTERN && pos < data.length)
            {
                int cb = data[pos++] & 0xFF;
                if (cb == 0)
                {
                    curRow++;
                    continue;
                }
                int ch = cb & 0x1F;
                int note = NOTE_NONE, instr = 0, vol = -1, fxCmd = 0, fxPar = 0;
                if ((cb & 0x20) != 0 && pos + 1 < data.length) // note + instrument
                {
                    note = data[pos++] & 0xFF;
                    instr = data[pos++] & 0xFF;
                }
                if ((cb & 0x40) != 0 && pos < data.length) // volume
                {
                    vol = data[pos++] & 0xFF;
                }
                if ((cb & 0x80) != 0 && pos + 1 < data.length) // effect
                {
                    fxCmd = data[pos++] & 0xFF;
                    fxPar = data[pos++] & 0xFF;
                }
                if (ch < 32 && curRow < ROWS_PER_PATTERN)
                {
                    pat.notes[curRow][ch] = note;
                    pat.instrs[curRow][ch] = instr;
                    pat.vols[curRow][ch] = vol;
                    pat.fxCmd[curRow][ch] = fxCmd;
                    pat.fxPar[curRow][ch] = fxPar;
                }
            }
            return pat;
        }
    }

    /**
     * Converts an S3M note byte to a MIDI note number.
     * High nibble = octave, low nibble = semitone (0=C … 11=B).
     * C5 (octave 5, semitone 0) maps to MIDI 60.
     */
    static int s3mNoteToMidi(int noteByte)
    {
        int octave = (noteByte >> 4) & 0x0F;
        int semitone = noteByte & 0x0F;
        return Math.clamp(octave * 12 + semitone, 0, 127);
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
