/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.midi;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import javax.sound.midi.MidiSystem;

import com.fupfin.midiraja.format.MusicFormatLoader;

/**
 * Converts a music file to MIDI format and writes it to an {@link OutputStream} or {@link File}.
 */
public final class MidiExporter
{
    public void export(File input, OutputStream out, Set<Integer> mutedChannels)
    {
        try
        {
            var sequence = MusicFormatLoader.load(input, mutedChannels);
            MidiSystem.write(sequence, 1, out);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to load: " + input, e);
        }
    }

    public void export(File input, File output, Set<Integer> mutedChannels)
    {
        try
        {
            var sequence = MusicFormatLoader.load(input, mutedChannels);
            MidiSystem.write(sequence, 1, output);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to load: " + input, e);
        }
    }
}
