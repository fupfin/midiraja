/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

import static com.fupfin.midiraja.vgm.FmMidiUtil.addEvent;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import com.fupfin.midiraja.vgm.TrackRoleAssigner;

/**
 * Converts a {@link ModParseResult} into a {@link javax.sound.midi.Sequence}.
 *
 * <p>
 * Channel mapping:
 * <ul>
 * <li>MOD ch 0 → MIDI ch 0, ch 1 → MIDI ch 1, etc. up to 8 channels
 * <li>MIDI ch 9 is reserved for GM drums; MOD channels whose instrument maps to percussion
 * are routed there
 * <li>MOD ch 4+ skip channel 9 (use 4→4, 5→5, 6→6, 7→7, then 8→10, etc.)
 * </ul>
 *
 * <p>
 * Tempo: fixed 120 BPM (PPQ=480). Each row's absolute microsecond timestamp is converted
 * to MIDI ticks via {@code tick = round(microsecond × 960 / 1_000_000)}.
 */
public class ModToMidiConverter
{
    private static final int PPQ = 480;
    private static final long TICKS_PER_SECOND = 960L; // PPQ * 2 (120 BPM)
    private static final byte[] TEMPO_BYTES = { 0x07, (byte) 0xA1, 0x20 }; // 500000 µs = 120 BPM
    private static final int DRUM_CH = 9;

    private final Set<Integer> mutedChannels;

    public ModToMidiConverter()
    {
        this(Set.of());
    }

    public ModToMidiConverter(Set<Integer> mutedChannels)
    {
        this.mutedChannels = mutedChannels;
    }

    /** Converts microsecond position to MIDI tick at 120 BPM with PPQ=480. */
    static long toTick(long microsecond)
    {
        return Math.round(microsecond * (double) TICKS_PER_SECOND / 1_000_000.0);
    }

