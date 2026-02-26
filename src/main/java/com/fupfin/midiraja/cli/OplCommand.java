/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Plays MIDI files using the built-in libADLMIDI OPL2/OPL3 FM synthesizer.
 */
@Command(name = "opl", mixinStandardHelpOptions = true,
    description = "Play with OPL2/OPL3 FM synthesis (AdLib / Sound Blaster).",
    footer = {"",
        "Emulator IDs: 0=Nuked OPL3 v1.8, 1=Nuked v1.7.4, 5=ESFMu, 6=MAME OPL2, 7=YMFM OPL2, "
        + "8=YMFM OPL3"})
public class OplCommand implements Callable<Integer>
{
    @ParentCommand @Nullable private MidirajaCommand parent;

    @Parameters(arity = "1..*", description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {"-b", "--bank"}, arity = "0..1", fallbackValue = "",
        description =
            "Embedded bank number (0-75) or path to a .wopl file. Default: bank 0 (General MIDI).")
    private Optional<String> bank = Optional.empty();

    @Option(names = {"-e", "--emulator"}, defaultValue = "0",
        description = "Emulator backend: 0:Nuked-1.8 (Default), 1:Nuked-1.7.4, 2:DosBox, 3:Opal, 4:Java, 5:ESFMu, 6:MAME, 7:YMFM-OPL2, 8:YMFM-OPL3")
    private int emulator = 0;

    @Mixin private FmSynthOptions fmOptions = new FmSynthOptions();

        @Option(names = {"--bass"}, defaultValue = "50", description = "Adjust bass gain (0-100%%). Default: 50 (neutral).")
    private float eqBass = 100;

    @Option(names = {"--mid"}, defaultValue = "50", description = "Adjust mid gain (0-100%%). Default: 50 (neutral).")
    private float eqMid = 100;

    @Option(names = {"--treble"}, defaultValue = "50", description = "Adjust treble gain (0-100%%). Default: 50 (neutral).")
    private float eqTreble = 100;

        @Option(names = {"--lpf"}, description = "Low-Pass Filter cutoff frequency in Hz (e.g. 2000). Cuts off high frequencies.")
    private Optional<Float> lpfFreq = Optional.empty();

    @Option(names = {"--hpf"}, description = "High-Pass Filter cutoff frequency in Hz (e.g. 500). Cuts off low frequencies.")
    private Optional<Float> hpfFreq = Optional.empty();

    @Option(names = {"--chorus"}, description = "Apply classic stereo chorus effect. (Intensity: 0-100%%, Recommended: 30-70).")
    private Optional<Float> chorus = Optional.empty();

    @Option(names = {"--reverb"}, description = "Apply algorithmic reverb preset. (Options: room, chamber, hall, plate, spring, cave).")
    private Optional<String> reverb = Optional.empty();

    @Option(names = {"--reverb-level"}, defaultValue = "50", description = "Reverb wet level intensity (0-100%%). Default: 50 (neutral).")
    private float reverbLevel = 50;

    @Option(names = {"--tube"}, description = "Apply analog vacuum tube saturation. (Range: 0-100%%, Recommended for warmth: 10-20, for punch: 30-50).")
    private Optional<Float> tubeDrive = Optional.empty();

    @Mixin private CommonOptions common = new CommonOptions();

