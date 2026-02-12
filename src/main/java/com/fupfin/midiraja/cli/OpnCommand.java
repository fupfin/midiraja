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
 * Plays MIDI files using the built-in libOPNMIDI OPN2/OPNA FM synthesizer.
 */
@Command(name = "opn", mixinStandardHelpOptions = true,
    description = "Play with OPN2 FM synthesis (Sega Genesis / PC-98).",
    footer = {"",
        "Emulator IDs: 0=MAME YM2612, 1=Nuked YM3438, 2=GENS, 3=YMFM OPN2, 4=NP2 OPNA, 5=MAME "
        + "YM2608, 6=YMFM OPNA"})
public class OpnCommand implements Callable<Integer>
{
    @ParentCommand @Nullable private MidirajaCommand parent;

    @Parameters(arity = "1..*", description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {"-b", "--bank"}, arity = "0..1", fallbackValue = "",
        description = "Path to a .wopn bank file. Default: built-in GM bank.")
    private Optional<String> bank = Optional.empty();

    @Option(names = {"-e", "--emulator"}, defaultValue = "0",
        description = "Emulator backend: 0:MAME-YM2612 (Default), 1:Nuked-YM3438, 2:GENS, 3:YMFM-OPN2, 4:NP2-OPNA, 5:MAME-YM2608, 6:YMFM-OPNA")
    private int emulator = 0;

    @Mixin private FmSynthOptions fmOptions = new FmSynthOptions();

    @Mixin private CommonOptions common = new CommonOptions();

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

    @Option(names = {"--tube"}, description = "Apply analog vacuum tube saturation. (Range: 0-100%%, Recommended: 10-20).")
    private Optional<Float> tubeDrive = Optional.empty();

    @Override public Integer call() throws Exception
    {
        var p = java.util.Objects.requireNonNull(parent);

        String audioLib = AudioLibResolver.resolve();
        var audio = new com.fupfin.midiraja.midi.NativeAudioEngine(audioLib);
        audio.init(44100, 2, 4096);
        
        com.fupfin.midiraja.dsp.AudioProcessor pipeline = new com.fupfin.midiraja.dsp.FloatToShortSink(audio);
        if (common != null && common.oneBitMode.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.OneBitAcousticSimulatorFilter(true, common.oneBitMode.get(), pipeline);
        }
        if (common != null && common.eightBitMode) {
            pipeline = new com.fupfin.midiraja.dsp.EightBitQuantizerFilter(true, pipeline);
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
        
        if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || tubeDrive.isPresent() || chorus.isPresent() || reverb.isPresent() || common.oneBitMode.isPresent()) {
            if (common != null && common.oneBitMode.isPresent()) {
                pipeline = new com.fupfin.midiraja.dsp.LegacyProcessorSink(pipeline, 
                    java.util.List.of(new com.fupfin.midiraja.dsp.OneBitAcousticSimulator(44100, common.oneBitMode.orElse("pwm"))));
            }
            pipeline = new com.fupfin.midiraja.dsp.ShortToFloatFilter(pipeline);
        }
        
        var bridge = new com.fupfin.midiraja.midi.FFMOpnMidiNativeBridge();
        var provider = new com.fupfin.midiraja.midi.OpnMidiSynthProvider(
            bridge, pipeline, emulator, fmOptions.chips, common.oneBitMode.orElse("pwm"));

        // bank: empty string = default built-in GM bank; otherwise WOPN file path
        String soundbankArg = bank.orElse("");

        var runner =
            new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(
            provider, true, Optional.empty(), Optional.of(soundbankArg), files, common);
    }
}
