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
 * Converts YM2413 (OPLL) FM chip events to MIDI note events.
 *
 * <p>
 * The YM2413 has 9 melody channels (or 6 melody + 5 rhythm percussion in rhythm mode).
 * Unlike 4-operator FM chips, it uses 15 ROM-based preset instruments that map almost
 * directly to General MIDI programs, plus one user-definable instrument.
 *
 * <p>
 * <b>Register map:</b>
 * <ul>
 * <li>0x0E: Rhythm mode (bit 5) + percussion key-on (bits 4-0)
 * <li>0x10-0x18: F-Number low byte (9 channels)
 * <li>0x20-0x28: Sustain (bit 5), Key-on (bit 4), Block (bits 3-1), F-Number high (bit 0)
 * <li>0x30-0x38: Instrument preset (bits 7-4), Volume (bits 3-0, 0=loudest, 15=quietest)
 * </ul>
 *
 * <p>
 * <b>MIDI channel mapping:</b> OPLL ch 0-5 → MIDI ch 3-8, ch 6-8 → MIDI ch 10-12,
 * rhythm drums → MIDI ch 9. This avoids conflict with AY-3-8910 on ch 0-2.
 */
public class Ym2413MidiConverter
{

    private static final int CHANNELS = 9;

    // OPLL preset number (0-15) → GM program number
    private static final int[] PRESET_TO_GM = {
            0, // 0: User-defined → Grand Piano (default)
            40, // 1: Violin
            25, // 2: Guitar (Acoustic Nylon)
            0, // 3: Piano
            73, // 4: Flute
            71, // 5: Clarinet
            68, // 6: Oboe
            56, // 7: Trumpet
            19, // 8: Organ
            60, // 9: Horn
            81, // 10: Synthesizer (Sawtooth Lead)
            6, // 11: Harpsichord
            11, // 12: Vibraphone
            38, // 13: Synth Bass
            32, // 14: Acoustic Bass
            27, // 15: Electric Guitar (Clean)
    };

    // Rhythm mode (0x0E bits 4-0) → GM drum note per bit position
    // Bit 0=HH, 1=TCY, 2=TOM, 3=SD, 4=BD
    private static final int[] RHYTHM_GM_NOTE = {
            42, // bit 0: HH → Closed Hi-Hat
            49, // bit 1: TCY → Crash Cymbal 1
            45, // bit 2: TOM → Low Tom
            38, // bit 3: SD → Acoustic Snare
            36, // bit 4: BD → Bass Drum 1
    };

