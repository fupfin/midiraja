/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

/**
 * Dependency Inversion interface for libOPNMIDI's native C API.
 * This isolates the FFM MethodHandles, allowing OpnMidiSynthProvider's
 * core logic to be tested purely in Java using a mock implementation.
 *
 * <p>libOPNMIDI provides OPN2 (YM2612, Sega Genesis) and OPNA (YM2608, PC-98)
 * FM synthesis. Unlike libADLMIDI, it has no embedded instrument banks;
 * external .wopn bank files must be loaded via {@link #loadBankFile}.
 *
 * <p>libOPNMIDI is NOT thread-safe. All methods must be called from the
 * render thread, except MIDI routing methods which are called from the
 * playback thread but queued in OpnMidiSynthProvider before dispatching.
 */
public interface OpnMidiNativeBridge extends AutoCloseable {

    /**
     * Initializes the OPN2 synthesizer device at the given sample rate.
     * Must be called before any other method.
     *
     * @param sampleRate Audio output sample rate (e.g. 44100).
     */
    void init(int sampleRate) throws Exception;

    /**
     * Loads an external .wopn bank file.
     * Call after {@link #init}.
     */
    void loadBankFile(String path) throws Exception;

    /**
     * Loads a bank from an in-memory byte array (e.g. a resource embedded in the jar).
     * Call after {@link #init}.
     */
    void loadBankData(byte[] data) throws Exception;

    /**
     * Sets the number of OPN2 chips to emulate (affects polyphony).
     * Default is 4 chips.
     */
    void setNumChips(int numChips);

    /**
     * Switches the OPN2 emulator backend.
     * 0 = MAME YM2612, 1 = Nuked YM3438, 2 = GENS, 3 = YMFM OPN2,
     * 4 = NP2 OPNA, 5 = MAME YM2608 OPNA, 6 = YMFM OPNA.
     */
    void switchEmulator(int emulatorId);

    /**
     * Resets the synthesizer state (clears all notes and patch settings).
     * Safe to call only from the render thread (not thread-safe).
     */
    void reset();

    // --- MIDI Event Routing (dispatched by render thread from event queue) ---

    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void controlChange(int channel, int type, int value);
    void patchChange(int channel, int patch);

    /**
     * Sends a pitch-bend event on the given channel.
     *
     * @param channel MIDI channel (0–15).
     * @param pitch   14-bit unsigned pitch bend (0–16383).
     */
    void pitchBend(int channel, int pitch);
    void systemExclusive(byte[] data);

    /**
     * Immediately cuts all active notes. Must be called from the render thread
     * while it is paused (i.e., not in {@link #generate}).
     */
    void panic();

    /**
     * Renders PCM audio into {@code buffer}.
     *
     * <p>{@code stereoFrames} is the number of stereo frames; the buffer must
     * have at least {@code stereoFrames * 2} elements. Internally, libOPNMIDI's
     * {@code opn2_generate} receives {@code buffer.length} (total shorts = frames × 2).
     *
     * @param buffer       Output buffer (interleaved stereo 16-bit PCM).
     * @param stereoFrames Number of stereo frames to render.
     */
    void generate(short[] buffer, int stereoFrames);

    /** Closes and frees all native resources. */
    @Override
    void close();
}
