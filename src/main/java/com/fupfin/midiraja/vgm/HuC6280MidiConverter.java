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
 * <p><b>Register map (per selected channel):</b>
 * <ul>
 *   <li>0x00: Channel select (0-5)
 *   <li>0x02: Frequency low byte (8 bits)
 *   <li>0x03: Frequency high nibble (bits 3-0)
 *   <li>0x04: Control — bit 7: enable, bit 6: DDA mode, bits 4-0: volume (0-31)
 *   <li>0x05: L/R balance — bits 7-4: left vol (0-15), bits 3-0: right vol (0-15)
 *   <li>0x06: Waveform data (ignored for MIDI)
 *   <li>0x07: Noise control (ch 4-5 only, ignored for MIDI)
 * </ul>
 *
 * <p><b>Note gating:</b> Like SCC, volume drives note state: enabled + vol > 0 → NoteOn,
 * disabled or vol = 0 → NoteOff. Frequency changes retrigger the active note.
 */
public class HuC6280MidiConverter {

    private static final int CHANNELS = 6;
    private static final int MIDI_CH_OFFSET = 0;
    private static final int MIN_NOTE = 28;
    private static final int GM_PROGRAM = 80; // Square Lead — PSG wavetable

    private int selectedCh = 0;
    private final int[] freqLo = new int[CHANNELS];
    private final int[] freqHi = new int[CHANNELS];
    private final int[] volume = new int[CHANNELS];     // 5-bit, 0-31
    private final boolean[] enabled = new boolean[CHANNELS];
    private final int[] activeNote = {-1, -1, -1, -1, -1, -1};
    private final int[] balanceL = new int[CHANNELS];   // 0-15
    private final int[] balanceR = new int[CHANNELS];   // 0-15
    private final int[] currentPan = {-1, -1, -1, -1, -1, -1};
    private boolean programSent = false;

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        int reg = event.rawData()[0] & 0xFF;
        int data = event.rawData()[1] & 0xFF;

        switch (reg) {
            case 0x00 -> selectedCh = Math.clamp(data, 0, CHANNELS - 1);
            case 0x02 -> handleFreqLo(data, tick, tracks, clock);
            case 0x03 -> handleFreqHi(data, tick, tracks, clock);
            case 0x04 -> handleControl(data, tick, tracks, clock);
            case 0x05 -> handleBalance(data, tick, tracks);
            default -> {} // 0x01 master vol, 0x06 waveform, 0x07 noise, 0x08-0x09 LFO
        }
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
            activeNote[ch] = note; // track internally, suppress MIDI
            return;
        }
        int midiCh = ch + MIDI_CH_OFFSET;
        ensureProgramSent(midiCh, tick, tracks);
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

    private void ensureProgramSent(int midiCh, long tick, Track[] tracks) {
        if (!programSent) {
            for (int ch = 0; ch < CHANNELS; ch++) {
                addEvent(tracks[ch + MIDI_CH_OFFSET], ShortMessage.PROGRAM_CHANGE,
                        ch + MIDI_CH_OFFSET, GM_PROGRAM, 0, tick);
            }
            programSent = true;
        }
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
