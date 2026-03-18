/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import com.fupfin.midiraja.dsp.MasterGainFilter;
import com.fupfin.midiraja.io.Console;
import java.util.List;
import java.util.Optional;
import javax.sound.midi.Sequence;

/**
 * Abstraction layer for native OS MIDI capabilities. Decouples the playback engine from operating
 * system specifics (ALSA, WinMM, CoreMIDI).
 */
public interface MidiOutProvider extends MidiSink
{
    /**
     * Returns an immutable list of available MIDI output devices on the host OS.
     */
    List<MidiPort> getOutputPorts();

    /**
     * Initializes a connection to the specified hardware or virtual port.
     */
    void openPort(int portIndex) throws Exception;

    /**
     * Transmits a raw MIDI byte array directly to the native synthesizer.
     */
    @Override
    void sendMessage(byte[] data) throws Exception;

    /**
     * Safely tears down the connection and releases native resources.
     */
    void closePort();

    /**
     * Prepares the audio pipeline for a new track. Called at the start of each song before playback
     * begins. Default is a no-op for hardware MIDI ports. Soft-synth implementations (e.g. Munt)
     * should override this to clear reverb tails and flush queued audio buffers so the new track
     * starts cleanly. Implementations may leave their render thread paused here;
     * {@link #onPlaybackStarted()} will resume it when playback actually begins. The sequence is
     * provided so that software synthesizers can pre-load necessary assets.
     */
    default void prepareForNewTrack(Sequence sequence)
    {}

    /**
     * Called by {@code PlaybackEngine.playLoop()} at the very start of playback, just before the
     * first MIDI event is dispatched. Soft-synth implementations that paused their render thread in
     * {@link #prepareForNewTrack(Sequence)} should resume it here, resetting any
     * timing references so the first notes are scheduled with fresh, near-zero timestamps. Default
     * is a no-op for hardware MIDI ports.
     */
    default void onPlaybackStarted()
    {}

    /**
     * Returns the estimated audio output latency in nanoseconds. Used to synchronize visual
     * feedback (e.g. VU meters) with actual audible output. Default is 0 for hardware MIDI ports
     * where the OS driver handles latency transparently.
     */
    default long getAudioLatencyNanos()
    {
        return 0L;
    }

    /**
     * Returns the output gain control for this provider, if it owns a DSP pipeline. Absent for
     * hardware MIDI ports and soft-synths without float conversion. When present, PlaybackEngine
     * routes volume adjustments through this gain instead of MIDI CC 7.
     */
    default Optional<MasterGainFilter> outputGain()
    {
        return Optional.empty();
    }

    /**
     * Transmits a master volume Control Change (CC 7) to all 16 MIDI channels.
     */
    default void setVolume(int volume)
    {
        if (volume < 0 || volume > 127) return;
        for (int ch = 0; ch < 16; ch++)
        {
            try
            {
                sendMessage(new byte[] {(byte) (0xB0 | ch), 7, (byte) volume});
            }
            catch (Exception ignored)
            {
                Console.err.println(
                        "[NativeBridge Error] " + ignored.getMessage()); /* Ignore during panic */
            }
        }
    }

    /**
     * Sends standard panic MIDI messages (Sustain Off, All Notes Off, All Sound Off, Reset All
     * Controllers, and individual Note Offs for every key) to all 16 channels. Does not wait
     * afterward; callers that need a hardware flush window should sleep after this returns.
     */
    default void sendPanicMessages()
    {
        for (int ch = 0; ch < 16; ch++)
        {
            try
            {
                // 1. Send standard panic controllers
                sendMessage(new byte[] {(byte) (0xB0 | ch), 64, 0}); // Sustain Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 123, 0}); // All Notes Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 120, 0}); // All Sound Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 121, 0}); // Reset All Controllers

                // 2. Machine Gun Panic: Explicitly send Note Off for every single key
                // Some synthesizers ignore CC 123, so this is the ultimate fallback.
                for (int note = 0; note < 128; note++)
                {
                    sendMessage(new byte[] {(byte) (0x80 | ch), (byte) note, 0});
                }
            }
            catch (Exception ignored)
            {
                Console.err.println(
                        "[NativeBridge Error] " + ignored.getMessage()); /* Ignore during panic */
            }
        }
    }

    /**
     * Instantly silences all active notes to prevent stuck sounds across track changes or abrupt
     * exits.
     */
    default void panic()
    {
        sendPanicMessages();
        // Increase flush window for the heavy Note Off barrage
        long endWait = System.currentTimeMillis() + 200;
        while (System.currentTimeMillis() < endWait)
        {
            try
            {
                Thread.sleep(Math.max(1, endWait - System.currentTimeMillis()));
            }
            catch (Exception ignored)
            {
                Console.err.println("[NativeBridge Error] " + ignored.getMessage()); /* force wait */
            }
        }
    }
}
