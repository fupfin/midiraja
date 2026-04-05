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
 * Converts Game Boy DMG sound chip events to MIDI note events.
 *
 * <p>
 * The DMG has 4 channels:
 * <ul>
 * <li>CH1 (NR10-NR14): Pulse with sweep → MIDI ch 0
 * <li>CH2 (NR21-NR24): Pulse → MIDI ch 1
 * <li>CH3 (NR30-NR34): Wave (4-bit wavetable) → MIDI ch 2
 * <li>CH4 (NR41-NR44): Noise (LFSR) → MIDI ch 9 (GM drums)
 * </ul>
 *
 * <p>
 * VGM command 0xB3 carries a register offset (0x00-0x3F mapping to NR10-NR52 + Wave RAM)
 * and a data byte. Register offsets are relative to 0xFF10 in the Game Boy memory map.
 *
 * <p>
 * <b>Frequency encoding:</b> Pulse and wave channels use an 11-bit frequency register
 * where f = clock / (32 × (2048 - freq)). The DMG clock for audio is typically 4,194,304 Hz.
 *
 * <p>
 * <b>Volume:</b> NR12/NR22 bits 7-4 = initial volume (0-15), bit 3 = direction.
 * NR32 bits 6-5 = wave output level (0=mute, 1=100%, 2=50%, 3=25%).
 * NR42 bits 7-4 = initial volume for noise.
 *
 * <p>
 * <b>Trigger:</b> Writing bit 7 of NR14/NR24/NR34/NR44 restarts the channel (trigger event).
 */
public class GameBoyDmgMidiConverter
{

    private static final int MIN_NOTE = 28;
    private static final int GM_PULSE = 80; // Square Lead for pulse channels
    private static final int GM_WAVE = 73; // Recorder for wave channel
    private static final int NOISE_CH = 9; // GM drums

    // NR register offsets (relative to 0xFF10, stored in VGM as 0x00-0x3F)
    // CH1 sweep/pulse: 0x00-0x04 (NR10-NR14)
    // CH2 pulse: 0x06-0x09 (NR21-NR24, NR20 at 0x05 is unused)
    // CH3 wave: 0x0A-0x0E (NR30-NR34)
    // CH4 noise: 0x10-0x13 (NR41-NR44, NR40 at 0x0F is unused)
    // Master: 0x14-0x16 (NR50-NR52)
    // Wave RAM: 0x20-0x2F

