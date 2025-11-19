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

@Command(name = "gus",
         description =
             "Play using Pure Java Gravis Ultrasound (GUS) Synthesizer")
public class GusCommand implements Callable<Integer> {
  @ParentCommand
  @org.jspecify.annotations.Nullable
  private MidirajaCommand parent;

  @Mixin @org.jspecify.annotations.Nullable private CommonOptions common;

  @Option(names = {"-p", "--patch-dir"}, description = "Directory containing GUS .pat files and gus.cfg (or timidity.cfg)")
  private Optional<File> patchDir = Optional.empty();

  @Option(names = {"--1bit"}, description = "Crush output to a 1-bit PC Speaker style PWM signal (RealSound effect).")
  private boolean oneBitMode = false;

  @Parameters(paramLabel = "<file>",
              description = "MIDI files or M3U playlists to play")
  private List<File> files = new java.util.ArrayList<>();

  @Override
  public Integer call() throws Exception
  {
      // ... debug logs removed ...

      var p = java.util.Objects.requireNonNull(parent);
    String audioLib = AudioLibResolver.resolve();
    NativeAudioEngine audio = new NativeAudioEngine(audioLib);
    String dirPath = patchDir.map(File::getAbsolutePath).orElse(null);

    var provider = new GusSynthProvider(audio, dirPath, oneBitMode);

    var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(),
                                    p.isInTestMode());
    return runner.run(provider, true, Optional.empty(),
                      patchDir.map(File::getPath), files,
                      java.util.Objects.requireNonNull(common));
  }
}
