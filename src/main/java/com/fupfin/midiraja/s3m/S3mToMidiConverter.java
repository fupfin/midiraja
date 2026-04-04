/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.s3m;

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
 * Converts an {@link S3mParseResult} into a {@link javax.sound.midi.Sequence}.
 *
 * <p>Channel mapping: S3M channels (sequential indices after disabled channels are removed) are
 * mapped to MIDI channels 0–14, skipping channel 9 (GM drums).
 * Instruments whose name matches a percussion keyword are routed to channel 9.
 *
 * <p>Timing: fixed 120 BPM (PPQ=480); tick = round(microsecond × 960 / 1_000_000).
 */
public class S3mToMidiConverter
{
    private static final int PPQ              = 480;
    private static final long TICKS_PER_SEC  = 960L; // PPQ * 2 (120 BPM)
    private static final byte[] TEMPO_BYTES  = {0x07, (byte) 0xA1, 0x20}; // 500000 µs
    private static final int DRUM_CH         = 9;

    private final Set<Integer> mutedChannels;

    public S3mToMidiConverter()               { this(Set.of()); }
    public S3mToMidiConverter(Set<Integer> m) { this.mutedChannels = m; }

    static long toTick(long microsecond)
    {
        return Math.round(microsecond * (double) TICKS_PER_SEC / 1_000_000.0);
    }

    public Sequence convert(S3mParseResult parsed)
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

            // Per-channel state
            int maxCh = parsed.channelCount();
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
                int seqCh = ev.channel();
                if (seqCh >= maxCh) continue;

                long tick  = toTick(ev.microsecond());
                int midiCh = midiChannel[seqCh];

                // Instrument change
                if (ev.instrument() > 0 && ev.instrument() <= insCount)
                {
                    int i = ev.instrument();
                    if (instrIsDrum[i])
                    {
                        isDrum[seqCh] = true;
                        midiCh = DRUM_CH;
                        midiChannel[seqCh] = DRUM_CH;
                    }
                    else if (activeInstr[seqCh] != i)
                    {
                        isDrum[seqCh] = false;
                        addEvent(routed[midiCh], ShortMessage.PROGRAM_CHANGE,
                                midiCh, instrGmProgram[i], 0, tick);
                        activeInstr[seqCh] = i;
                    }
                }

                // Volume column → CC7
                if (ev.volume() >= 0)
                {
                    int cc7 = Math.clamp(Math.round(ev.volume() / 64.0f * 127), 0, 127);
                    addEvent(routed[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, cc7, tick);
                }

                // Note
                int note = ev.note();
                if (note == -2) // key-off
                {
                    if (activeNote[seqCh] >= 0)
                        addEvent(routed[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[seqCh], 0, tick);
                    activeNote[seqCh] = -1;
                }
                else if (note >= 0)
                {
                    if (activeNote[seqCh] >= 0)
                        addEvent(routed[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[seqCh], 0, tick);

                    int instrIdx = ev.instrument() > 0 ? ev.instrument() : activeInstr[seqCh];
                    int baseVol  = instrIdx > 0 && instrIdx <= insCount
                            ? instruments.get(instrIdx - 1).volume() : 64;
                    int vel = Math.clamp(Math.round(baseVol / 64.0f * 127), 1, 127);

                    int destCh = isDrum[seqCh] ? DRUM_CH : midiCh;
                    addEvent(routed[destCh], ShortMessage.NOTE_ON, destCh, note, vel, tick);
                    activeNote[seqCh] = note;
                }

                // Volume slide effect (D)
                if (ev.effectCmd() == 4)
                {
                    int up   = (ev.effectParam() >> 4) & 0x0F;
                    int down = ev.effectParam() & 0x0F;
                    if (up != 0xF && down != 0xF && (up > 0 || down > 0))
                    {
                        int instrIdx = activeInstr[seqCh] > 0 ? activeInstr[seqCh] : 0;
                        int baseVol = instrIdx > 0 && instrIdx <= insCount
                                ? instruments.get(instrIdx - 1).volume() : 64;
                        int newVol = Math.clamp(baseVol + up - down, 0, 64);
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

    /** Maps S3M sequential channel indices to MIDI channels, skipping channel 9. */
    static int[] buildMidiChannelMap(int channelCount)
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
