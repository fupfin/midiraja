/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Post-conversion pass that assigns GM programs based on per-segment musical role analysis.
 *
 * <p>
 * Divides the sequence into time segments (2 measures = 3840 ticks at 120 BPM) and
 * independently classifies each channel's role within that segment by comparing note
 * distributions across all active channels:
 *
 * <ul>
 * <li><b>Bass</b>: lowest median note in the segment, below C3
 * <li><b>Lead</b>: highest median note among non-bass channels
 * <li><b>Harmony</b>: all other active channels
 * </ul>
 *
 * <p>
 * Program Change is inserted at segment boundaries only when the assigned instrument
 * changes, preventing per-note thrashing while still tracking role evolution over time.
 */
public final class TrackRoleAssigner
{

    private static final int DRUM_CHANNEL = 9;
    private static final int CHANNELS = 15;
    /** 2 measures at PPQ=480, 4/4 time, 120 BPM = 2 × 4 × 480 = 3840 ticks. */
    static final int SEGMENT_TICKS = 3840;
    private static final int BASS_THRESHOLD = 50;
    private static final int SHORT_DURATION_TICKS = 200;

    // Ensemble palette for stable-channel VGMs (Sega Genesis, MSX, arcade)
    private static final int PROGRAM_LEAD = 4; // Electric Piano 1
    private static final int PROGRAM_HARMONY = 5; // Electric Piano 2
    private static final int PROGRAM_PERC_MELODY = 13; // Xylophone
    private static final int PROGRAM_BASS = 33; // Electric Bass
    private static final int PROGRAM_PERC_BASS = 36; // Slap Bass

    private TrackRoleAssigner()
    {
    }

    private static final double VOLATILE_THRESHOLD = 0.5;

    /**
     * Mutable scan state shared across all FM chip handlers in {@link #isVolatileFm}.
     * Arrays are indexed by logical channel (0-17 across ports).
     */
    private static final class FmScanState
    {
        int keyOns, patchChanges;
        final int[] prevConn = new int[18];
        final int[] prevFb = new int[18];
        final boolean[] connSet = new boolean[18];

        FmScanState()
        {
            Arrays.fill(prevConn, -1);
            Arrays.fill(prevFb, -1);
        }
    }

    /**
     * Checks if FM chip events have volatile patch parameters (connection/feedback change
     * on more than 50% of key-ons). OPL2/OPL3 game drivers typically reuse channels as a
     * note pool, changing the instrument on nearly every note.
     */
    static boolean isVolatileFm(VgmParseResult parsed)
    {
        var st = new FmScanState();
        for (var event : parsed.events())
        {
            int chip = event.chip();
            byte[] raw = event.rawData();
            if (chip == 14 || chip == 15 || chip == 16)
                scanOplPatch(st, chip, raw);
            else if (chip >= 1 && chip <= 2 || chip >= 6 && chip <= 10)
                scanOpnPatch(st, chip, raw);
            else if (chip == 5)
                scanOpmPatch(st, raw);
        }
        return st.keyOns > 0 && (double) st.patchChanges / st.keyOns > VOLATILE_THRESHOLD;
    }

    /**
     * Scans one OPL2/OPL3 register write for patch-change and key-on signals.
     * OPL3 port 1 (chip 16) uses a channel offset of 9 to distinguish its 9 channels.
     */
    private static void scanOplPatch(FmScanState st, int chip, byte[] raw)
    {
        int reg = raw[0] & 0xFF, val = raw[1] & 0xFF;
        int portOff = (chip == 16) ? 9 : 0;
        // 0xC0-0xC8: connection/feedback register — detect instrument change
        if (reg >= 0xC0 && reg <= 0xC8)
        {
            int ch = reg - 0xC0 + portOff;
            int conn = val & 0x01, fb = (val >> 1) & 0x07;
            if (st.connSet[ch] && (conn != st.prevConn[ch] || fb != st.prevFb[ch]))
                st.patchChanges++;
            st.prevConn[ch] = conn;
            st.prevFb[ch] = fb;
            st.connSet[ch] = true;
        }
        // 0xB0-0xB8 bit 5: key-on
        else if (reg >= 0xB0 && reg <= 0xB8 && (val & 0x20) != 0)
        {
            st.keyOns++;
        }
    }

    /**
     * Scans one OPN/YM2612 register write for patch-change and key-on signals.
     * Port 1 chips (2, 8, 10) use channel offset 3 so all 6 channels share one index space.
     */
    private static void scanOpnPatch(FmScanState st, int chip, byte[] raw)
    {
        int reg = raw[0] & 0xFF, val = raw[1] & 0xFF;
        // 0xB0-0xB2: algorithm/feedback register — detect instrument change
        if (reg >= 0xB0 && reg <= 0xB2)
        {
            int portOff = (chip == 2 || chip == 8 || chip == 10) ? 3 : 0;
            int ch = (reg - 0xB0) + portOff;
            int alg = val & 0x07, fb = (val >> 3) & 0x07;
            if (st.connSet[ch] && (alg != st.prevConn[ch] || fb != st.prevFb[ch]))
                st.patchChanges++;
            st.prevConn[ch] = alg;
            st.prevFb[ch] = fb;
            st.connSet[ch] = true;
        }
        // 0x28: key-on/off — upper nibble non-zero means any operator keyed on
        else if (reg == 0x28 && (val & 0xF0) != 0)
        {
            st.keyOns++;
        }
    }

