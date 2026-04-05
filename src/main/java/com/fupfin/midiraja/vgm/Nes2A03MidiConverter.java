/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static com.fupfin.midiraja.vgm.FmMidiUtil.addEvent;

import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts NES 2A03 (RP2A03) APU events to MIDI note events.
 *
 * <p>
 * The 2A03 has 5 channels:
 * <ul>
 * <li>Pulse 1 ($4000-$4003): Square wave with sweep → MIDI ch 0
 * <li>Pulse 2 ($4004-$4007): Square wave → MIDI ch 1
 * <li>Triangle ($4008-$400B): Triangle wave → MIDI ch 2
 * <li>Noise ($400C-$400F): LFSR noise → MIDI ch 9 (GM drums)
 * <li>DPCM ($4010-$4013): Delta PCM sample playback → MIDI ch 9 (GM drums)
 * </ul>
 *
 * <p>
 * VGM command 0xB4 carries a register offset (0x00-0x17 mapping to $4000-$4017)
 * and a data byte.
 *
 * <p>
 * <b>Frequency encoding:</b> Pulse channels use an 11-bit timer period where
 * f = clock / (16 × (period + 1)). Triangle uses f = clock / (32 × (period + 1)),
 * producing a note one octave lower for the same period value.
 *
 * <p>
 * <b>Volume:</b> Pulse and noise use a 4-bit volume in bits 3-0 of $4000/$4004/$400C
 * when the constant volume flag (bit 4) is set. Triangle has no volume control — it is
 * either on or off (controlled by the linear counter at $4008).
 *
 * <p>
 * <b>Trigger:</b> Writing to $4003/$4007/$400B/$400F reloads the length counter,
 * effectively triggering the channel.
 */
public class Nes2A03MidiConverter
{

    private static final int GM_TRIANGLE = 38; // Synth Bass 1
    private static final int DRUM_CH = 9;
    private static final int DRUM_KICK = 36; // Bass Drum for DPCM

    // Per-channel state: pulse1=0, pulse2=1, triangle=2
    private final int[] periodLo = new int[3];
    private final int[] periodHi = new int[3];
    private final int[] volume = new int[3]; // pulse 4-bit, triangle uses linear counter
    private final int[] activeNote = { -1, -1, -1, -1, -1 }; // pulse1, pulse2, tri, noise, dpcm

    // Noise state
    private int noiseVolume = 0;
    private int noiseMode = 0; // bit 7 of $400E: 0=long, 1=short

    // DPCM state
    private int dpcmFlags = 0;
    private int dpcmLength = 0;
    private boolean dpcmActive = false;

