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
 * Converts a MIDI {@link Sequence} to AY-3-8910 VGM format.
 *
 * <p>
 * Uses two AY-3-8910 chips (6 tone channels total, channels 0-2 on chip 0, 3-5 on chip 1).
 * Percussion on MIDI channel 9 is mapped to noise on channel 5.
 * Voice stealing evicts the channel with the highest (least-recently-used) index when all
 * slots are occupied.
 */
public final class Ay8910VgmExporter
{
    private static final int SLOTS = 6;
    private static final int NOISE_SLOT = 5;

    // Drum noise periods for MIDI percussion notes
    private static final int NOISE_KICK = 31;
    private static final int NOISE_HIHAT = 4;
    private static final int NOISE_SNARE = 14;

    // General MIDI percussion note numbers
    private static final int GM_BASS_DRUM = 36;
    private static final int GM_CLOSED_HIHAT = 42;
    private static final int GM_SNARE = 38;

    public void export(Sequence sequence, OutputStream out)
    {
        try (var writer = new VgmWriter(out, VgmWriter.ChipMode.AY8910))
        {
            var events = mergeAndSort(sequence);
            var state = new Ay8910State();
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
        // Default tempo: 120 BPM = 500000 µs/beat
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

    // ── AY-3-8910 state ───────────────────────────────────────────────────────

    private static final class Ay8910State
    {
        private final int[] noteSlot = new int[SLOTS]; // MIDI note in each slot, -1=free
        private final int[] chanSlot = new int[SLOTS]; // MIDI channel for each slot
        private final boolean[] active = new boolean[SLOTS];
        private final int[] mixer = { 0x38, 0x38 }; // initial: all noise/tone off per chip

        Ay8910State()
        {
            java.util.Arrays.fill(noteSlot, -1);
            java.util.Arrays.fill(chanSlot, -1);
        }

        void initSilence(VgmWriter w)
        {
            // All amplitudes to 0, mixer to full mute
            for (int ch = 0; ch < SLOTS; ch++)
                writeAmp(w, ch, 0);
            writeReg(w, 0, 7, 0x3F); // mixer: all bits set = mute all
            writeReg(w, 1, 7, 0x3F);
        }

        void handleMessage(ShortMessage msg, VgmWriter w)
        {
            int status = msg.getCommand();
            int midiCh = msg.getChannel();
            int note = msg.getData1();
            int velocity = msg.getData2();

            if (status == ShortMessage.NOTE_ON && velocity > 0)
                noteOn(midiCh, note, velocity, w);
            else if (status == ShortMessage.NOTE_OFF || (status == ShortMessage.NOTE_ON && velocity == 0))
                noteOff(midiCh, note, w);
        }

        private void noteOn(int midiCh, int note, int velocity, VgmWriter w)
        {
            boolean isPercussion = (midiCh == 9);
            int slot = isPercussion ? NOISE_SLOT : findFreeSlot();
            if (slot < 0)
                slot = stealSlot(isPercussion);

            // Turn off any existing note in this slot
            if (active[slot])
                silenceSlot(slot, w);

            noteSlot[slot] = note;
            chanSlot[slot] = midiCh;
            active[slot] = true;

            int amp = (int) Math.round(velocity * 15.0 / 127.0);

            if (isPercussion)
            {
                int noisePeriod = drumNoisePeriod(note);
                writeReg(w, 1, 6, noisePeriod); // chip 1, noise period reg 6
                enableNoise(w, slot, amp);
            }
            else
            {
                double freq = midiNoteToHz(note);
                int tp = (int) Math.round(VgmWriter.AY8910_CLOCK / (16.0 * freq));
                tp = Math.clamp(tp, 1, 4095);
                int chip = slot / 3;
                int ch = slot % 3;
                writeReg(w, chip, ch * 2, tp & 0xFF); // fine period
                writeReg(w, chip, ch * 2 + 1, (tp >> 8) & 0x0F); // coarse period
                enableTone(w, slot, amp);
            }
        }

        private void noteOff(int midiCh, int note, VgmWriter w)
        {
            for (int slot = 0; slot < SLOTS; slot++)
            {
                if (active[slot] && noteSlot[slot] == note && chanSlot[slot] == midiCh)
                {
                    silenceSlot(slot, w);
                    active[slot] = false;
                    noteSlot[slot] = -1;
                    chanSlot[slot] = -1;
                    return;
                }
            }
        }

        private void silenceSlot(int slot, VgmWriter w)
        {
            writeAmp(w, slot, 0);
            int chip = slot / 3;
            int ch = slot % 3;
            // Disable tone and noise in mixer for this channel
            mixer[chip] |= (1 << ch) | (1 << (ch + 3));
            writeReg(w, chip, 7, mixer[chip]);
        }

        private void enableTone(VgmWriter w, int slot, int amp)
        {
            int chip = slot / 3;
            int ch = slot % 3;
            mixer[chip] &= ~(1 << ch); // clear tone-off bit for this channel
            mixer[chip] |= (1 << (ch + 3)); // set noise-off bit
            writeReg(w, chip, 7, mixer[chip]);
            writeAmp(w, slot, amp);
        }

        private void enableNoise(VgmWriter w, int slot, int amp)
        {
            int chip = slot / 3;
            int ch = slot % 3;
            mixer[chip] &= ~(1 << (ch + 3)); // clear noise-off bit
            mixer[chip] |= (1 << ch); // set tone-off bit
            writeReg(w, chip, 7, mixer[chip]);
            writeAmp(w, slot, amp);
        }

        private int findFreeSlot()
        {
            for (int i = 0; i < SLOTS - 1; i++) // reserve NOISE_SLOT for percussion
            {
                if (!active[i])
                    return i;
            }
            return -1;
        }

        private int stealSlot(boolean isPercussion)
        {
            if (isPercussion)
                return NOISE_SLOT;
            // Steal highest-indexed active tone slot
            for (int i = SLOTS - 2; i >= 0; i--)
            {
                if (active[i])
                    return i;
            }
            return 0;
        }

        private void writeAmp(VgmWriter w, int slot, int amp)
        {
            int chip = slot / 3;
            int ch = slot % 3;
            writeReg(w, chip, 8 + ch, amp & 0x0F);
        }

        private static void writeReg(VgmWriter w, int chip, int reg, int data)
        {
            if (chip == 0)
                w.writeAy(reg, data);
            else
                w.writeAy2(reg, data);
        }

        private static int drumNoisePeriod(int note)
        {
            if (note == GM_BASS_DRUM)
                return NOISE_KICK;
            if (note == GM_CLOSED_HIHAT)
                return NOISE_HIHAT;
            if (note == GM_SNARE)
                return NOISE_SNARE;
            return NOISE_SNARE; // default
        }

        private static double midiNoteToHz(int note)
        {
            return 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        }
    }
}