    public Sequence convert(ModParseResult parsed)
    {
        try
        {
            var sequence = new Sequence(Sequence.PPQ, PPQ);

            // Track 0: tempo + title
            var tempoTrack = sequence.createTrack();
            tempoTrack.add(new MidiEvent(new MetaMessage(0x51, TEMPO_BYTES, 3), 0));
            if (!parsed.title().isBlank())
            {
                byte[] tb = parsed.title().getBytes(StandardCharsets.UTF_8);
                tempoTrack.add(new MidiEvent(new MetaMessage(0x03, tb, tb.length), 0));
            }

            // Tracks 1-15 for MIDI channels 0-14
            var tracks = new Track[15];
            for (int i = 0; i < 15; i++)
                tracks[i] = sequence.createTrack();

            // GM drums always on ch 9
            tracks[DRUM_CH].add(new MidiEvent(
                    new ShortMessage(ShortMessage.PROGRAM_CHANGE, DRUM_CH, 0, 0), 0));

            // Determine GM program per instrument from name keywords
            var instruments = parsed.instruments();
            int[] instrGmProgram = new int[32]; // index 1-31
            boolean[] instrIsDrum = new boolean[32];
            for (int i = 1; i <= 31; i++)
            {
                if (i <= instruments.size())
                {
                    int gm = ModInstrumentMapper.mapToGmProgram(instruments.get(i - 1));
                    instrIsDrum[i] = (gm == ModInstrumentMapper.PERCUSSION);
                    instrGmProgram[i] = instrIsDrum[i] ? 0 : Math.max(gm, 0);
                }
            }

            // Per-channel state
            int maxCh = Math.min(parsed.channelCount(), 8);
            int[] midiChannel = buildMidiChannelMap(maxCh);
            int[] activeNote = new int[maxCh];
            int[] activeInstr = new int[maxCh];
            boolean[] isDrum = new boolean[maxCh];
            java.util.Arrays.fill(activeNote, -1);

            // Muted-channel sink
            var routed = tracks.clone();
            if (!mutedChannels.isEmpty())
            {
                var sinkSeq = new Sequence(Sequence.PPQ, PPQ);
                var sink = sinkSeq.createTrack();
                for (int ch : mutedChannels)
                    if (ch >= 0 && ch < routed.length)
                        routed[ch] = sink;
            }

            for (var event : parsed.events())
            {
                int modCh = event.channel();
                if (modCh >= maxCh)
                    continue;

                long tick = toTick(event.microsecond());
                int midiCh = midiChannel[modCh];
                int effect = event.effectCmd();
                int param = event.effectParam();

                // Handle instrument change
                if (event.instrument() > 0 && event.instrument() <= 31)
                {
                    int instrNum = event.instrument();
                    if (instrIsDrum[instrNum])
                    {
                        isDrum[modCh] = true;
                        midiCh = DRUM_CH;
                        midiChannel[modCh] = DRUM_CH;
                    }
                    else
                    {
                        isDrum[modCh] = false;
                        int gm = instrGmProgram[instrNum];
                        if (activeInstr[modCh] != instrNum && gm >= 0)
                        {
                            addEvent(routed[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, gm, 0, tick);
                            activeInstr[modCh] = instrNum;
                        }
                    }
                }

                // Handle new note
                if (event.period() > 0)
                {
                    int note = AmigaPeriodTable.periodToMidiNote(event.period());
                    if (note >= 0)
                    {
                        // Note off for previous note
                        if (activeNote[modCh] >= 0)
                            addEvent(routed[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[modCh], 0, tick);

                        int instrNum = event.instrument() > 0
                                ? event.instrument()
                                : (activeInstr[modCh] > 0 ? activeInstr[modCh] : 0);
                        int vol = instrNum > 0 && instrNum <= 31 ? instruments.get(instrNum - 1).volume() : 64;
                        int vel = Math.clamp(Math.round(vol / 64.0f * 127), 1, 127);

                        if (isDrum[modCh])
                        {
                            addEvent(routed[DRUM_CH], ShortMessage.NOTE_ON, DRUM_CH, note, vel, tick);
                            activeNote[modCh] = note;
                        }
                        else
                        {
                            addEvent(routed[midiCh], ShortMessage.NOTE_ON, midiCh, note, vel, tick);
                            activeNote[modCh] = note;
                        }
                    }
                }

                // Handle volume effects
                if (effect == 0xC) // Cxx: set volume
                {
                    int vol = Math.min(param, 64);
                    int cc7 = Math.clamp(Math.round(vol / 64.0f * 127), 0, 127);
                    addEvent(routed[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, cc7, tick);
                }
                else if (effect == 0xA) // Axx: volume slide
                {
                    int up = (param >> 4) & 0x0F;
                    int down = param & 0x0F;
                    // Apply slide to current volume — simplified: emit delta as CC7
                    if (up > 0 || down > 0)
                    {
                        int instrNum = activeInstr[modCh] > 0 ? activeInstr[modCh] : 0;
                        int baseVol = instrNum > 0 && instrNum <= 31 ? instruments.get(instrNum - 1).volume() : 64;
                        int newVol = Math.clamp(baseVol + up - down, 0, 64);
                        int cc7 = Math.clamp(Math.round(newVol / 64.0f * 127), 0, 127);
                        addEvent(routed[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, cc7, tick);
                    }
                }
            }

            // Assign GM programs for channels that didn't get one from instrument names
            TrackRoleAssigner.assignUnassigned(sequence);

            return sequence;
        }
        catch (InvalidMidiDataException e)
        {
            throw new IllegalStateException("Failed to create MIDI sequence", e);
        }
    }

    /**
     * Maps MOD channel indices (0-based) to MIDI channel numbers, skipping channel 9 (drums).
     * Ch 0→0, 1→1, 2→2, 3→3, 4→4, 5→5, 6→6, 7→7, 8→10, …
     */
    static int[] buildMidiChannelMap(int channelCount)
    {
        int[] map = new int[channelCount];
        int midiCh = 0;
        for (int i = 0; i < channelCount; i++)
        {
            if (midiCh == DRUM_CH)
                midiCh++;
            map[i] = midiCh++;
        }
        return map;
    }
}
