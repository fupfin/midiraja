/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

/**
 * Dependency Inversion interface for TinySoundFont's native C API. Isolates the FFM MethodHandles
 * so {@link TsfSynthProvider}'s logic can be tested purely in Java using a mock implementation.
 *
 * <p>
 * TinySoundFont is NOT thread-safe. All methods must be called from the render thread, except MIDI
 * routing methods which are queued in {@link TsfSynthProvider} before dispatching.
 */
public interface TsfNativeBridge extends MidiNativeBridge
{
    /**
     * Loads a SoundFont from {@code path} and initializes the synthesizer at the given sample rate.
     * Must be called before any other method.
     *
     * @param path
     *            Path to an {@code .sf2} or {@code .sf3} SoundFont file.
     * @param sampleRate
     *            Audio output sample rate (e.g. 44100).
     */
    void loadSoundfontFile(String path, int sampleRate) throws Exception;
}
