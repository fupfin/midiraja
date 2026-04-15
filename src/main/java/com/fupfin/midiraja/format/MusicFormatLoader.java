/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format;

import java.io.File;
import java.util.Locale;
import java.util.Set;
import javax.sound.midi.Sequence;

import com.fupfin.midiraja.format.mod.ModFileDetector;
import com.fupfin.midiraja.format.mod.ModParser;
import com.fupfin.midiraja.format.mod.ModToMidiConverter;
import com.fupfin.midiraja.format.vgm.VgmFileDetector;
import com.fupfin.midiraja.format.vgm.VgmParser;
import com.fupfin.midiraja.format.vgm.VgmToMidiConverter;
import com.fupfin.midiraja.midi.MidiUtils;

/**
 * Single entry point for loading any supported music file format into a MIDI {@link Sequence}.
 *
 * <p>
 * Supported formats: MIDI (.mid/.midi), VGM/VGZ, and Amiga ProTracker MOD.
 *
 * <p>
 * S3M, XM, and IT are intentionally not supported. Those formats are built around
 * specific PCM samples and instrument envelopes that have no meaningful MIDI equivalent;
 * accurate playback requires a dedicated module renderer such as libopenmpt.
 *
 * <p>
 * To add a new format: add its detector/parser/converter here and extend
 * {@link #isSupportedFile}.
 */
public final class MusicFormatLoader
{
    private MusicFormatLoader()
    {
    }

    /**
     * Detects the format of {@code file} and converts it to a {@link Sequence}.
     *
     * @param mutedChannels
     *            MIDI channel indices (0-based) to silence during conversion;
     *            ignored for plain MIDI files
     */
    public static Sequence load(File file, Set<Integer> mutedChannels) throws Exception
    {
        if (VgmFileDetector.isVgmFile(file))
            return new VgmToMidiConverter(mutedChannels).convert(new VgmParser().parse(file));
        if (ModFileDetector.isModFile(file))
            return new ModToMidiConverter(mutedChannels).convert(new ModParser().parse(file));
        return MidiUtils.loadSequence(file);
    }

    /**
     * Returns {@code true} if {@code fileName} has an extension recognised by this loader.
     * Used by {@code PlaylistParser} to filter directory scans.
     */
    public static boolean isSupportedFile(String fileName)
    {
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".mid") || name.endsWith(".midi")
                || name.endsWith(".vgm") || name.endsWith(".vgz")
                || name.endsWith(".mod");
    }
}
