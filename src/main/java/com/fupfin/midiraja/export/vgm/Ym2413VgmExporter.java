/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts a MIDI {@link Sequence} to YM2413 (OPLL) VGM format.
 *
 * <p>
 * Supports 6 melodic FM channels plus rhythm mode (5 rhythm instruments) when MIDI channel 9
 * is active. GM program numbers are mapped to YM2413 built-in patches.
 * Voice stealing evicts the highest-indexed active channel.
 */
public final class Ym2413VgmExporter
{
    private static final int MELODIC_SLOTS = 6;
    private static final int RHYTHM_SLOTS = 5; // BD, SD, TOM, CYM, HH
    private static final int TOTAL_SLOTS = MELODIC_SLOTS + RHYTHM_SLOTS;

    /**
     * GM program (0-based) to YM2413 built-in instrument (1-15, 0=custom).
     * Instruments 1-15 are built into the YM2413; 0 uses the user-defined patch.
     */
    // @formatter:off
    private static final int[] GM_TO_YM2413 = {
        // Piano
        1, 1, 1, 1, 1, 1, 1, 1,
        // Chromatic Perc
        11, 11, 11, 11, 11, 11, 11, 11,
        // Organ
        5, 5, 5, 5, 5, 5, 5, 5,
        // Guitar
        6, 6, 6, 6, 6, 6, 6, 6,
        // Bass
        2, 2, 2, 2, 2, 2, 2, 2,
        // Strings
        4, 4, 4, 4, 4, 4, 4, 4,
        // Ensemble
        4, 4, 4, 4, 4, 4, 4, 4,
        // Brass
        3, 3, 3, 3, 3, 3, 3, 3,
        // Reed
        8, 8, 8, 8, 8, 8, 8, 8,
        // Pipe
        7, 7, 7, 7, 7, 7, 7, 7,
        // Synth Lead
        1, 1, 1, 1, 1, 1, 1, 1,
        // Synth Pad
        4, 4, 4, 4, 4, 4, 4, 4,
        // Synth FX
        4, 4, 4, 4, 4, 4, 4, 4,
        // Ethnic
        6, 6, 6, 6, 6, 6, 6, 6,
        // Percussive
        11, 11, 11, 11, 11, 11, 11, 11,
        // Sound FX
        1, 1, 1, 1, 1, 1, 1, 1
    };
    // @formatter:on

    public void export(Sequence sequence, OutputStream out)
    {
        try (var writer = new VgmWriter(out, VgmWriter.ChipMode.YM2413))
        {
            var events = mergeAndSort(sequence);
            var state = new Ym2413State();
            state.initSilence(writer);

            long prevTick = 0;
            double ticksPerSample = ticksPerSample(sequence);

            for (var event : events)
            {
                long tick = event.getTick();
                int waitSamples = (int) Math.round((tick - prevTick) / ticksPerSample);
                if (waitSamples > 0)
                    writer.waitSamples(waitSamples);
                prevTick = tick;

                if (event.getMessage() instanceof ShortMessage msg)
                    state.handleMessage(msg, writer);
            }
        }
    }

    private static double ticksPerSample(Sequence sequence)
    {
        int resolution = sequence.getResolution();
        double ticksPerSecond = resolution * 1_000_000.0 / 500_000.0;
        return ticksPerSecond / VgmWriter.VGM_SAMPLE_RATE;
    }

    private static List<MidiEvent> mergeAndSort(Sequence sequence)
    {
        var events = new ArrayList<MidiEvent>();
        for (Track track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
                events.add(track.get(i));
        }
        events.sort(Comparator.comparingLong(MidiEvent::getTick));
        return events;
    }

    // ── YM2413 state ─────────────────────────────────────────────────────────

    private static final class Ym2413State
    {
        private final int[] noteSlot = new int[TOTAL_SLOTS];
        private final int[] chanSlot = new int[TOTAL_SLOTS];
        private final int[] progSlot = new int[TOTAL_SLOTS];
        private final boolean[] active = new boolean[TOTAL_SLOTS];
        private final int[] program = new int[16]; // per MIDI channel
        private boolean rhythmMode = false;

        Ym2413State()
        {
            java.util.Arrays.fill(noteSlot, -1);
            java.util.Arrays.fill(chanSlot, -1);
        }

        void initSilence(VgmWriter w)
        {
            for (int ch = 0; ch < MELODIC_SLOTS; ch++)
            {
                w.writeYm2413(0x20 + ch, 0); // key off
                w.writeYm2413(0x30 + ch, 0); // instrument 0, volume 15 (silent)
            }
        }