    @Override public Integer call() throws Exception
    {
        var p = java.util.Objects.requireNonNull(parent);

        String audioLib = AudioLibResolver.resolve();
        var audio = new com.fupfin.midiraja.midi.NativeAudioEngine(audioLib);
        audio.init(44100, 2, 4096);
        if (common != null && common.dumpWav.isPresent()) { audio.enableDump(common.dumpWav.get()); }
        
        com.fupfin.midiraja.dsp.AudioProcessor pipeline = new com.fupfin.midiraja.dsp.FloatToShortSink(audio);
        // 1. Acoustic Speaker Simulation (Applied BEFORE final DAC to shape the signal)
        // Wait, physical speakers come AFTER the DAC.
        // But our pipeline runs BACKWARDS. The signal flows from the synth (upstream) to the sink.
        // The FloatToShortSink calls `pipeline.process()`. 
        // So the outermost wrapper is executed LAST in the physical signal chain.
        // The physical chain is: Synth -> Filter -> DAC -> Speaker.
        // Therefore, we must wrap in this order:
        // pipeline = Speaker(pipeline)
        // pipeline = DAC(pipeline)
        
        if (common != null && common.speakerProfile.isPresent()) {
            String profileStr = common.speakerProfile.get().toUpperCase(java.util.Locale.ROOT).replace("-", "_");
            try {
                com.fupfin.midiraja.dsp.AcousticSpeakerFilter.Profile profile = 
                    com.fupfin.midiraja.dsp.AcousticSpeakerFilter.Profile.valueOf(profileStr);
                pipeline = new com.fupfin.midiraja.dsp.AcousticSpeakerFilter(true, profile, pipeline);
            } catch (IllegalArgumentException e) {
                System.err.println("Warning: Unknown speaker profile '" + profileStr + "'. Ignoring.");
            }
        }

        // 2. Retro DAC Conversion
        if (common != null && common.retroMode.isPresent()) {
            String mode = common.retroMode.get().toLowerCase(java.util.Locale.ROOT);
            switch (mode) {
                case "mac128k":
                    // mac128k is a monolithic filter combining DAC and Speaker for now
                    pipeline = new com.fupfin.midiraja.dsp.Mac128kSimulatorFilter(true, pipeline);
                    break;
                case "ibmpc":
                case "1bit":
                case "realsound": // legacy mapped to IBM PC
                    pipeline = new com.fupfin.midiraja.dsp.IbmPcDacFilter(true, "pwm", pipeline);
                    break;
                case "covox":
                case "8bit":
                    pipeline = new com.fupfin.midiraja.dsp.CovoxDacFilter(true, pipeline);
                    break;
                case "apple2":
                    pipeline = new com.fupfin.midiraja.dsp.Apple2DacFilter(true, pipeline);
                    break;
                case "amiga":
                case "disneysound":
                    // Fallbacks for planned features
                    pipeline = new com.fupfin.midiraja.dsp.CovoxDacFilter(true, pipeline);
                    break;
                default:
                    System.err.println("Warning: Unknown DAC mode '" + mode + "'. Ignoring.");
            }
        }

        
        if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || lpfFreq.isPresent() || hpfFreq.isPresent()) {
            var eq = new com.fupfin.midiraja.dsp.EqFilter(pipeline);
            eq.setParams(eqBass, eqMid, eqTreble);
            if (lpfFreq.isPresent()) eq.setLpf(lpfFreq.get());
            if (hpfFreq.isPresent()) eq.setHpf(hpfFreq.get());
            pipeline = eq;
        }
        if (tubeDrive.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.TubeSaturationFilter(pipeline, 1.0f + (tubeDrive.get() / 100.0f * 9.0f));
        }
        if (chorus.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.ChorusFilter(pipeline, chorus.get());
        }
        if (reverb.isPresent()) {
            
            float levelScale = reverbLevel / 100.0f;
            try {
                var preset = com.fupfin.midiraja.dsp.ReverbFilter.Preset.valueOf(reverb.get().toUpperCase(java.util.Locale.ROOT));
                pipeline = new com.fupfin.midiraja.dsp.ReverbFilter(pipeline, preset, levelScale);
            } catch (IllegalArgumentException e) {
                System.err.println("Warning: Unknown reverb preset '" + reverb.get() + "'. Using HALL.");
                pipeline = new com.fupfin.midiraja.dsp.ReverbFilter(pipeline, com.fupfin.midiraja.dsp.ReverbFilter.Preset.HALL, levelScale);
            }
        }
        
        if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || tubeDrive.isPresent() || chorus.isPresent() || reverb.isPresent() || (common != null && (common.retroMode.isPresent() || common.speakerProfile.isPresent()))) {
            pipeline = new com.fupfin.midiraja.dsp.ShortToFloatFilter(pipeline);
        }
        
        var bridge = new com.fupfin.midiraja.midi.FFMAdlMidiNativeBridge();
        var provider = new com.fupfin.midiraja.midi.AdlMidiSynthProvider(
            bridge, pipeline, emulator, fmOptions.chips, common.retroMode.orElse(null));

        // Resolve bank argument: "" → "bank:0", "14" → "bank:14", path → path
        String soundbankArg =
            bank.map(v -> v.isEmpty() ? "bank:0" : (v.matches("\\d+") ? "bank:" + v : v))
                .orElse("bank:0");

        var runner =
            new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(
            provider, true, Optional.empty(), Optional.of(soundbankArg), files, common);
    }
}
