/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts AY-3-8910 / YM2149F PSG chip events to MIDI note events on channels 0-2 and 9.
 *
 * <p>Register map:
 * <ul>
 *   <li>R0/R1: Channel A 12-bit tone period (fine/coarse)
 *   <li>R2/R3: Channel B 12-bit tone period
 *   <li>R4/R5: Channel C 12-bit tone period
 *   <li>R6: Noise period (5-bit) — maps to GM hi-hat based on frequency
 *   <li>R7: Mixer — bit2-0 tone disable per channel (0=enabled), bit5-3 noise disable (0=enabled)
 *   <li>R8-R10: Channel A/B/C volume (bits 3-0); bit4=1 means envelope mode
 *   <li>R11/R12: Envelope period (not mapped)
 *   <li>R13: Envelope shape — determines effective velocity for envelope-mode channels
 * </ul>
 *
 * <p>Frequency formula: {@code f = clock / (16 × N)} where N is the 12-bit tone period.
 *
 * <p><b>Fine + coarse writes both trigger pitch retrigger:</b> MSX games implementing
 * glissandi (pitch slides) typically update only the fine (low-byte) period register per
 * frame, keeping the coarse byte unchanged. An implementation that watches only the coarse
 * byte for pitch changes would miss the entire glissando — the melody would be completely
 * absent from the MIDI output. Both R0/R2/R4 (fine) and R1/R3/R5 (coarse) writes therefore
 * trigger a retrigger check whenever the note changes.
 *
 * <p><b>Noise → hi-hat only:</b> The AY noise generator is an LFSR producing electrical
 * white noise with no drum body, snare crack, or tonal content. GM snare (38) has prominent
 * low-frequency impact that does not exist in the original signal, so all noise periods
 * map to hi-hat variants (42=closed, 46=open) based on R6 frequency.
 *
 * <p><b>Envelope mode approximation:</b> The AY-3-8910 envelope generator (R13) modulates
 * amplitude over time. MIDI has no equivalent; the shape is approximated by a fixed CC7
 * value representing the average or peak amplitude of the envelope contour (see
 * {@link #envelopeEffectiveVol()}).
 *
 * <p><b>Volume → CC7 via square-root curve:</b> Linear mapping ({@code vol/15×127}) places
 * typical game volumes (vol 4-6) at CC7=34-51 — inaudible in most GM soundfonts. The 4-bit
 * volume register behaves perceptually closer to a logarithmic scale, so
 * {@code sqrt(vol/15)×127} gives more faithful audibility at mid-range values while
 * preserving relative dynamics.
 */
public class Ay8910MidiConverter {

    private static final int NOISE_MIDI_CH = 9;

    private final int[] period = new int[3];
    private final int[] volume = new int[3];         // 0=silent, 15=loudest
    private final boolean[] toneEn = {true, true, true};   // R7 bit2-0 inverted
    private final boolean[] noiseEn = {false, false, false}; // R7 bit5-3 inverted
    private final int[] activeNote = {-1, -1, -1};
    private int noisePeriod = 0;    // R6 5-bit noise period divider
    private int envelopeShape = 0;  // R13 envelope shape (0-15)
    private int noiseActiveNote = -1;

    public void convert(VgmEvent event, Track[] tracks, long clock, long tick) {
        // VGM 0xA0 command delivers 2 bytes: register index and value.
        // Mask to 0x0F: the AY has 14 addressable registers (0x00-0x0D);
        // the upper nibble of the register byte is unused.
        int reg = event.rawData()[0] & 0x0F;
        int val = event.rawData()[1] & 0xFF;

        switch (reg) {
            // Period registers come in fine/coarse pairs. Tone period N is 12-bit:
            //   fine  (R0/R2/R4): low  8 bits — written alone for small pitch steps
            //   coarse(R1/R3/R5): high 4 bits — only bits 3-0 used
            // Both fine and coarse writes call handleTone() because MSX games that implement
            // glissandi (e.g. arpeggios, vibrato) often update only the fine byte per frame.
            // Before this was fixed, watching only the coarse byte caused entire melody lines
            // to be absent from the MIDI output.
            case 0 -> { period[0] = (period[0] & 0xF00) | val;
                        handleTone(0, tick, tracks, clock); }
            case 1 -> { period[0] = (period[0] & 0x0FF) | ((val & 0x0F) << 8);
                        handleTone(0, tick, tracks, clock); }
            case 2 -> { period[1] = (period[1] & 0xF00) | val;
                        handleTone(1, tick, tracks, clock); }
            case 3 -> { period[1] = (period[1] & 0x0FF) | ((val & 0x0F) << 8);
                        handleTone(1, tick, tracks, clock); }
            case 4 -> { period[2] = (period[2] & 0xF00) | val;
                        handleTone(2, tick, tracks, clock); }
            case 5 -> { period[2] = (period[2] & 0x0FF) | ((val & 0x0F) << 8);
                        handleTone(2, tick, tracks, clock); }
            case 6 -> noisePeriod = val & 0x1F;
            case 7 -> handleMixer(val, tick, tracks, clock);
            case 8 -> handleVolume(0, val, tick, tracks, clock);
            case 9 -> handleVolume(1, val, tick, tracks, clock);
            case 10 -> handleVolume(2, val, tick, tracks, clock);
            case 13 -> envelopeShape = val & 0x0F;
            // R11, R12: envelope period — not mapped to MIDI
        }
    }

    private void handleMixer(int val, long tick, Track[] tracks, long clock) {
        // R7 uses inverted-enable logic: bit=0 means enabled, bit=1 means disabled.
        // We compare old and new tone-enable states to fire NoteOff/NoteOn only on
        // transitions, avoiding spurious note events when the noise bits change while
        // a tone channel is already playing.
        for (int ch = 0; ch < 3; ch++) {
            boolean newTone = (val & (1 << ch)) == 0;
            boolean wasTone = toneEn[ch];
            toneEn[ch] = newTone;
            noiseEn[ch] = (val & (1 << (ch + 3))) == 0;

            if (wasTone && !newTone) {
                noteOff(ch, tick, tracks);
            } else if (!wasTone && newTone && volume[ch] > 0) {
                noteOn(ch, tick, tracks, clock);
            }
        }
        updateNoise(tick, tracks);
    }

    private void handleVolume(int ch, int val, long tick, Track[] tracks, long clock) {
        // bit4=1 means the channel routes through the envelope generator (R13) instead
        // of a static 4-bit volume. The envelope shape maps to an approximated effective
        // volume because MIDI has no time-varying amplitude envelope.
        boolean envelope = (val & 0x10) != 0;
        int newVol = envelope ? envelopeEffectiveVol() : (val & 0x0F);
        int oldVol = volume[ch];
        volume[ch] = newVol;

        boolean shouldPlay = newVol > 0 && toneEn[ch];
        boolean wasPlaying = oldVol > 0 && toneEn[ch];

        if (!shouldPlay) {
            noteOff(ch, tick, tracks);
        } else if (!wasPlaying) {
            noteOn(ch, tick, tracks, clock);
        } else {
            // Volume envelope change while note is playing: send CC7 without re-triggering.
            // Re-triggering on every envelope step causes audible attack clicks.
            addNote(tracks[ch], ShortMessage.CONTROL_CHANGE, ch, 7, toVelocity(newVol), tick);
        }
        updateNoise(tick, tracks);
    }

    private void handleTone(int ch, long tick, Track[] tracks, long clock) {
        // Only check for pitch change when a note is actively sounding.
        // Volume=0 or tone disabled means there is nothing to retrigger.
        if (volume[ch] == 0 || !toneEn[ch] || activeNote[ch] < 0) return;
        int newNote = ay8910Note(clock, period[ch]);
        if (newNote >= 0 && newNote != activeNote[ch]) {
            noteOff(ch, tick, tracks);
            noteOn(ch, tick, tracks, clock);
        }
    }

    private void noteOn(int ch, long tick, Track[] tracks, long clock) {
        int note = ay8910Note(clock, period[ch]);
        if (note < 0) return;
        // CC7 before NoteOn: ensures channel volume is set correctly regardless of any
        // residual CC7 value left from a previous note or Program Change reset.
        addNote(tracks[ch], ShortMessage.CONTROL_CHANGE, ch, 7, toVelocity(volume[ch]), tick);
        addNote(tracks[ch], ShortMessage.NOTE_ON, ch, note, 127, tick);
        activeNote[ch] = note;
    }

    private void noteOff(int ch, long tick, Track[] tracks) {
        if (activeNote[ch] >= 0) {
            addNote(tracks[ch], ShortMessage.NOTE_OFF, ch, activeNote[ch], 0, tick);
            activeNote[ch] = -1;
        }
    }

    private void updateNoise(long tick, Track[] tracks) {
        // Noise is a single mono source shared across all three channels via the mixer.
        // Determine the loudest noise-enabled channel to use as the output velocity.
        // One MIDI note-on on ch 9 represents the combined noise output; which physical
        // channel drives the noise generator is not musically meaningful.
        int maxVol = 0;
        for (int ch = 0; ch < 3; ch++) {
            if (noiseEn[ch] && volume[ch] > maxVol) maxVol = volume[ch];
        }
        boolean noiseOn = maxVol > 0;
        if (noiseOn && noiseActiveNote < 0) {
            int note = noiseNote(noisePeriod);
            addNote(tracks[NOISE_MIDI_CH], ShortMessage.NOTE_ON, NOISE_MIDI_CH,
                    note, toVelocity(maxVol), tick);
            noiseActiveNote = note;
        } else if (!noiseOn && noiseActiveNote >= 0) {
            addNote(tracks[NOISE_MIDI_CH], ShortMessage.NOTE_OFF, NOISE_MIDI_CH,
                    noiseActiveNote, 0, tick);
            noiseActiveNote = -1;
        }
    }

    /**
     * Maps a 5-bit AY-3-8910 noise period to a GM percussion note.
     *
     * <p>The AY-3-8910 noise generator is an LFSR producing electrical white noise — there is no
     * drum body or snare crack; only the density/rate of the noise changes with R6. GM snare (38)
     * has significant low-frequency content that does not exist in the original signal, so all
     * periods map to hi-hat variants.
     *
     * <p>Noise frequency = clock / (16 × period). Higher period → slower LFSR → coarser noise:
     * <ul>
     *   <li>period ≤ 12  (~9 kHz+): closed hi-hat (42) — bright, short hiss
     *   <li>period 13+   (< 9 kHz): open hi-hat (46) — coarser noise, longer ring
     * </ul>
     */
    static int noiseNote(int period) {
        if (period <= 12) return 42; // Closed Hi-Hat
        return 46;                   // Open Hi-Hat (coarser, slower noise)
    }

    /**
     * Estimates an effective MIDI velocity volume (0-15) for envelope-mode channels.
     *
     * <p>The AY-3-8910 envelope generator modulates amplitude over time; R13 selects the shape:
     * <ul>
     *   <li>Decay/transient (0-3, 8, 9, 10, 15): average amplitude ≈ 50% → effective vol = 7
     *   <li>Sustained-high (11, 13): fall/rise then hold at max → effective vol = 11
     *   <li>Oscillating (12, 14): continuous sawtooth/triangle → effective vol = 8
     * </ul>
     */
    private int envelopeEffectiveVol() {
        return switch (envelopeShape) {
            case 11, 13 -> 11; // single phase then hold at max amplitude
            case 12, 14 -> 8;  // continuous sawtooth/triangle, average ≈ 53%
            default     -> 7;  // decay / single-shot transient shapes (0-3, 8, 9, 10, 15)
        };
    }

    /** Converts a 12-bit AY-3-8910 tone period to a MIDI note number. */
    static int ay8910Note(long clock, int period) {
        if (period <= 0) return -1;
        double f = clock / (16.0 * period);
        return Sn76489MidiConverter.clamp(
                (int) Math.round(12 * Math.log(f / 440.0) / Math.log(2) + 69), 0, 127);
    }

    private static int toVelocity(int vol) {
        // Square-root curve: linear mapping (vol/15×127) placed typical game volumes (vol 4-6)
        // at CC7=34-51, inaudible in most GM soundfonts. Sqrt maps vol=4→85, vol=6→98,
        // preserving relative dynamics while matching the perceptual loudness of the 4-bit register.
        return Sn76489MidiConverter.clamp((int) Math.round(Math.sqrt(vol / 15.0) * 127), 0, 127);
    }

    private static void addNote(Track track, int cmd, int ch, int note, int vel, long tick) {
        try {
            track.add(new MidiEvent(new ShortMessage(cmd, ch, note, vel), tick));
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Bad MIDI data", e);
        }
    }
}
