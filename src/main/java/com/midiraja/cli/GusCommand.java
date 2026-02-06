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

  @Option(names = {"--tube"}, description = "Apply analog vacuum tube saturation. (Recommended: 1.5 - 2.0).")
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
    
    // Global Tube Saturation
    if (tubeDrive.isPresent()) {
        pipeline = new com.midiraja.dsp.TubeSaturationFilter(pipeline, tubeDrive.get());
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
