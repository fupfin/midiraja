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
 * Converts HuC6280 (PC Engine) PSG events to MIDI note events on channels 0-5.
 *
 * <p>The HuC6280 has 6 wavetable channels with stereo volume control. VGM command 0xB9
 * carries a register address and data byte. The chip uses a channel-select register (0x00)
 * to direct subsequent register writes (0x02-0x07) to the selected channel.
 *
 * <p><b>Waveform analysis:</b> Each channel has a 32-sample, 5-bit wavetable written via
 * register 0x06. The waveform shape is classified by counting steep edges (consecutive
 * sample differences &gt; 10) to select an appropriate GM program:
 * <ul>
 *   <li>0 steep edges → sine/triangle → Calliope Lead (82)
 *   <li>1 steep edge → sawtooth → Sawtooth Lead (81)
 *   <li>2 steep edges → square wave → Square Lead (80)
 *   <li>3+ steep edges → complex waveform → Synth Brass 1 (62)
 * </ul>
 */
public class HuC6280MidiConverter {

    private static final int CHANNELS = 6;
    private static final int WAVE_LENGTH = 32;
    private static final int MIDI_CH_OFFSET = 0;
    private static final int MIN_NOTE = 28;
    private static final int STEEP_THRESHOLD = 10; // ~1/3 of 5-bit range (0-31)

    private static final int PROGRAM_SQUARE_LEAD   = 80;
    private static final int PROGRAM_SAWTOOTH_LEAD = 81;
    private static final int PROGRAM_CALLIOPE_LEAD = 82;
    private static final int PROGRAM_SYNTH_BRASS1  = 62;

