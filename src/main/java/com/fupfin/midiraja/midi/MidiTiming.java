/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;

public final class MidiTiming
{
    private MidiTiming()
    {
    }

    /**
     * Returns all sequence events sorted by tick, excluding End-of-Track meta events (FF 2F).
     * This avoids unbounded trailing silence when files contain malformed EOT ticks.
     */
    public static List<MidiEvent> sortedEventsWithoutEndOfTrack(Sequence sequence)
    {
        return Arrays.stream(sequence.getTracks())
                .flatMap(track -> IntStream.range(0, track.size()).mapToObj(track::get))
                .filter(event -> !isEndOfTrack(event))
                .sorted(Comparator.comparingLong(MidiEvent::getTick))
                .toList();
    }

    /**
     * Converts event ticks to absolute microseconds using tempo meta events (default 120 BPM).
     */
    public static long computeMicroseconds(List<MidiEvent> sortedEvents, int resolution)
    {
        long lastTick = 0;
        long elapsedMicros = 0;
        int usPerQuarter = 500_000; // 120 BPM default

        for (var event : sortedEvents)
        {
            long tick = event.getTick();
            if (tick > lastTick)
            {
                elapsedMicros += (long) ((tick - lastTick) * (double) usPerQuarter / resolution);
                lastTick = tick;
            }

            int tempo = extractTempoMspqn(event.getMessage().getMessage());
            if (tempo > 0)
                usPerQuarter = tempo;
        }

        return elapsedMicros;
    }

    private static boolean isEndOfTrack(MidiEvent event)
    {
        byte[] raw = event.getMessage().getMessage();
        return raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0x2F;
    }

    private static int extractTempoMspqn(byte[] msg)
    {
        if (msg.length < 6 || (msg[0] & 0xFF) != 0xFF || (msg[1] & 0xFF) != 0x51)
            return -1;
        int mspqn = ((msg[3] & 0xFF) << 16) | ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        return mspqn > 0 ? mspqn : -1;
    }
}
