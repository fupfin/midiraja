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

    @Option(names = {"--dump-wav"}, description = "Dump the real-time audio output to a specified WAV file.")
    public Optional<String> dumpWav = Optional.empty();

    @Option(names = {"--retro"}, description = "Retro hardware physical acoustic simulation (mac128k, realsound, ibmpc, apple2, spectrum, covox, disneysound, amiga)")
    public Optional<String> retroMode = Optional.empty();

    @Option(names = {"--speaker"}, description = "Vintage speaker acoustic simulation (tin-can, warm-radio, none)")
    public Optional<String> speakerProfile = Optional.empty();




    @ArgGroup(exclusive = true, multiplicity = "0..1")
    public UiModeOptions uiOptions = new UiModeOptions();

    public com.fupfin.midiraja.dsp.AudioProcessor wrapRetroPipeline(com.fupfin.midiraja.dsp.AudioProcessor sink) {
        com.fupfin.midiraja.dsp.AudioProcessor pipeline = sink;
        
        // 1. Acoustic Speaker Simulation (Applied BEFORE final DAC to shape the signal)
        if (speakerProfile.isPresent()) {
            String profileStr = speakerProfile.get().toUpperCase(java.util.Locale.ROOT).replace("-", "_");
            try {
                com.fupfin.midiraja.dsp.AcousticSpeakerFilter.Profile profile = 
                    com.fupfin.midiraja.dsp.AcousticSpeakerFilter.Profile.valueOf(profileStr);
                pipeline = new com.fupfin.midiraja.dsp.AcousticSpeakerFilter(true, profile, pipeline);
            } catch (IllegalArgumentException e) {
                System.err.println("Warning: Unknown speaker profile '" + profileStr + "'. Ignoring.");
            }
        }

        // 2. Retro DAC Conversion
        if (retroMode.isPresent()) {
            String mode = retroMode.get().toLowerCase(java.util.Locale.ROOT);
            switch (mode) {
                case "mac128k":
                    pipeline = new com.fupfin.midiraja.dsp.Mac128kSimulatorFilter(true, pipeline);
                    break;
                case "ibmpc":
                case "1bit":
                case "realsound": 
                    pipeline = new com.fupfin.midiraja.dsp.OneBitHardwareFilter(true, "pwm", 18600.0, 64.0, 0.45f, pipeline);
                    break;
                case "covox":
                case "8bit":
                    pipeline = new com.fupfin.midiraja.dsp.CovoxDacFilter(true, pipeline);
                    break;
                case "apple2":
                    pipeline = new com.fupfin.midiraja.dsp.OneBitHardwareFilter(true, "pwm", 11025.0, 93.0, 0.35f, pipeline);
                    break;
                case "spectrum":
                    pipeline = new com.fupfin.midiraja.dsp.OneBitHardwareFilter(true, "pwm", 17500.0, 200.0, 0.50f, pipeline);
                    break;
                case "amiga":
                case "disneysound":
                    pipeline = new com.fupfin.midiraja.dsp.CovoxDacFilter(true, pipeline);
                    break;
                default:
                    System.err.println("Warning: Unknown retro hardware mode '" + mode + "'. Falling back to clean output.");
                    break;
            }
        }
        return pipeline;
    }

}
