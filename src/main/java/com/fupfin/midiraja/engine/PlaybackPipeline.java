/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

/**
 * Abstraction over the MIDI filter/output pipeline that MidiPlaybackEngine uses to
 * send events and adjust audio parameters. Decouples the engine from concrete filter
 * classes (VolumeFilter, TransposeFilter, SysexFilter).
 */
public interface PlaybackPipeline {

    /** Send a raw MIDI message through the pipeline. */
    void sendMessage(byte[] data) throws Exception;

    /** Adjust volume by delta. Implementations handle provider-native vs MIDI-CC dual-mode. */
    void adjustVolume(double delta);

    /** Current volume scale (0.0–1.5). */
    double getVolumeScale();

    /**
     * Adjust transpose in semitones. Implementations should silence lingering notes
     * (e.g. via provider panic) before applying the new transposition.
     */
    void adjustTranspose(int semitones);

    /** Current transpose in semitones. */
    int getCurrentTranspose();

    /** Enable or disable SysEx message filtering. */
    void setIgnoreSysex(boolean ignore);
}