    private boolean triangleProgramSent = false;

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick)
    {
        int reg = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;

        switch (reg)
        {
            // Pulse 1 ($4000-$4003)
            case 0x00 -> volume[0] = data & 0x0F; // $4000: volume
            case 0x02 -> periodLo[0] = data; // $4002: period lo
            case 0x03 -> handlePulseTrigger(0, data, tick, tracks, clock); // $4003: period hi + trigger

            // Pulse 2 ($4004-$4007)
            case 0x04 -> volume[1] = data & 0x0F; // $4004: volume
            case 0x06 -> periodLo[1] = data; // $4006: period lo
            case 0x07 -> handlePulseTrigger(1, data, tick, tracks, clock); // $4007: period hi + trigger

            // Triangle ($4008-$400B)
            case 0x08 -> volume[2] = (data & 0x7F) > 0 ? 15 : 0; // $4008: linear counter → on/off
            case 0x0A -> periodLo[2] = data; // $400A: period lo
            case 0x0B -> handleTriangleTrigger(data, tick, tracks, clock); // $400B: trigger

            // Noise ($400C-$400F)
            case 0x0C -> noiseVolume = data & 0x0F; // $400C: volume
            case 0x0E -> noiseMode = data & 0x80; // $400E: mode flag
            case 0x0F -> handleNoiseTrigger(tick, tracks); // $400F: trigger

            // DPCM ($4010-$4013)
            case 0x10 -> dpcmFlags = data; // $4010: flags/rate
            case 0x13 -> dpcmLength = data; // $4013: sample length

            // Status ($4015)
            case 0x15 -> handleStatus(data, tick, tracks);

            default ->
                {
                } // $4001/$4005 sweep, $4009 unused, $4011 DPCM direct, etc.
        }
    }

    private void handlePulseTrigger(int ch, int data, long tick, Track[] tracks, long clock)
    {
        periodHi[ch] = data & 0x07;
        if (volume[ch] > 0)
        {
            noteOff(ch, tick, tracks);
            int note = computePulseNote(ch, clock);
            noteOn(ch, note, tick, tracks);
        }
        else
        {
            noteOff(ch, tick, tracks);
        }
    }

    private void handleTriangleTrigger(int data, long tick, Track[] tracks, long clock)
    {
        periodHi[2] = data & 0x07;
        if (volume[2] > 0)
        {
            noteOff(2, tick, tracks);
            int note = computeTriangleNote(clock);
            noteOn(2, note, tick, tracks);
        }
        else
        {
            noteOff(2, tick, tracks);
        }
    }

    private void handleNoiseTrigger(long tick, Track[] tracks)
    {
        if (noiseVolume > 0)
        {
            noteOff(3, tick, tracks);
            // Short mode (bit 7) → metallic hi-hat, long mode → open hi-hat
            int drumNote = (noiseMode != 0) ? 42 : 46;
            int vel = Math.clamp(Math.round(noiseVolume / 15.0f * 127), 1, 127);
            addEvent(tracks[DRUM_CH], ShortMessage.NOTE_ON, DRUM_CH, drumNote, vel, tick);
            activeNote[3] = drumNote;
        }
        else
        {
            noteOff(3, tick, tracks);
        }
    }

    private void handleStatus(int data, long tick, Track[] tracks)
    {
        // $4015 bit 4: DPCM enable. Writing 1 with non-zero length triggers playback.
        if ((data & 0x10) != 0 && dpcmLength > 0 && dpcmFlags != 0)
        {
            noteOff(4, tick, tracks);
            int vel = 100; // DPCM has no volume register; use fixed velocity
            addEvent(tracks[DRUM_CH], ShortMessage.NOTE_ON, DRUM_CH, DRUM_KICK, vel, tick);
            activeNote[4] = DRUM_KICK;
            dpcmActive = true;
        }
        else if ((data & 0x10) == 0 && dpcmActive)
        {
            noteOff(4, tick, tracks);
            dpcmActive = false;
        }
    }

    private void noteOn(int ch, int note, long tick, Track[] tracks)
    {
        if (note < 0)
            return;
        int midiCh = ch; // ch 0-2 → MIDI ch 0-2
        if (ch == 2 && !triangleProgramSent)
        {
            addEvent(tracks[2], ShortMessage.PROGRAM_CHANGE, 2, GM_TRIANGLE, 0, tick);
            triangleProgramSent = true;
        }
        int vel = Math.clamp(Math.round(volume[ch] / 15.0f * 127), 1, 127);
        addEvent(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, vel, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks)
    {
        if (activeNote[ch] < 0)
            return;
        if (ch >= 3)
        {
            addEvent(tracks[DRUM_CH], ShortMessage.NOTE_OFF, DRUM_CH, activeNote[ch], 0, tick);
        }
        else
        {
            addEvent(tracks[ch], ShortMessage.NOTE_OFF, ch, activeNote[ch], 0, tick);
        }
        activeNote[ch] = -1;
    }

    private int computePulseNote(int ch, long clock)
    {
        int period = (periodHi[ch] << 8) | periodLo[ch];
        return pulseNote(clock, period);
    }

    private int computeTriangleNote(long clock)
    {
        int period = (periodHi[2] << 8) | periodLo[2];
        return triangleNote(clock, period);
    }

    /** Converts NES pulse 11-bit period to MIDI note. f = clock / (16 × (period + 1)). */
    static int pulseNote(long clock, int period)
    {
        double f = clock / (16.0 * (period + 1));
        return Math.clamp(Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }

    /** Converts NES triangle 11-bit period to MIDI note. f = clock / (32 × (period + 1)). */
    static int triangleNote(long clock, int period)
    {
        double f = clock / (32.0 * (period + 1));
        return Math.clamp(Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }
}
