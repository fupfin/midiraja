/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import javax.sound.midi.*;

import com.fupfin.midiraja.midi.MidiTiming;
import com.fupfin.midiraja.midi.MidiUtils;

/**
 * Extracts metadata from a MIDI sequence without playing it.
 * Shared utility for {@link MidiInfoCommand} and other consumers.
 */
public class MidiMetaExtractor
{
    private record MetaTextEvent(long tick, int trackIdx, String text) {}

    public record MidiMeta(
            long durationMicroseconds,
            String title,
            String copyright,
            String lyrics,
            List<String> instrumentNames)
    {
    }

    /** Loads the sequence from {@code file} and extracts metadata. */
    public MidiMeta extract(File file) throws IOException, InvalidMidiDataException
    {
        return extractFromSequence(MidiUtils.loadSequence(file));
    }

    /** Extracts metadata from an already-loaded sequence. */
    public MidiMeta extractFromSequence(Sequence seq)
    {
        long duration = MidiTiming.computeMicroseconds(
                MidiTiming.sortedEventsWithoutEndOfTrack(seq), seq.getResolution());
        String title = Objects.requireNonNullElse(MidiUtils.extractSequenceTitle(seq), "");
        String copyright = extractFirst(seq, 0x02);
        String lyrics = extractLyrics(seq);
        List<String> instruments = extractInstruments(seq);
        return new MidiMeta(duration, title, copyright, lyrics, instruments);
    }

    private String extractFirst(Sequence seq, int type)
    {
        for (var event : collectMetaTextEvents(seq, type, true))
        {
            if (!event.text().isEmpty())
                return event.text();
        }
        return "";
    }

    private String extractLyrics(Sequence seq)
    {
        var events = collectMetaTextEvents(seq, 0x05, false);
        if (events.isEmpty())
            return "";
        events.sort(Comparator.comparingLong(MetaTextEvent::tick)
                .thenComparingInt(MetaTextEvent::trackIdx));
        var sb = new StringBuilder();
        for (int i = 0; i < events.size(); i++)
        {
            if (i > 0)
                sb.append('\n');
            sb.append(events.get(i).text());
        }
        return sb.toString();
    }

    private List<String> extractInstruments(Sequence seq)
    {
        var seen = new LinkedHashSet<String>();
        for (var event : collectMetaTextEvents(seq, 0x07, true))
        {
            String name = event.text();
            if (!name.isEmpty() && seen.stream().noneMatch(s -> s.equalsIgnoreCase(name)))
                seen.add(name);
        }
        return List.copyOf(seen);
    }

    private static List<MetaTextEvent> collectMetaTextEvents(Sequence seq, int type, boolean trim)
    {
        var events = new ArrayList<MetaTextEvent>();
        Track[] tracks = seq.getTracks();
        for (int t = 0; t < tracks.length; t++)
        {
            for (int i = 0; i < tracks[t].size(); i++)
            {
                MidiEvent event = tracks[t].get(i);
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == type)
                {
                    byte[] data = meta.getData();
                    if (data == null || data.length == 0)
                        continue;
                    String text = new String(data, StandardCharsets.UTF_8);
                    events.add(new MetaTextEvent(event.getTick(), t, trim ? text.trim() : text));
                }
            }
        }
        return events;
    }
}