    private int selectedCh = 0;
    private final int[] freqLo = new int[CHANNELS];
    private final int[] freqHi = new int[CHANNELS];
    private final int[] volume = new int[CHANNELS];
    private final boolean[] enabled = new boolean[CHANNELS];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1};
    private final int[] balanceL = new int[CHANNELS];
    private final int[] balanceR = new int[CHANNELS];
    private final int[] currentPan = {-1, -1, -1, -1, -1, -1};
    private final int[] currentProgram = {-1, -1, -1, -1, -1, -1};

    // Waveform data: 32 samples (5-bit, 0-31) per channel
    private final int[][] waveform = new int[CHANNELS][WAVE_LENGTH];
    private final int[] waveWritePos = new int[CHANNELS];

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int reg = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;

        switch (reg) {
            case 0x00 -> selectedCh = Math.clamp(data, 0, CHANNELS - 1);
            case 0x02 -> handleFreqLo(data, tick, tracks, clock);
            case 0x03 -> handleFreqHi(data, tick, tracks, clock);
            case 0x04 -> handleControl(data, tick, tracks, clock);
            case 0x05 -> handleBalance(data, tick, tracks);
            case 0x06 -> handleWaveData(data);
            default -> {} // 0x01 master vol, 0x07 noise, 0x08-0x09 LFO
        }
    }

    private void handleWaveData(int data) {
        int ch = selectedCh;
        int pos = waveWritePos[ch];
        waveform[ch][pos] = data & 0x1F;
        waveWritePos[ch] = (pos + 1) % WAVE_LENGTH;
    }

    private void handleFreqLo(int data, long tick, Track[] tracks, long clock) {
        int ch = selectedCh;
        freqLo[ch] = data;
        retriggerIfNeeded(ch, tick, tracks, clock);
    }

    private void handleFreqHi(int data, long tick, Track[] tracks, long clock) {
        int ch = selectedCh;
        freqHi[ch] = data & 0x0F;
        retriggerIfNeeded(ch, tick, tracks, clock);
    }

    private void handleControl(int data, long tick, Track[] tracks, long clock) {
        int ch = selectedCh;
        enabled[ch] = (data & 0x80) != 0;
        volume[ch] = data & 0x1F;

        if (enabled[ch] && volume[ch] > 0) {
            int note = computeNote(ch, clock);
            if (note != activeNote[ch]) {
                noteOff(ch, tick, tracks);
                noteOn(ch, note, tick, tracks);
            }
            emitVolume(ch, tick, tracks);
        } else {
            noteOff(ch, tick, tracks);
        }
    }

    private void handleBalance(int data, long tick, Track[] tracks) {
        int ch = selectedCh;
        balanceL[ch] = (data >> 4) & 0x0F;
        balanceR[ch] = data & 0x0F;
        emitPanIfNeeded(ch, tick, tracks);
    }

    private void retriggerIfNeeded(int ch, long tick, Track[] tracks, long clock) {
        if (!enabled[ch] || volume[ch] == 0 || activeNote[ch] < 0) return;
        int note = computeNote(ch, clock);
        if (note != activeNote[ch]) {
            noteOff(ch, tick, tracks);
            noteOn(ch, note, tick, tracks);
        }
    }

    private void noteOn(int ch, int note, long tick, Track[] tracks) {
        if (note < MIN_NOTE) {
            activeNote[ch] = note;
            return;
        }
        int midiCh = ch + MIDI_CH_OFFSET;
        emitProgramIfNeeded(ch, midiCh, tick, tracks);
        emitPanIfNeeded(ch, tick, tracks);
        addEvent(tracks[midiCh], ShortMessage.NOTE_ON, midiCh, note, 100, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks) {
        if (activeNote[ch] < 0) return;
        if (activeNote[ch] >= MIN_NOTE) {
            int midiCh = ch + MIDI_CH_OFFSET;
            addEvent(tracks[midiCh], ShortMessage.NOTE_OFF, midiCh, activeNote[ch], 0, tick);
        }
        activeNote[ch] = -1;
    }

    private void emitVolume(int ch, long tick, Track[] tracks) {
        int midiCh = ch + MIDI_CH_OFFSET;
        int cc7 = Math.clamp(Math.round(volume[ch] / 31.0f * 127), 0, 127);
        addEvent(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 7, cc7, tick);
    }

    private void emitPanIfNeeded(int ch, long tick, Track[] tracks) {
        int l = balanceL[ch], r = balanceR[ch];
        int pan = (l + r == 0) ? 64 : Math.clamp(Math.round(r * 127.0f / (l + r)), 0, 127);
        if (pan != currentPan[ch]) {
            int midiCh = ch + MIDI_CH_OFFSET;
            addEvent(tracks[midiCh], ShortMessage.CONTROL_CHANGE, midiCh, 10, pan, tick);
            currentPan[ch] = pan;
        }
    }

    private void emitProgramIfNeeded(int ch, int midiCh, long tick, Track[] tracks) {
        int program = classifyWaveform(ch);
        if (program != currentProgram[ch]) {
            addEvent(tracks[midiCh], ShortMessage.PROGRAM_CHANGE, midiCh, program, 0, tick);
            currentProgram[ch] = program;
        }
    }

    /**
     * Classifies a 32-sample waveform by counting steep edges (consecutive sample pairs
     * with absolute difference greater than {@link #STEEP_THRESHOLD}).
     */
    static int classifyWaveform(int[] wave) {
        int steepEdges = 0;
        for (int i = 0; i < WAVE_LENGTH; i++) {
            if (Math.abs(wave[(i + 1) % WAVE_LENGTH] - wave[i]) > STEEP_THRESHOLD) {
                steepEdges++;
            }
        }
        return switch (steepEdges) {
            case 0 -> PROGRAM_CALLIOPE_LEAD;    // smooth: sine/triangle
            case 1 -> PROGRAM_SAWTOOTH_LEAD;    // one edge: sawtooth
            case 2 -> PROGRAM_SQUARE_LEAD;      // two edges: square
            default -> PROGRAM_SYNTH_BRASS1;    // complex: many edges
        };
    }

    private int classifyWaveform(int ch) {
        return classifyWaveform(waveform[ch]);
    }

    private int computeNote(int ch, long clock) {
        int period = (freqHi[ch] << 8) | freqLo[ch];
        return huC6280Note(clock, period);
    }

    static int huC6280Note(long clock, int period) {
        if (period <= 0) return -1;
        double f = clock / (32.0 * period);
        return Math.clamp(Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }
}
