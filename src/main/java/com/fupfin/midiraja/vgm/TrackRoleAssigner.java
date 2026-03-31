/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.util.ArrayList;
import java.util.Comparator;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Post-conversion pass that analyzes MIDI tracks and assigns GM programs based on musical role.
 *
 * <p>After VGM → MIDI conversion produces raw NoteOn/NoteOff events, this class examines
 * each track's note distribution and assigns ensemble-appropriate instruments:
 *
 * <ul>
 *   <li><b>Bass</b> (lowest median note, below C3): Electric Bass / Slap Bass
 *   <li><b>Lead</b> (highest median note among non-bass): Electric Piano 1
 *   <li><b>Harmony</b> (other non-bass tracks): Electric Piano 2
 *   <li><b>Percussion</b> (channel 9): GM 0 (drums)
 * </ul>
 *
 * <p>Percussive vs sustained is determined by average note duration: short (&lt; 200 ticks)
 * triggers percussive variants (Vibraphone for melody, Slap Bass for bass).
 */
final class TrackRoleAssigner {

    private static final int DRUM_CHANNEL = 9;
    private static final int BASS_THRESHOLD = 50; // median note below this → bass role
    private static final int SHORT_DURATION_TICKS = 200; // ~208ms at 960 ticks/sec

    // Ensemble palette
    private static final int PROGRAM_ELECTRIC_PIANO1 = 4;   // lead
    private static final int PROGRAM_ELECTRIC_PIANO2 = 5;   // harmony
    private static final int PROGRAM_VIBRAPHONE      = 11;  // percussive melody
    private static final int PROGRAM_ELECTRIC_BASS   = 33;  // sustained bass
    private static final int PROGRAM_SLAP_BASS       = 36;  // percussive bass

    private TrackRoleAssigner() {}

    /**
     * Analyzes all tracks in the sequence and inserts Program Change events at tick 0.
     * Existing Program Change events are removed first.
     */
    static void assign(Sequence sequence) {
        var tracks = sequence.getTracks();
        if (tracks.length < 2) return; // track 0 is tempo only

        var stats = new TrackStats[15];
        for (int ch = 0; ch < 15 && ch + 1 < tracks.length; ch++) {
            stats[ch] = analyzeTrack(tracks[ch + 1], ch);
        }

        // Remove any existing Program Change events (except ch 9 drums)
        for (int ch = 0; ch < 15 && ch + 1 < tracks.length; ch++) {
            if (ch != DRUM_CHANNEL) {
                removeExistingProgramChanges(tracks[ch + 1]);
            }
        }

        // Find bass track (lowest median, below threshold)
        int bassChannel = -1;
        int lowestMedian = Integer.MAX_VALUE;
        for (int ch = 0; ch < 15; ch++) {
            if (ch == DRUM_CHANNEL || stats[ch] == null || stats[ch].noteCount == 0) continue;
            if (stats[ch].medianNote < BASS_THRESHOLD && stats[ch].medianNote < lowestMedian) {
                lowestMedian = stats[ch].medianNote;
                bassChannel = ch;
            }
        }

        // Find lead track (highest median among non-bass)
        int leadChannel = -1;
        int highestMedian = -1;
        for (int ch = 0; ch < 15; ch++) {
            if (ch == DRUM_CHANNEL || ch == bassChannel) continue;
            if (stats[ch] == null || stats[ch].noteCount == 0) continue;
            if (stats[ch].medianNote > highestMedian) {
                highestMedian = stats[ch].medianNote;
                leadChannel = ch;
            }
        }

        // Assign programs
        for (int ch = 0; ch < 15 && ch + 1 < tracks.length; ch++) {
            if (ch == DRUM_CHANNEL) continue;
            if (stats[ch] == null || stats[ch].noteCount == 0) continue;

            int program;
            if (ch == bassChannel) {
                program = stats[ch].percussive ? PROGRAM_SLAP_BASS : PROGRAM_ELECTRIC_BASS;
            } else if (ch == leadChannel) {
                program = stats[ch].percussive ? PROGRAM_VIBRAPHONE : PROGRAM_ELECTRIC_PIANO1;
            } else {
                // Harmony / accompaniment
                program = stats[ch].percussive ? PROGRAM_VIBRAPHONE : PROGRAM_ELECTRIC_PIANO2;
            }

            insertProgramChange(tracks[ch + 1], ch, program);
        }
    }

    private static TrackStats analyzeTrack(Track track, int ch) {
        var notes = new ArrayList<Integer>();
        var durations = new ArrayList<Long>();
        // Map active notes to their start tick for duration calculation
        var activeStart = new long[128];
        java.util.Arrays.fill(activeStart, -1);

        for (int i = 0; i < track.size(); i++) {
            var msg = track.get(i).getMessage();
            if (!(msg instanceof ShortMessage sm)) continue;
            if (sm.getChannel() != ch) continue;

            if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                int note = sm.getData1();
                notes.add(note);
                activeStart[note] = track.get(i).getTick();
            } else if (sm.getCommand() == ShortMessage.NOTE_OFF
                    || (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() == 0)) {
                int note = sm.getData1();
                if (activeStart[note] >= 0) {
                    durations.add(track.get(i).getTick() - activeStart[note]);
                    activeStart[note] = -1;
                }
            }
        }

        if (notes.isEmpty()) return new TrackStats(0, 0, false);

        notes.sort(Comparator.naturalOrder());
        int medianNote = notes.get(notes.size() / 2);

        long avgDuration = durations.isEmpty() ? Long.MAX_VALUE
                : durations.stream().mapToLong(Long::longValue).sum() / durations.size();
        boolean percussive = avgDuration < SHORT_DURATION_TICKS;

        return new TrackStats(notes.size(), medianNote, percussive);
    }

    private static void removeExistingProgramChanges(Track track) {
        // Iterate backwards to safely remove
        for (int i = track.size() - 1; i >= 0; i--) {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                track.remove(track.get(i));
            }
        }
    }

    private static void insertProgramChange(Track track, int ch, int program) {
        try {
            track.add(new MidiEvent(
                    new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, program, 0), 0));
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }

    private record TrackStats(int noteCount, int medianNote, boolean percussive) {}
}
