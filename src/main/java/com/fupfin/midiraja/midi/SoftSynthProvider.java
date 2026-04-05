/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

/**
 * An extended interface for software synthesizer providers that require a soundbank (e.g., .sf2) to
 * produce audio.
 */
public interface SoftSynthProvider extends MidiOutProvider
{
    /**
     * Loads a soundbank file into the synthesizer engine.
     *
     * @param path
     *            The absolute or relative path to the soundbank file.
     * @throws Exception
     *             If the file cannot be loaded or is invalid.
     */
    void loadSoundbank(String path) throws Exception;
}
