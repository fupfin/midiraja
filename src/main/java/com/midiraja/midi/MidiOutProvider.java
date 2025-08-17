/*
 * Copyright (c) 2026, Park, Sungchul
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.midiraja.midi;

import java.util.List;

/**
 * Abstraction layer for native OS MIDI capabilities.
 * Decouples the playback engine from operating system specifics (ALSA, WinMM, CoreMIDI).
 */
public interface MidiOutProvider {
    /**
     * @return an immutable list of available MIDI output devices on the host OS.
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
     * Transmits a master volume Control Change (CC 7) to all 16 MIDI channels.
     */
    default void setVolume(int volume) {
        if (volume < 0 || volume > 127) return;
        for (int ch = 0; ch < 16; ch++) {
            try {
                sendMessage(new byte[]{(byte) (0xB0 | ch), 7, (byte) volume});
            } catch (Exception ignored) {}
        }
    }

    /**
     * Instantly silences all active notes to prevent stuck sounds across track changes or abrupt exits.
     */
    default void panic() {
        for (int ch = 0; ch < 16; ch++) {
            try {
                // All Notes Off (123) and All Sound Off (120)
                sendMessage(new byte[]{(byte) (0xB0 | ch), 123, 0});
                sendMessage(new byte[]{(byte) (0xB0 | ch), 120, 0});
            } catch (Exception ignored) {}
        }
    }
}