/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.cli;

import com.midiraja.MidirajaCommand;
import com.midiraja.midi.NativeAudioEngine;
import com.midiraja.midi.gus.GusSynthProvider;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "gus", mixinStandardHelpOptions = true,
         description =
             "Play using Pure Java Gravis Ultrasound (GUS) Synthesizer")
public class GusCommand implements Callable<Integer> {
  @ParentCommand
  @org.jspecify.annotations.Nullable
  private MidirajaCommand parent;

  @Mixin @org.jspecify.annotations.Nullable private CommonOptions common;

  @Option(names = {"-p", "--patch-dir"}, description = "Directory containing GUS .pat files and gus.cfg (or timidity.cfg)")
  private Optional<File> patchDir = Optional.empty();

  @Option(names = {"--1bit"}, description = "1-Bit acoustic modulation strategy (\"pwm\" or \"dsd\"). If omitted, outputs standard 16-bit PCM.")
  private @org.jspecify.annotations.Nullable String oneBitMode;
  
  @Option(names = {"--realsound"}, description = "Authentic 1980s PC Speaker macro (Automatically applies --1bit pwm).")
  private boolean realSound = false;

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

  @Parameters(paramLabel = "<file>",
              description = "MIDI files or M3U playlists to play")
  private List<File> files = new java.util.ArrayList<>();

  @Override
  public Integer call() throws Exception
  {
      var p = java.util.Objects.requireNonNull(parent);
    String audioLib = AudioLibResolver.resolve();
    NativeAudioEngine audio = new NativeAudioEngine(audioLib);
    audio.init(44100, 2, 4096);
    
    com.midiraja.dsp.AudioProcessor pipeline = new com.midiraja.dsp.FloatToShortSink(audio);
    
    if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || lpfFreq.isPresent() || hpfFreq.isPresent()) {
        var eq = new com.midiraja.dsp.EqFilter(pipeline);
        eq.setParams(eqBass, eqMid, eqTreble);
        if (lpfFreq.isPresent()) eq.setLpf(lpfFreq.get());
        if (hpfFreq.isPresent()) eq.setHpf(hpfFreq.get());
        pipeline = eq;
    }
    if (tubeDrive.isPresent()) {
        pipeline = new com.midiraja.dsp.TubeSaturationFilter(pipeline, 1.0f + (tubeDrive.get() / 100.0f * 9.0f));
    }
    if (chorus.isPresent()) {
            pipeline = new com.midiraja.dsp.ChorusFilter(pipeline, chorus.get());
        }
        if (reverb.isPresent()) {
        
            float levelScale = reverbLevel / 100.0f;
            try {
                var preset = com.midiraja.dsp.ReverbFilter.Preset.valueOf(reverb.get().toUpperCase(java.util.Locale.ROOT));
                pipeline = new com.midiraja.dsp.ReverbFilter(pipeline, preset, levelScale);
            } catch (IllegalArgumentException e) {
                System.err.println("Warning: Unknown reverb preset '" + reverb.get() + "'. Using HALL.");
                pipeline = new com.midiraja.dsp.ReverbFilter(pipeline, com.midiraja.dsp.ReverbFilter.Preset.HALL, levelScale);
            }
    }
    
    if (eqBass != 50 || eqMid != 50 || eqTreble != 50 || tubeDrive.isPresent() || chorus.isPresent() || reverb.isPresent()) {
        pipeline = new com.midiraja.dsp.ShortToFloatFilter(pipeline);
    }
    
    String dirPath = patchDir.map(File::getAbsolutePath).orElse(null);

    String finalOneBit = realSound ? "pwm" : oneBitMode; 
    var provider = new GusSynthProvider(pipeline, dirPath, finalOneBit);

    var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(),
                                    p.isInTestMode());
    return runner.run(provider, true, Optional.empty(),
                      patchDir.map(File::getPath), files,
                      java.util.Objects.requireNonNull(common));
  }
}
