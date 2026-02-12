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
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Plays MIDI files through the built-in Munt MT-32 emulator.
 */
@Command(name = "munt", aliases = {"mt32", "mt32emu", "lapc1", "cm32l"}, mixinStandardHelpOptions = true,
    description = "Play with MT-32 emulation (Roland MT-32/CM-32L).",
    footer = {"", "Requires MT32_CONTROL.ROM and MT32_PCM.ROM in the specified ROM directory."})
public class MuntCommand implements Callable<Integer>
{
    @ParentCommand @Nullable private MidirajaCommand parent;

    @Parameters(
        index = "0", description = "Directory containing MT32_CONTROL.ROM and MT32_PCM.ROM.")
    private File romDir = new File("");

    @Parameters(index = "1..*", arity = "1..*",
        description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Mixin private CommonOptions common = new CommonOptions();

    @Override public Integer call() throws Exception
    {
        var p = java.util.Objects.requireNonNull(parent);

        String audioLib = AudioLibResolver.resolve();
        var audio = new com.fupfin.midiraja.midi.NativeAudioEngine(audioLib);
        audio.init(32000, 2, 4096);
        com.fupfin.midiraja.dsp.AudioProcessor pipeline = new com.fupfin.midiraja.dsp.FloatToShortSink(audio);
        if (common != null && common.oneBitMode.isPresent()) {
            pipeline = new com.fupfin.midiraja.dsp.OneBitAcousticSimulatorFilter(true, common.oneBitMode.get(), pipeline);
        }
        if (common != null && common.eightBitMode) {
            pipeline = new com.fupfin.midiraja.dsp.EightBitQuantizerFilter(true, pipeline);
        }
        var bridge = new com.fupfin.midiraja.midi.FFMMuntNativeBridge();
        var provider = new com.fupfin.midiraja.midi.MuntSynthProvider(bridge, pipeline);

        var runner =
            new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(
            provider, true, Optional.empty(), Optional.of(romDir.getPath()), files, common);
    }
}
