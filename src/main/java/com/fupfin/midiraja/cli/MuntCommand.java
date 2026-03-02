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
    private final File romDir = new File("");

    @Parameters(index = "1..*", arity = "1..*",
        description = "MIDI files, directories, or .m3u playlists to play.")
    private List<File> files = new ArrayList<>();

    @Mixin private final CommonOptions common = new CommonOptions();

    @Override public Integer call() throws Exception
    {
        var p = java.util.Objects.requireNonNull(parent);

        String audioLib = AudioLibResolver.resolve();
        var audio = new com.fupfin.midiraja.midi.NativeAudioEngine(audioLib);
        audio.init(32000, 2, 4096);
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
                    // ZX Spectrum Z80 at 3.5MHz allows high carrier (17.5kHz) and high precision (200 levels).
                    // Its speaker was tiny and piercing, so we use a lighter filter (alpha 0.50)
                    pipeline = new com.fupfin.midiraja.dsp.OneBitHardwareFilter(true, "pwm", 17500.0, 200.0, 0.50f, pipeline);
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

        var bridge = new com.fupfin.midiraja.midi.FFMMuntNativeBridge();
        var provider = new com.fupfin.midiraja.midi.MuntSynthProvider(bridge, pipeline);

        var runner =
            new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());
        return runner.run(
            provider, true, Optional.empty(), Optional.of(romDir.getPath()), files, common);
    }
}