    /**
     * Scans one OPM/YM2151 register write for patch-change and key-on signals.
     */
    private static void scanOpmPatch(FmScanState st, byte[] raw)
    {
        int reg = raw[0] & 0xFF, val = raw[1] & 0xFF;
        // 0x20-0x27: connection/feedback per channel
        if (reg >= 0x20 && reg <= 0x27)
        {
            int ch = reg & 0x07;
            int alg = val & 0x07, fb = (val >> 3) & 0x07;
            if (st.connSet[ch] && (alg != st.prevConn[ch] || fb != st.prevFb[ch]))
                st.patchChanges++;
            st.prevConn[ch] = alg;
            st.prevFb[ch] = fb;
            st.connSet[ch] = true;
        }
        // 0x08: key-on/off — bits 6-3 select operators; non-zero = key on
        else if (reg == 0x08 && (val & 0x78) != 0)
        {
            st.keyOns++;
        }
    }

    /**
     * Assigns the same GM program to all non-drum channels.
     * Used when FM patch volatility is high (channels used as note pool).
     */
    static void assignUniform(Sequence sequence, int program)
    {
        var tracks = sequence.getTracks();
        for (int ch = 0; ch < CHANNELS && ch + 1 < tracks.length; ch++)
        {
            if (ch == DRUM_CHANNEL)
                continue;
            removeExistingProgramChanges(tracks[ch + 1]);
            insertProgramChange(tracks[ch + 1], ch, program, 0);
        }
    }

    /**
     * Assigns programs only to channels that have no existing Program Change events.
     * FM converters (YM2612, YM2151) emit their own per-note Program Change;
     * this method fills in PSG, wavetable, and any other channels without PC.
     */
    public static void assignUnassigned(Sequence sequence)
    {
        var tracks = sequence.getTracks();
        if (tracks.length < 2)
            return;

        for (int ch = 0; ch < CHANNELS && ch + 1 < tracks.length; ch++)
        {
            if (ch == DRUM_CHANNEL)
                continue;
            if (hasProgramChange(tracks[ch + 1]))
                continue;
            if (!hasNoteOn(tracks[ch + 1], ch))
                continue; // skip empty tracks
            insertProgramChange(tracks[ch + 1], ch, 0, 0);
        }
    }

