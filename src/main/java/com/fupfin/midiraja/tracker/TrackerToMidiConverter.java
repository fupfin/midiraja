/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.tracker;

import static com.fupfin.midiraja.vgm.FmMidiUtil.addEvent;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import com.fupfin.midiraja.mod.ModInstrumentMapper;
import com.fupfin.midiraja.vgm.TrackRoleAssigner;

/**
 * Converts a {@link TrackerParseResult} (S3M, XM, or IT) into a {@link Sequence}.
 *
 * <p>Channel mapping: tracker channels are mapped to MIDI channels 0–14, skipping channel 9
 * (GM drums). Instruments whose name matches a percussion keyword are routed to channel 9.
 *
 * <p>Volume slide effects: S3M and IT use effect code 4 (D); XM uses 10 (A). Both are handled.
 * When a nibble equals {@code 0xF} (fine-slide marker in S3M/IT), that nibble is skipped.
 *
 * <p>Timing: fixed 120 BPM (PPQ=480); tick = round(microsecond × 960 / 1_000_000).
 */
public class TrackerToMidiConverter
{
    private static final int PPQ              = 480;
    private static final long TICKS_PER_SEC  = 960L; // PPQ * 2 (120 BPM)
    private static final byte[] TEMPO_BYTES  = {0x07, (byte) 0xA1, 0x20}; // 500000 µs
    private static final int DRUM_CH         = 9;
    // Volume slide effect codes: S3M/IT = 4 (D), XM = 10 (A)
    private static final int FX_VOL_SLIDE_D  = 4;
    private static final int FX_VOL_SLIDE_A  = 10;

    private final Set<Integer> mutedChannels;

    public TrackerToMidiConverter()               { this(Set.of()); }
    public TrackerToMidiConverter(Set<Integer> m) { this.mutedChannels = m; }

    /** Converts a microsecond position to a MIDI tick at 120 BPM / PPQ=480. */
    public static long toTick(long microsecond)
    {
        return Math.round(microsecond * (double) TICKS_PER_SEC / 1_000_000.0);
    }

    public Sequence convert(TrackerParseResult parsed)
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

            // Tracks 1–15 for MIDI channels 0–14
            var tracks = new Track[15];
            for (int i = 0; i < 15; i++) tracks[i] = sequence.createTrack();
            tracks[DRUM_CH].add(new MidiEvent(
                    new ShortMessage(ShortMessage.PROGRAM_CHANGE, DRUM_CH, 0, 0), 0));

            // Instrument → GM program mapping
            var instruments = parsed.instruments();
            int insCount = instruments.size();
            int[] instrGmProgram = new int[insCount + 1];
            boolean[] instrIsDrum = new boolean[insCount + 1];
            for (int i = 1; i <= insCount; i++)
            {
                int gm = ModInstrumentMapper.mapNameToGmProgram(instruments.get(i - 1).name());
                instrIsDrum[i] = (gm == ModInstrumentMapper.PERCUSSION);
                instrGmProgram[i] = instrIsDrum[i] ? 0 : Math.max(gm, 0);
            }

            // Per-channel state (up to 14 melodic channels, same limit for all tracker formats)
            int maxCh = Math.min(parsed.channelCount(), 14);
            int[] midiChannel = buildMidiChannelMap(maxCh);
            int[] activeNote  = new int[maxCh];
            int[] activeInstr = new int[maxCh];
            boolean[] isDrum  = new boolean[maxCh];
            java.util.Arrays.fill(activeNote, -1);

            // Muted-channel sink
            var routed = tracks.clone();
            if (!mutedChannels.isEmpty())
            {
                var sinkSeq = new Sequence(Sequence.PPQ, PPQ);
                var sink = sinkSeq.createTrack();
                for (int ch : mutedChannels)
                    if (ch >= 0 && ch < routed.length) routed[ch] = sink;
            }