    private final int[] freqLo = new int[3]; // ch 0-2 (pulse1, pulse2, wave)
    private final int[] freqHi = new int[3]; // ch 0-2
    private final int[] volume = new int[3]; // ch 0-2: 4-bit (0-15)
    private final int[] activeNote = { -1, -1, -1, -1 }; // ch 0-3
    private int noiseVolume = 0;
    private int noiseLfsr = 0;
    private int panRegister = 0xFF; // NR51: all channels L+R by default
    private final int[] currentPan = { -1, -1, -1, -1 };
    private boolean programSent = false;

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick)
    {
        int reg = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;

        switch (reg)
        {
            // CH1 (pulse with sweep)
            case 0x02 -> volume[0] = (data >> 4) & 0x0F; // NR12: initial volume
            case 0x03 -> freqLo[0] = data; // NR13: freq lo
            case 0x04 -> handleTrigger(0, data, tick, tracks, clock); // NR14: freq hi + trigger

            // CH2 (pulse)
            case 0x07 -> volume[1] = (data >> 4) & 0x0F; // NR22: initial volume
            case 0x08 -> freqLo[1] = data; // NR23: freq lo
            case 0x09 -> handleTrigger(1, data, tick, tracks, clock); // NR24: freq hi + trigger

            // CH3 (wave)
            case 0x0C -> volume[2] = switch ((data >> 5) & 0x03)
            {
                case 0 -> 0; // mute
                case 1 -> 15; // 100%
                case 2 -> 10; // 50%
                case 3 -> 5; // 25%
                default -> 0;
            }; // NR32: wave output level
            case 0x0D -> freqLo[2] = data; // NR33: freq lo
            case 0x0E -> handleTrigger(2, data, tick, tracks, clock); // NR34: freq hi + trigger

            // CH4 (noise)
            case 0x11 -> noiseVolume = (data >> 4) & 0x0F; // NR42: initial volume
            case 0x12 -> noiseLfsr = data; // NR43: LFSR params
            case 0x13 -> handleNoiseTrigger(data, tick, tracks); // NR44: trigger

            // Master
            case 0x15 ->
            {
                panRegister = data;
                updateAllPans(tick, tracks);
            } // NR51: L/R panning

            default ->
                {
                } // NR10 sweep, NR20/NR40 unused, wave RAM, NR50/NR52
        }
    }

    private void handleTrigger(int ch, int data, long tick, Track[] tracks, long clock)
    {
        freqHi[ch] = data & 0x07;
        boolean trigger = (data & 0x80) != 0;

        if (trigger && volume[ch] > 0)
        {
            noteOff(ch, tick, tracks);
            int note = computeNote(ch, clock);
            noteOn(ch, note, tick, tracks);
        }
        else if (trigger && volume[ch] == 0)
        {
            noteOff(ch, tick, tracks);
        }
    }

    private void handleNoiseTrigger(int data, long tick, Track[] tracks)
    {
        boolean trigger = (data & 0x80) != 0;
        if (trigger && noiseVolume > 0)
        {
            noteOff(3, tick, tracks);
            // LFSR bit 3: 0=15-bit (long), 1=7-bit (short/metallic)
            // Short noise → hi-hat, long noise → snare-like
            boolean shortMode = (noiseLfsr & 0x08) != 0;
            int drumNote = shortMode ? 42 : 46; // Closed Hi-Hat / Open Hi-Hat

            emitPanForChannel(3, tick, tracks);
            int vel = Math.clamp(Math.round(noiseVolume / 15.0f * 127), 1, 127);
            addEvent(tracks[NOISE_CH], ShortMessage.NOTE_ON, NOISE_CH, drumNote, vel, tick);
            activeNote[3] = drumNote;
        }
        else if (trigger && noiseVolume == 0)
        {
            noteOff(3, tick, tracks);
        }
    }

    private void noteOn(int ch, int note, long tick, Track[] tracks)
    {
        if (note < 0)
            return;
        if (note < MIN_NOTE)
        {
            activeNote[ch] = note;
            return;
        }
        int midiCh = ch; // ch 0-2 → MIDI ch 0-2

        emitPanForChannel(ch, tick, tracks);
        int vel = Math.clamp(Math.round(volume[ch] / 15.0f * 127), 1, 127);
        addEvent(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, vel, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks)
    {
        if (activeNote[ch] < 0)
            return;
        if (activeNote[ch] >= MIN_NOTE)
        {
            int midiCh = (ch == 3) ? NOISE_CH : ch;
            addEvent(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
        }
        activeNote[ch] = -1;
    }

    private void emitPanForChannel(int ch, long tick, Track[] tracks)
    {
        // NR51 bits: ch4R ch3R ch2R ch1R ch4L ch3L ch2L ch1L
        int bit = ch; // ch 0-3
        boolean left = (panRegister & (1 << (bit + 4))) != 0;
        boolean right = (panRegister & (1 << bit)) != 0;
        int pan;
        if (left && right)
            pan = 64;
        else if (left)
            pan = 0;
        else if (right)
            pan = 127;
        else
            pan = 64;

        if (pan != currentPan[ch])
        {
            int midiCh = (ch == 3) ? NOISE_CH : ch;
            addEvent(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 10, pan, tick);
            currentPan[ch] = pan;
        }
    }

    private void updateAllPans(long tick, Track[] tracks)
    {
        for (int ch = 0; ch < 4; ch++)
        {
            if (activeNote[ch] >= 0)
                emitPanForChannel(ch, tick, tracks);
        }
    }

    private void ensureProgramSent(long tick, Track[] tracks)
    {
        if (!programSent)
        {
            addEvent(tracks[0], ShortMessage.PROGRAM_CHANGE, 0, GM_PULSE, 0, tick);
            addEvent(tracks[1], ShortMessage.PROGRAM_CHANGE, 1, GM_PULSE, 0, tick);
            addEvent(tracks[2], ShortMessage.PROGRAM_CHANGE, 2, GM_WAVE, 0, tick);
            programSent = true;
        }
    }

    private int computeNote(int ch, long clock)
    {
        int freq = (freqHi[ch] << 8) | freqLo[ch];
        return dmgNote(clock, freq);
    }

    /** Converts DMG 11-bit frequency to MIDI note. f = clock / (32 × (2048 - freq)). */
    static int dmgNote(long clock, int freq)
    {
        int period = 2048 - freq;
        if (period <= 0)
            return -1;
        double f = clock / (32.0 * period);
        return Math.clamp(Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }
}