    private static boolean hasNoteOn(Track track, int ch)
    {
        for (int i = 0; i < track.size(); i++)
        {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.NOTE_ON && sm.getChannel() == ch)
                return true;
        }
        return false;
    }

    private static boolean hasProgramChange(Track track)
    {
        for (int i = 0; i < track.size(); i++)
        {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.PROGRAM_CHANGE)
                return true;
        }
        return false;
    }

    /**
     * Analyzes all tracks in segments and inserts Program Change events at segment boundaries.
     */
    static void assign(Sequence sequence)
    {
        var tracks = sequence.getTracks();
        if (tracks.length < 2)
            return;

        // Remove any existing Program Change events (except ch 9 drums)
        for (int ch = 0; ch < CHANNELS && ch + 1 < tracks.length; ch++)
        {
            if (ch != DRUM_CHANNEL)
                removeExistingProgramChanges(tracks[ch + 1]);
        }

        // Collect all NoteOn events per channel with tick and note info
        var channelNotes = collectNotes(tracks);

        // Find the end tick
        long endTick = 0;
        for (var notes : channelNotes)
        {
            if (!notes.isEmpty())
            {
                endTick = Math.max(endTick, notes.getLast().tick + notes.getLast().duration);
            }
        }
        if (endTick == 0)
            return;

        // Process each segment
        var currentProgram = new int[CHANNELS];
        Arrays.fill(currentProgram, -1);

        for (long segStart = 0; segStart < endTick; segStart += SEGMENT_TICKS)
        {
            long segEnd = segStart + SEGMENT_TICKS;
            assignSegment(tracks, channelNotes, segStart, segEnd, currentProgram);
        }
    }

    private static void assignSegment(Track[] tracks, List<NoteInfo>[] channelNotes,
            long segStart, long segEnd, int[] currentProgram)
    {
        // Compute per-channel stats for this segment
        var segStats = new SegmentStats[CHANNELS];
        for (int ch = 0; ch < CHANNELS; ch++)
        {
            segStats[ch] = analyzeSegment(channelNotes[ch], segStart, segEnd);
        }

        // Find bass: always pick the channel with the lowest median note.
        // No absolute threshold — OPL3 channels cover wide ranges so medians cluster together.
        int bassCh = -1;
        int lowestMedian = Integer.MAX_VALUE;
        for (int ch = 0; ch < CHANNELS; ch++)
        {
            if (ch == DRUM_CHANNEL || segStats[ch].noteCount == 0)
                continue;
            if (segStats[ch].medianNote < lowestMedian)
            {
                lowestMedian = segStats[ch].medianNote;
                bassCh = ch;
            }
        }

        // Find lead (highest median among non-bass)
        int leadCh = -1;
        int highestMedian = -1;
        for (int ch = 0; ch < CHANNELS; ch++)
        {
            if (ch == DRUM_CHANNEL || ch == bassCh || segStats[ch].noteCount == 0)
                continue;
            if (segStats[ch].medianNote > highestMedian)
            {
                highestMedian = segStats[ch].medianNote;
                leadCh = ch;
            }
        }
        // If only one active channel, it's lead, not bass
        if (leadCh < 0)
        {
            leadCh = bassCh;
            bassCh = -1;
        }

        // Assign programs for this segment
        for (int ch = 0; ch < CHANNELS && ch + 1 < tracks.length; ch++)
        {
            if (ch == DRUM_CHANNEL || segStats[ch].noteCount == 0)
                continue;

            int program;
            if (ch == bassCh)
            {
                program = segStats[ch].percussive ? PROGRAM_PERC_BASS : PROGRAM_BASS;
            }
            else if (ch == leadCh)
            {
                program = segStats[ch].percussive ? PROGRAM_PERC_MELODY : PROGRAM_LEAD;
            }
            else
            {
                program = segStats[ch].percussive ? PROGRAM_PERC_MELODY : PROGRAM_HARMONY;
            }

            if (program != currentProgram[ch])
            {
                insertProgramChange(tracks[ch + 1], ch, program, segStart);
                currentProgram[ch] = program;
            }
        }
    }

    private static SegmentStats analyzeSegment(List<NoteInfo> notes, long segStart, long segEnd)
    {
        var segNotes = new ArrayList<Integer>();
        long totalDuration = 0;
        int durationCount = 0;

        for (var n : notes)
        {
            // Note overlaps with segment if it starts before segEnd and ends after segStart
            if (n.tick < segEnd && n.tick + n.duration > segStart)
            {
                segNotes.add(n.note);
                totalDuration += n.duration;
                durationCount++;
            }
        }

        if (segNotes.isEmpty())
            return new SegmentStats(0, 0, false);

        segNotes.sort(Comparator.naturalOrder());
        int medianNote = segNotes.get(segNotes.size() / 2);
        long avgDuration = totalDuration / durationCount;
        boolean percussive = avgDuration < SHORT_DURATION_TICKS;

        return new SegmentStats(segNotes.size(), medianNote, percussive);
    }

    @SuppressWarnings("unchecked")
    private static List<NoteInfo>[] collectNotes(Track[] tracks)
    {
        var result = new List[CHANNELS];
        for (int ch = 0; ch < CHANNELS; ch++)
        {
            result[ch] = new ArrayList<NoteInfo>();
        }

        for (int ch = 0; ch < CHANNELS && ch + 1 < tracks.length; ch++)
        {
            if (ch == DRUM_CHANNEL)
                continue;
            var track = tracks[ch + 1];
            var activeStart = new long[128];
            Arrays.fill(activeStart, -1);

            for (int i = 0; i < track.size(); i++)
            {
                var msg = track.get(i).getMessage();
                if (!(msg instanceof ShortMessage sm) || sm.getChannel() != ch)
                    continue;

                if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0)
                {
                    activeStart[sm.getData1()] = track.get(i).getTick();
                }
                else if (sm.getCommand() == ShortMessage.NOTE_OFF
                        || (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() == 0))
                {
                    int note = sm.getData1();
                    if (activeStart[note] >= 0)
                    {
                        long duration = track.get(i).getTick() - activeStart[note];
                        result[ch].add(new NoteInfo(activeStart[note], note, Math.max(1, duration)));
                        activeStart[note] = -1;
                    }
                }
            }
        }
        return result;
    }

    private static void removeExistingProgramChanges(Track track)
    {
        for (int i = track.size() - 1; i >= 0; i--)
        {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.PROGRAM_CHANGE)
            {
                track.remove(track.get(i));
            }
        }
    }

    private static void insertProgramChange(Track track, int ch, int program, long tick)
    {
        try
        {
            track.add(new MidiEvent(
                    new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, program, 0), tick));
        }
        catch (InvalidMidiDataException e)
        {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }

    private record NoteInfo(long tick, int note, long duration)
    {
    }

    private record SegmentStats(int noteCount, int medianNote, boolean percussive)
    {
    }

}
