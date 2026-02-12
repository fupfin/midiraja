/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.util.Optional;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

/**
 * Shared playback options mixed into every command (root and all subcommands).
 */
public class CommonOptions
{
    @Option(names = {"-v", "--volume"}, description = "Initial volume percentage (0-100).",
        defaultValue = "100")
    public int volume = 100;

    @Option(names = {"-x", "--speed"}, description = "Playback speed multiplier (e.g. 1.0, 1.2).",
        defaultValue = "1.0")
    public double speed = 1.0;

    @Option(names = {"-S", "--start"},
        description = "Playback start time (e.g. 01:10:12, 05:30, or 90 for seconds).")
    public Optional<String> startTime = Optional.empty();

    @Option(names = {"-t", "--transpose"},
        description = "Transpose by semitones (e.g. 12 for one octave up, -5 for down).")
    public Optional<Integer> transpose = Optional.empty();

    @Option(names = {"-s", "--shuffle"}, description = "Shuffle the playlist before playing.")
    public boolean shuffle;

    @Option(names = {"-r", "--loop"}, description = "Loop the playlist indefinitely.")
    public boolean loop;

    @Option(names = {"-R", "--recursive"},
        description = "Recursively search for MIDI files in given directories.")
    public boolean recursive;

    @Option(names = {"--verbose"}, description = "Show verbose error messages and stack traces.")
    public boolean verbose;

    @Option(names = {"--ignore-sysex"},
        description = "Filter out hardware-specific System Exclusive (SysEx) messages.")
    public boolean ignoreSysex;

    @Option(names = {"--reset"},
        description = "Send a SysEx reset before each track (gm, gm2, gs, xg, mt32, or raw hex "
                      + "like F0...F7).")
    public Optional<String> resetType = Optional.empty();

    
    @Option(names = {"--1bit"}, description = "1-Bit acoustic modulation strategy ('pwm' or 'dsd'). If omitted, outputs standard 16-bit PCM.")
    public Optional<String> oneBitMode = Optional.empty();

    @Option(names = {"--8bit"}, description = "8-Bit DAC quantization strategy. Simulates the sound of early Macintosh and Amiga sound chips.")
    public boolean eightBitMode;

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    public UiModeOptions uiOptions = new UiModeOptions();
}