            for (var ev : parsed.events())
            {
                int trackerCh = ev.channel();
                if (trackerCh >= maxCh) continue;

                long tick  = toTick(ev.microsecond());
                int midiCh = midiChannel[trackerCh];

                // Instrument change
                if (ev.instrument() > 0 && ev.instrument() <= insCount)
                {
                    int i = ev.instrument();
                    if (instrIsDrum[i])
                    {
                        isDrum[trackerCh] = true;
                        midiCh = DRUM_CH;
                        midiChannel[trackerCh] = DRUM_CH;
                    }
                    else if (activeInstr[trackerCh] != i)
                    {
                        isDrum[trackerCh] = false;
                        addEvent(routed[midiCh], ShortMessage.PROGRAM_CHANGE,
                                midiCh, instrGmProgram[i], 0, tick);
                        activeInstr[trackerCh] = i;
                    }
                }

                // Volume column → CC7
                if (ev.volume() >= 0)
                {
                    int cc7 = Math.clamp(Math.round(ev.volume() / 64.0f * 127), 0, 127);
                    addEvent(routed[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, cc7, tick);
                }

                // Note event
                int note = ev.note();
                if (note == -2) // key-off / note cut
                {
                    if (activeNote[trackerCh] >= 0)
                        addEvent(routed[midiCh], ShortMessage.NOTE_OFF,
                                midiCh, activeNote[trackerCh], 0, tick);
                    activeNote[trackerCh] = -1;
                }
                else if (note >= 0)
                {
                    if (activeNote[trackerCh] >= 0)
                        addEvent(routed[midiCh], ShortMessage.NOTE_OFF,
                                midiCh, activeNote[trackerCh], 0, tick);

                    int instrIdx = ev.instrument() > 0 ? ev.instrument() : activeInstr[trackerCh];
                    int baseVol  = instrIdx > 0 && instrIdx <= insCount
                            ? instruments.get(instrIdx - 1).volume() : 64;
                    int vel = Math.clamp(Math.round(baseVol / 64.0f * 127), 1, 127);

                    int destCh = isDrum[trackerCh] ? DRUM_CH : midiCh;
                    addEvent(routed[destCh], ShortMessage.NOTE_ON, destCh, note, vel, tick);
                    activeNote[trackerCh] = note;
                }

                // Volume slide: S3M/IT use effect D (4), XM uses effect A (10)
                if (ev.effectCmd() == FX_VOL_SLIDE_D || ev.effectCmd() == FX_VOL_SLIDE_A)
                {
                    int up   = (ev.effectParam() >> 4) & 0x0F;
                    int down = ev.effectParam() & 0x0F;
                    // Nibble 0xF = fine-slide marker in S3M/IT; treat as no slide for that direction
                    int effectiveUp   = (up   == 0xF) ? 0 : up;
                    int effectiveDown = (down == 0xF) ? 0 : down;
                    if (effectiveUp > 0 || effectiveDown > 0)
                    {
                        int instrIdx = activeInstr[trackerCh] > 0 ? activeInstr[trackerCh] : 0;
                        int baseVol = instrIdx > 0 && instrIdx <= insCount
                                ? instruments.get(instrIdx - 1).volume() : 64;
                        int newVol = Math.clamp(baseVol + effectiveUp - effectiveDown, 0, 64);
                        int cc7 = Math.clamp(Math.round(newVol / 64.0f * 127), 0, 127);
                        addEvent(routed[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, cc7, tick);
                    }
                }
            }

            TrackRoleAssigner.assignUnassigned(sequence);
            return sequence;
        }
        catch (InvalidMidiDataException e)
        {
            throw new IllegalStateException("Failed to create MIDI sequence", e);
        }
    }

    /** Maps tracker channel indices to MIDI channels, skipping channel 9 (GM drums). */
    public static int[] buildMidiChannelMap(int channelCount)
    {
        int[] map = new int[channelCount];
        int midiCh = 0;
        for (int i = 0; i < channelCount; i++)
        {
            if (midiCh == DRUM_CH) midiCh++;
            map[i] = midiCh++;
        }
        return map;
    }
}
