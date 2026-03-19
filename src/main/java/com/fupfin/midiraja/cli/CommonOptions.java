/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.dsp.*;

import java.util.Optional;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

/**
 * Shared playback options mixed into every command (root and all subcommands).
 */
public class CommonOptions
{
    private static final java.util.logging.Logger log =
            java.util.logging.Logger.getLogger(CommonOptions.class.getName());
    @Option(names = {"-v", "--volume"},
            description = "Initial volume percentage. For internal synths with DSP: 0-150 (>100 boosts output, may clip). For external MIDI: 0-100.",
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

    @Option(names = {"--debug"}, description = "Write debug-level log to the midiraja log file and echo to stderr.")
    public boolean debug;

    @Option(names = {"--ignore-sysex"},
            description = "Filter out hardware-specific System Exclusive (SysEx) messages.")
    public boolean ignoreSysex;

    @Option(names = {"--reset"},
            description = "Send a SysEx reset before each track (gm, gm2, gs, xg, mt32, or raw hex "
                    + "like F0...F7).")
    public Optional<String> resetType = Optional.empty();

    @Option(names = {"--dump-wav"},
            description = "Dump the real-time audio output to a specified WAV file.")
    public Optional<String> dumpWav = Optional.empty();

    @Option(names = {"--retro"},
            description = "Retro hardware physical acoustic simulation (compactmac, pc, apple2, spectrum, covox, disneysound)")
    public Optional<String> retroMode = Optional.empty();

    @Option(names = {"--speaker"},
            description = "Vintage speaker acoustic simulation (tin-can, warm-radio, none)")
    public Optional<String> speakerProfile = Optional.empty();



    @ArgGroup(exclusive = true, multiplicity = "0..1")
    public UiModeOptions uiOptions = new UiModeOptions();

    public AudioProcessor wrapRetroPipeline(AudioProcessor sink)
    {
        AudioProcessor pipeline = sink;

        // 1. Acoustic Speaker Simulation (Applied BEFORE final DAC to shape the signal)
        if (speakerProfile.isPresent())
        {
            String profileStr =
                    speakerProfile.get().toUpperCase(java.util.Locale.ROOT).replace("-", "_");
            try
            {
                AcousticSpeakerFilter.Profile profile =
                        AcousticSpeakerFilter.Profile.valueOf(profileStr);
                pipeline = new AcousticSpeakerFilter(true, profile, pipeline);
            }
            catch (IllegalArgumentException e)
            {
                log.warning("Unknown speaker profile '" + profileStr + "'. Ignoring.");
            }
        }

        // 2. Retro DAC Conversion
        if (retroMode.isPresent())
        {
            String mode = retroMode.get().toLowerCase(java.util.Locale.ROOT);
            pipeline = switch (mode)
            {
                case "compactmac" -> new CompactMacSimulatorFilter(true, pipeline);
                // Empirically measured from original RealSound demos: 15.2kHz carrier
                // (1.19318MHz / 78 steps ≈ 15.3kHz), 78 discrete levels (~6.3-bit).
                case "pc" -> new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 0.45f, pipeline);
                // DAC522 technique: each audio sample is encoded as TWO 46-cycle pulses.
                // Two pulses together (92 cycles) ≈ the original 93-cycle 11kHz sample period,
                // but the carrier noise is now at 22.05kHz — above the hearing limit.
                // 32 discrete widths per pulse (6-37 out of 46 cycles, ~5-bit).
                case "apple2" -> new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 0.55f, pipeline);
                case "spectrum" -> new SpectrumBeeperFilter(true, pipeline);
                case "covox", "disneysound" -> new CovoxDacFilter(true, pipeline);
                default ->
                {
                    log.warning("Unknown retro hardware mode '" + mode
                            + "'. Falling back to clean output.");
                    yield pipeline;
                }
            };
        }
        return pipeline;
    }

}