    private final int[] fnumLo = new int[CHANNELS];
    private final int[] fnumHi = new int[CHANNELS]; // bit 0 of 0x20-0x28
    private final int[] block = new int[CHANNELS]; // bits 3-1 of 0x20-0x28
    private final int[] instrument = new int[CHANNELS];
    private final int[] volume = new int[CHANNELS]; // 0=loudest, 15=quietest
    private final int[] activeNote = { -1, -1, -1, -1, -1, -1, -1, -1, -1 };
    private final int[] currentProgram = { -1, -1, -1, -1, -1, -1, -1, -1, -1 };
    private boolean rhythmMode = false;
    private int rhythmKeyState = 0; // bits 4-0: current key state of 5 drums
    private final int[] activeRhythm = { -1, -1, -1, -1, -1 }; // active drum notes

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick)
    {
        int reg = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;

        if (reg >= 0x10 && reg <= 0x18)
        {
            fnumLo[reg - 0x10] = data;
        }
        else if (reg >= 0x20 && reg <= 0x28)
        {
            handleKeyRegister(reg - 0x20, data, tick, tracks, clock);
        }
        else if (reg >= 0x30 && reg <= 0x38)
        {
            handleInstrumentVolume(reg - 0x30, data, tick, tracks);
        }
        else if (reg == 0x0E)
        {
            handleRhythmControl(data, tick, tracks);
        }
    }

    private void handleKeyRegister(int ch, int data, long tick, Track[] tracks, long clock)
    {
        fnumHi[ch] = data & 0x01;
        block[ch] = (data >> 1) & 0x07;
        boolean keyOn = (data & 0x10) != 0;

        // In rhythm mode, channels 6-8 key-on is handled by 0x0E register
        if (rhythmMode && ch >= 6)
            return;

        if (keyOn)
        {
            noteOff(ch, tick, tracks);
            int note = computeNote(ch, clock);
            noteOn(ch, note, tick, tracks);
        }
        else
        {
            noteOff(ch, tick, tracks);
        }
    }

    private void handleInstrumentVolume(int ch, int data, long tick, Track[] tracks)
    {
        instrument[ch] = (data >> 4) & 0x0F;
        volume[ch] = data & 0x0F;

        // Emit volume as CC7 if note is active
        if (activeNote[ch] >= 0)
        {
            int midiCh = midiChannel(ch);
            int cc7 = Math.clamp(Math.round((15 - volume[ch]) / 15.0f * 127), 0, 127);
            addEvent(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, cc7, tick);
        }
    }

    private void handleRhythmControl(int data, long tick, Track[] tracks)
    {
        rhythmMode = (data & 0x20) != 0;
        if (!rhythmMode)
            return;

        int newKeyState = data & 0x1F;
        for (int bit = 0; bit < 5; bit++)
        {
            boolean wasOn = (rhythmKeyState & (1 << bit)) != 0;
            boolean isOn = (newKeyState & (1 << bit)) != 0;
            if (!wasOn && isOn)
            {
                // Key-on: emit drum NoteOn on ch 9
                int drumNote = RHYTHM_GM_NOTE[bit];
                addEvent(tracks[9], ShortMessage.NOTE_ON, 9, drumNote, 100, tick);
                activeRhythm[bit] = drumNote;
            }
            else if (wasOn && !isOn)
            {
                // Key-off
                if (activeRhythm[bit] >= 0)
                {
                    addEvent(tracks[9], ShortMessage.NOTE_OFF, 9, activeRhythm[bit], 0, tick);
                    activeRhythm[bit] = -1;
                }
            }
        }
        rhythmKeyState = newKeyState;
    }

    private void noteOn(int ch, int note, long tick, Track[] tracks)
    {
        if (note < 0)
            return;
        int midiCh = midiChannel(ch);
        emitProgramIfNeeded(ch, midiCh, tick, tracks);
        int vel = Math.clamp(Math.round((15 - volume[ch]) / 15.0f * 127), 1, 127);
        addEvent(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, vel, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks)
    {
        if (activeNote[ch] < 0)
            return;
        int midiCh = midiChannel(ch);
        addEvent(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
        activeNote[ch] = -1;
    }

    private void emitProgramIfNeeded(int ch, int midiCh, long tick, Track[] tracks)
    {
        int gm = PRESET_TO_GM[instrument[ch]];
        if (gm != currentProgram[ch])
        {
            addEvent(tracks[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, gm, 0, tick);
            currentProgram[ch] = gm;
        }
    }

    private int computeNote(int ch, long clock)
    {
        int fnum = (fnumHi[ch] << 8) | fnumLo[ch];
        return opllNote(clock, fnum, block[ch]);
    }

    /** Converts OPLL F-Number + block to MIDI note. f = fnum × clock / (72 × 2^(20-block)). */
    static int opllNote(long clock, int fnum, int block)
    {
        if (fnum <= 0)
            return -1;
        double f = fnum * clock / (72.0 * (1L << (20 - block)));
        return Math.clamp(Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }

    /** OPLL ch 0-5 → MIDI ch 3-8, ch 6-8 → MIDI ch 10-12. */
    private static int midiChannel(int ch)
    {
        return (ch < 6) ? ch + 3 : ch + 4; // 6→10, 7→11, 8→12
    }
}
