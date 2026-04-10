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
 * Converts a MIDI {@link Sequence} to MSX VGM format (YM2413 + AY-3-8910).
 *
 * <p>
 * The MSX system has a YM2413 OPLL FM chip (9 melodic channels) and an AY-3-8910 PSG
 * (3 tone channels). FM channels are preferred; PSG handles overflow when all FM slots
 * are occupied. Voice stealing evicts the least-important active channel (highest slot index,
 * then quietest as tiebreaker).
 */
public final class MsxVgmExporter
{
    private static final int MAX_FM_SLOTS = 9;
    private static final int PSG_SLOTS = 3;

    /** Maximum amplitude on PSG (AY-3-8910 is 4-bit, 0-15, but we cap at 11). */
    private static final int PSG_AMP_MAX = 11;
    /** Maximum volume index on FM (YM2413 inverted, 0=max; we use 0-10). */
    private static final int FM_VOL_MAX = 10;

    // @formatter:off
    private static final int[] GM_TO_YM2413 = {
        1, 1, 1, 1, 1, 1, 1, 1,
        11, 11, 11, 11, 11, 11, 11, 11,
        5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6,
        2, 2, 2, 2, 2, 2, 2, 2,
        4, 4, 4, 4, 4, 4, 4, 4,
        4, 4, 4, 4, 4, 4, 4, 4,
        3, 3, 3, 3, 3, 3, 3, 3,
        8, 8, 8, 8, 8, 8, 8, 8,
        7, 7, 7, 7, 7, 7, 7, 7,
        1, 1, 1, 1, 1, 1, 1, 1,
        4, 4, 4, 4, 4, 4, 4, 4,
        4, 4, 4, 4, 4, 4, 4, 4,
        6, 6, 6, 6, 6, 6, 6, 6,
        11, 11, 11, 11, 11, 11, 11, 11,
        1, 1, 1, 1, 1, 1, 1, 1
    };
    // @formatter:on

    public void export(Sequence sequence, OutputStream out)
    {
        try (var writer = new VgmWriter(out, VgmWriter.ChipMode.MSX))
        {
            var events = mergeAndSort(sequence);
            var state = new MsxState();
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

    // ── MSX state ─────────────────────────────────────────────────────────────

    private static final class MsxState
    {
        // FM slots 0-8; PSG slots 9-11
        private static final int FM_START = 0;
        private static final int PSG_START = MAX_FM_SLOTS;
        private static final int TOTAL_SLOTS = MAX_FM_SLOTS + PSG_SLOTS;

        private final int[] noteSlot = new int[TOTAL_SLOTS];
        private final int[] chanSlot = new int[TOTAL_SLOTS];
        private final int[] velSlot = new int[TOTAL_SLOTS];
        private final boolean[] active = new boolean[TOTAL_SLOTS];
        private final int[] program = new int[16];
        private boolean rhythmMode = false;
        private final int[] psgMixer = { 0x38 }; // single AY chip mixer

        MsxState()
        {
            java.util.Arrays.fill(noteSlot, -1);
            java.util.Arrays.fill(chanSlot, -1);
        }

        void initSilence(VgmWriter w)
        {
            for (int ch = 0; ch < MAX_FM_SLOTS; ch++)
            {
                w.writeYm2413(0x20 + ch, 0);
                w.writeYm2413(0x30 + ch, 0x0F); // silent volume
            }
            // AY: all amplitudes 0
            for (int ch = 0; ch < PSG_SLOTS; ch++)
                w.writeAy(8 + ch, 0);
            w.writeAy(7, 0x3F); // mute all
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

            // Try to allocate FM first; fall back to PSG
            int slot = findFreeSlot();
            if (slot < 0)
                slot = stealSlot();

            if (active[slot])
                silenceSlot(slot, w);

            noteSlot[slot] = note;
            chanSlot[slot] = midiCh;
            velSlot[slot] = velocity;
            active[slot] = true;

            if (slot < MAX_FM_SLOTS)
                startFm(slot, note, velocity, midiCh, w);
            else
                startPsg(slot - PSG_START, note, velocity, w);
        }

        private void noteOff(int midiCh, int note, VgmWriter w)
        {
            for (int slot = 0; slot < TOTAL_SLOTS; slot++)
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
            if (slot < MAX_FM_SLOTS)
            {
                w.writeYm2413(0x20 + slot, 0);
            }
            else
            {
                int ch = slot - PSG_START;
                w.writeAy(8 + ch, 0);
                psgMixer[0] |= (1 << ch) | (1 << (ch + 3));
                w.writeAy(7, psgMixer[0]);
            }
        }

        private void startFm(int slot, int note, int velocity, int midiCh, VgmWriter w)
        {
            int prog = program[midiCh];
            int instrument = (prog < GM_TO_YM2413.length) ? GM_TO_YM2413[prog] : 1;
            int vol = FM_VOL_MAX - (int) Math.round(velocity * FM_VOL_MAX / 127.0);

            int[] fnumBlock = computeFnumBlock(note);
            int fnum = fnumBlock[0];
            int block = fnumBlock[1];

            w.writeYm2413(0x30 + slot, (instrument << 4) | vol);
            w.writeYm2413(0x10 + slot, fnum & 0xFF);
            w.writeYm2413(0x20 + slot, 0x10 | (block << 1) | ((fnum >> 8) & 0x01));
        }

        private void startPsg(int psgCh, int note, int velocity, VgmWriter w)
        {
            double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
            int tp = (int) Math.round(VgmWriter.AY8910_CLOCK / (16.0 * freq));
            tp = Math.clamp(tp, 1, 4095);
            w.writeAy(psgCh * 2, tp & 0xFF);
            w.writeAy(psgCh * 2 + 1, (tp >> 8) & 0x0F);

            int amp = (int) Math.round(velocity * PSG_AMP_MAX / 127.0);
            psgMixer[0] &= ~(1 << psgCh);
            psgMixer[0] |= (1 << (psgCh + 3));
            w.writeAy(7, psgMixer[0]);
            w.writeAy(8 + psgCh, amp);
        }

        private void enableRhythmMode(VgmWriter w)
        {
            if (!rhythmMode)
            {
                rhythmMode = true;
                w.writeYm2413(0x0E, 0x20);
            }
        }

        private void triggerRhythm(int note, int velocity, VgmWriter w)
        {
            int bit = rhythmBit(note);
            if (bit < 0)
                return;
            int vol = FM_VOL_MAX - (int) Math.round(velocity * FM_VOL_MAX / 127.0);
            if (bit == 4) // Bass drum
                w.writeYm2413(0x36, (vol << 4) | vol);
            w.writeYm2413(0x0E, 0x20 | (1 << bit));
        }

        private int findFreeSlot()
        {
            for (int i = FM_START; i < MAX_FM_SLOTS; i++)
            {
                if (!active[i])
                    return i;
            }
            for (int i = PSG_START; i < PSG_START + PSG_SLOTS; i++)
            {
                if (!active[i])
                    return i;
            }
            return -1;
        }

        private int stealSlot()
        {
            // Prefer to steal PSG (worst quality); then FM by highest index
            for (int i = PSG_START + PSG_SLOTS - 1; i >= PSG_START; i--)
            {
                if (active[i])
                    return i;
            }
            for (int i = MAX_FM_SLOTS - 1; i >= FM_START; i--)
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
                case 35, 36 -> 4;
                case 38, 40 -> 3;
                case 41, 43, 45, 47, 48, 50 -> 2;
                case 49, 51, 53, 55, 57, 59 -> 1;
                case 42, 44, 46 -> 0;
                default -> -1;
            };
        }
    }
}
