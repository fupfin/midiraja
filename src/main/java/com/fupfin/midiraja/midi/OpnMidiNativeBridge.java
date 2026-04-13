/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

/**
 * Dependency Inversion interface for libOPNMIDI's native C API. This isolates the FFM
 * MethodHandles, allowing OpnMidiSynthProvider's core logic to be tested purely in Java using a
 * mock implementation.
 *
 * <p>
 * libOPNMIDI provides OPN2 (YM2612, Sega Genesis) and OPNA (YM2608, PC-98) FM synthesis. Unlike
 * libADLMIDI, it has no embedded instrument banks; external .wopn bank files must be loaded via
 * {@link #loadBankFile}.
 *
 * <p>
 * libOPNMIDI is NOT thread-safe. All methods must be called from the render thread, except MIDI
 * routing methods which are called from the playback thread but queued in OpnMidiSynthProvider
 * before dispatching.
 */
public interface OpnMidiNativeBridge extends MidiNativeBridge
{
    /**
     * Initializes the OPN2 synthesizer device at the given sample rate. Must be called before any
     * other method.
     *
     * @param sampleRate
     *            Audio output sample rate (e.g. 44100).
     */

    /**
     * Loads an external .wopn bank file. Call after {@link #init}.
     */
    void loadBankFile(String path) throws Exception;

    /**
     * Loads a bank from an in-memory byte array (e.g. a resource embedded in the jar). Call after
     * {@link #init}.
     */
    void loadBankData(byte[] data) throws Exception;

    /**
     * Sets the number of OPN2 chips to emulate (affects polyphony). Default is 4 chips.
     */
    void setNumChips(int numChips);

    /**
     * Switches the OPN2 emulator backend. 0 = MAME YM2612, 1 = Nuked YM3438, 2 = GENS, 3 = YMFM
     * OPN2, 4 = NP2 OPNA, 5 = MAME YM2608 OPNA, 6 = YMFM OPNA, 7 = VGM Dumper.
     */
    void switchEmulator(int emulatorId);

    /**
     * Sets the global VGM output file path for the VGMFileDumper backend.
     *
     * <p>
     * This is a global (not per-device) setting in libOPNMIDI and <b>must be called before
     * {@link #init}</b>. Not thread-safe across concurrent conversions.
     *
     * @param path
     *            absolute path to the output .vgm file
     */
    void setVgmOutPath(String path);

    /**
     * Loads a MIDI file from in-memory bytes for file-based playback or VGM dumping.
     *
     * <p>
     * Call after {@link #init}, {@link #switchEmulator}, and {@link #loadBankData}. Use with
     * {@link #playFromFile} to drive the conversion loop.
     *
     * @param midiBytes
     *            standard MIDI file bytes (type 0 or type 1)
     */
    void openMidiData(byte[] midiBytes) throws Exception;

    /**
     * Renders one chunk from the loaded MIDI file into {@code buffer}.
     *
     * <p>
     * Returns the number of samples written into the buffer. A return value ≤ 0 indicates
     * end-of-file. Only valid after {@link #openMidiData}.
     *
     * @param buffer
     *            output buffer to fill
     * @return samples written; ≤ 0 means end-of-file
     */
    int playFromFile(short[] buffer);

    /**
     * Resets the synthesizer state (clears all notes and patch settings). Safe to call only from
     * the render thread (not thread-safe).
     */

    // --- MIDI Event Routing (dispatched by render thread from event queue) ---

    /**
     * Sends a pitch-bend event on the given channel.
     *
     * @param channel
     *            MIDI channel (0–15).
     * @param pitch
     *            14-bit unsigned pitch bend (0–16383).
     */

    /**
     * Immediately cuts all active notes. Must be called from the render thread while it is paused
     * (i.e., not in {@link #generate}).
     */

    /**
     * Renders PCM audio into {@code buffer}.
     *
     * <p>
     * {@code stereoFrames} is the number of stereo frames; the buffer must have at least
     * {@code stereoFrames * 2} elements. Internally, libOPNMIDI's {@code opn2_generate} receives
     * {@code buffer.length} (total shorts = frames × 2).
     *
     * @param buffer
     *            Output buffer (interleaved stereo 16-bit PCM).
     * @param stereoFrames
     *            Number of stereo frames to render.
     */

    /** Closes and frees all native resources. */

}