        void handleMessage(ShortMessage msg, VgmWriter w)
        {
            int status = msg.getCommand();
            int midiCh = msg.getChannel();
            int d1 = msg.getData1();
            int d2 = msg.getData2();

            switch (status)
            {
                case ShortMessage.PROGRAM_CHANGE -> program[midiCh] = d1;
                case ShortMessage.NOTE_ON ->
                {
                    if (d2 > 0)
                        noteOn(midiCh, d1, d2, w);
                    else
                        noteOff(midiCh, d1, w);
                }
                case ShortMessage.NOTE_OFF -> noteOff(midiCh, d1, w);
            }
        }

        private void noteOn(int midiCh, int note, int velocity, VgmWriter w)
        {
            if (midiCh == 9)
            {
                enableRhythmMode(w);
                triggerRhythm(note, velocity, w);
                return;
            }

            int slot = findFreeSlot();
            if (slot < 0)
                slot = stealMelodicSlot();

            if (active[slot])
                keyOff(slot, w);

            int prog = program[midiCh];
            int instrument = (prog < GM_TO_YM2413.length) ? GM_TO_YM2413[prog] : 1;
            // Volume: YM2413 uses inverted 4-bit volume (0=max, 15=silent)
            int vol = 15 - (int) Math.round(velocity * 15.0 / 127.0);

            int[] fnumBlock = computeFnumBlock(note);
            int fnum = fnumBlock[0];
            int block = fnumBlock[1];

            w.writeYm2413(0x30 + slot, (instrument << 4) | vol);
            w.writeYm2413(0x10 + slot, fnum & 0xFF);
            w.writeYm2413(0x20 + slot, 0x10 | (block << 1) | ((fnum >> 8) & 0x01)); // key on

            noteSlot[slot] = note;
            chanSlot[slot] = midiCh;
            progSlot[slot] = instrument;
            active[slot] = true;
        }

        private void noteOff(int midiCh, int note, VgmWriter w)
        {
            for (int slot = 0; slot < MELODIC_SLOTS; slot++)
            {
                if (active[slot] && noteSlot[slot] == note && chanSlot[slot] == midiCh)
                {
                    keyOff(slot, w);
                    active[slot] = false;
                    noteSlot[slot] = -1;
                    chanSlot[slot] = -1;
                    return;
                }
            }
        }

        private void keyOff(int slot, VgmWriter w)
        {
            w.writeYm2413(0x20 + slot, 0); // clear key-on bit and frequency
        }

        private void enableRhythmMode(VgmWriter w)
        {
            if (!rhythmMode)
            {
                rhythmMode = true;
                w.writeYm2413(0x0E, 0x20); // enable rhythm mode
            }
        }

        private void triggerRhythm(int note, int velocity, VgmWriter w)
        {
            // Rhythm bits in register 0x0E: BD=bit4, SD=bit3, TOM=bit2, CYM=bit1, HH=bit0
            int bit = rhythmBit(note);
            if (bit < 0)
                return;
            // Volume in 0x36-0x38 for rhythm instruments
            int vol = 15 - (int) Math.round(velocity * 15.0 / 127.0);
            if (note >= 35 && note <= 36) // Bass drum: reg 0x36 upper nibble
                w.writeYm2413(0x36, (vol << 4) | (vol & 0x0F));
            w.writeYm2413(0x0E, 0x20 | (1 << bit));
        }

        private int findFreeSlot()
        {
            for (int i = 0; i < MELODIC_SLOTS; i++)
            {
                if (!active[i])
                    return i;
            }
            return -1;
        }

        private int stealMelodicSlot()
        {
            for (int i = MELODIC_SLOTS - 1; i >= 0; i--)
            {
                if (active[i])
                    return i;
            }
            return 0;
        }

        private static int[] computeFnumBlock(int note)
        {
            double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
            int block = 0;
            while (block < 7 && freq >= 261.63 * Math.pow(2.0, block))
                block++;
            if (block > 0)
                block--;
            int fnum = (int) Math.round(freq * 72.0 * (1L << (19 - block)) / VgmWriter.YM2413_CLOCK);
            fnum = Math.clamp(fnum, 0, 0x1FF);
            return new int[] { fnum, block };
        }

        private static int rhythmBit(int note)
        {
            return switch (note)
            {
                case 35, 36 -> 4; // Bass drum
                case 38, 40 -> 3; // Snare drum
                case 41, 43, 45, 47, 48, 50 -> 2; // Tom
                case 49, 51, 53, 55, 57, 59 -> 1; // Cymbal
                case 42, 44, 46 -> 0; // Hi-hat
                default -> -1;
            };
        }
    }
}
