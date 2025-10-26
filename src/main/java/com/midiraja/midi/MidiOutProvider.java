/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import java.util.List;

/**
 * Abstraction layer for native OS MIDI capabilities. Decouples the playback engine from operating
 * system specifics (ALSA, WinMM, CoreMIDI).
 */
public interface MidiOutProvider
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
    void sendMessage(byte[] data) throws Exception;

    /**
     * Safely tears down the connection and releases native resources.
     */
    void closePort();

    /**
     * Returns the estimated audio output latency in nanoseconds. Used to synchronize visual
     * feedback (e.g. VU meters) with actual audible output. Default is 0 for hardware MIDI ports
     * where the OS driver handles latency transparently.
     */
    default long getAudioLatencyNanos() {
        return 0L;
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
            catch (Exception ignored) { /* Ignore during panic */ }
        }
    }

    /**
     * Instantly silences all active notes to prevent stuck sounds across track changes or abrupt
     * exits.
     */
    default void panic()
    {
        for (int ch = 0; ch < 16; ch++)
        {
            try
            {
                // 1. Send standard panic controllers
                sendMessage(new byte[] {(byte) (0xB0 | ch), 64, 0});  // Sustain Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 123, 0}); // All Notes Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 120, 0}); // All Sound Off
                sendMessage(new byte[] {(byte) (0xB0 | ch), 121, 0}); // Reset All Controllers
                
                // 2. Machine Gun Panic: Explicitly send Note Off for every single key
                // Some synthesizers ignore CC 123, so this is the ultimate fallback.
                for (int note = 0; note < 128; note++) {
                    sendMessage(new byte[] {(byte) (0x80 | ch), (byte) note, 0});
                }
            }
            catch (Exception ignored) { /* Ignore during panic */ }
        }
        // Increase flush window for the heavy Note Off barrage
        long endWait = System.currentTimeMillis() + 200;
        while (System.currentTimeMillis() < endWait) {
            try { Thread.sleep(Math.max(1, endWait - System.currentTimeMillis())); } catch (Exception ignored) { /* force wait */ }
        }
    }
}
