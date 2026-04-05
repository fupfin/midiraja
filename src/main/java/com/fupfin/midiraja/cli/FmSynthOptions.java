/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import picocli.CommandLine.Option;

import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.dsp.FloatToShortSink;
import com.fupfin.midiraja.midi.NativeAudioEngine;

/**
 * Shared FM-synthesizer options mixed into OplCommand and OpnCommand.
 */
public class FmSynthOptions
{
    @Option(names = { "-e",
            "--emulator" }, defaultValue = "0", description = "Emulator backend ID (default: 0). Available IDs are listed in the command footer.")
    public int emulator = 0;

    @Option(names = { "-c",
            "--chips" }, defaultValue = "4", description = "Number of chips to emulate (default: 4). More chips = more polyphony.")
    public int chips = 4;

    /** Builds the stereo FM synth audio pipeline (audio engine + DSP chain). */
    static AudioProcessor buildStereoFmPipeline(CommonOptions common, FxOptions fxOptions)
            throws Exception
    {
        var audio = new NativeAudioEngine(AudioLibResolver.resolve());
        audio.init(44100, 2, 4096);
        if (common.dumpWav.isPresent())
        {
            audio.enableDump(common.dumpWav.get());
        }
        AudioProcessor pipeline = new FloatToShortSink(audio);
        pipeline = common.buildDspChain(pipeline);
        pipeline = fxOptions.wrapWithFloatConversion(pipeline, common);
        return pipeline;
    }
}
