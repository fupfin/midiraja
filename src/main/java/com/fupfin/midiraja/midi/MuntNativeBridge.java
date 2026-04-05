/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

/**
 * Dependency Inversion interface for Munt's native C API (`libmt32emu`). This isolates the FFM
 * MethodHandles, allowing the MuntSynthProvider's core logic to be tested purely in Java using a
 * mock implementation.
 */
public interface MuntNativeBridge extends AutoCloseable
{
    /** Initializes the Munt emulation context. */
    void createSynth() throws Exception;

    /** Loads ROM files (MT32_CONTROL.ROM, MT32_PCM.ROM) from the specified directory. */
    void loadRoms(String romDirectory) throws Exception;

    /** Opens the synthesizer engine, preparing it for playback and rendering. */
    void openSynth() throws Exception;

    /**
     * Resets the internal render-clock reference to "now". Called by the render thread immediately
     * before its first render cycle so that events queued before the first render get near-zero
     * future timestamps instead of stale construction-time offsets. Default no-op for bridge
     * implementations that do not use wall-clock timestamps.
     */
    default void resetRenderTiming()
    {
    }

    /**
     * Stops all MT-32 voices immediately by cycling the synth context (close + reopen), then resets
     * the render-timing reference to zero. This avoids the ~2-second silence that the MT-32 master
     * reset SysEx (address 7F 00 00) causes in Munt due to the emulated ROM initialization
     * sequence.
     * <p>
     * <b>Must only be called while the render thread is paused</b> — {@code close_synth} and
     * {@code open_synth} are not thread-safe with {@code render_bit16s}. Default no-op for
     * non-MT-32 bridge implementations.
     */
    default void reopenSynth() throws Exception
    {
    }

    // --- MIDI Event Routing ---
    void playNoteOn(int channel, int key, int velocity);

    void playNoteOff(int channel, int key);

    void playControlChange(int channel, int number, int value);

    void playProgramChange(int channel, int program);

    void playPitchBend(int channel, int value);

    void playSysex(byte[] sysexData);

    /**
     * Renders PCM data into the provided short array (interleaved stereo).
     *
     * @param buffer
     *            The array to fill with 16-bit PCM samples.
     * @param frames
     *            The number of sample frames (1 frame = 2 shorts for stereo).
     */
    void renderAudio(short[] buffer, int frames);

    // --- ROM Info ---

    /**
     * Returns the control ROM description string (e.g. "MT-32 Control v1.07"), or {@code null} if
     * not available. The ROM must have been loaded via {@link #loadRoms} before calling this.
     * Default is {@code null} for implementations that do not support this query.
     */
    @org.jspecify.annotations.Nullable
    default String getRomDescription()
    {
        return null;
    }

    // --- Diagnostic / State Polling ---

    /**
     * Returns true if at least one Munt partial is actively sounding (Attack or Sustain state).
     * Default is false for implementations that do not support this query.
     */
    default boolean hasActivePartials()
    {
        return false;
    }

    /**
     * Returns a bitmask of active parts: bit 0 = Part 1, bit 7 = Part 8, bit 8 = Rhythm. A set bit
     * means at least one partial on that part is active. Default is 0 for implementations that do
     * not support this query.
     */
    default int getPartStates()
    {
        return 0;
    }

    /**
     * Returns the count of currently playing notes on the specified part (0–7 for Parts 1–8, 8 for
     * the Rhythm part). Fills {@code keys} and {@code velocities} with up to 4 entries. Default is
     * 0 for implementations that do not support this query.
     */
    default int getPlayingNotes(int partNumber, byte[] keys, byte[] velocities)
    {
        return 0;
    }

    /** Closes and frees all native resources. */
    @Override
    void close();
}
