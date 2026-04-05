/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import org.jspecify.annotations.Nullable;

public class MidiUtils
{
    private MidiUtils()
    {
    }

    /**
     * Checks if the file has a valid MIDI 'MThd' header.
     */
    public static boolean isMidiFile(File file)
    {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file)))
        {
            byte[] header = new byte[4];
            if (bis.read(header) < 4)
                return false;
            return header[0] == 'M' && header[1] == 'T' && header[2] == 'h' && header[3] == 'd';
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /**
     * Safely loads a MIDI sequence, throwing InvalidMidiDataException if the header is invalid.
     */
    public static Sequence loadSequence(File file) throws IOException, InvalidMidiDataException
    {
        if (!isMidiFile(file))
        {
            throw new InvalidMidiDataException("Not a valid MIDI file (missing MThd header): " + file.getPath());
        }
        return MidiSystem.getSequence(file);
    }

    public static @Nullable String extractSequenceTitle(Sequence sequence)
    {
        for (Track track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                MidiMessage msg = track.get(i).getMessage();
                if (msg instanceof MetaMessage meta && meta.getType() == 0x03)
                {
                    byte[] data = meta.getData();
                    if (data != null && data.length > 0)
                    {
                        String text = new String(data, StandardCharsets.UTF_8).trim();
                        if (!text.isEmpty() && !text.matches("^[\\s\\p{C}]+$"))
                            return text;
                    }
                }
            }
        }
        return null;
    }
}
