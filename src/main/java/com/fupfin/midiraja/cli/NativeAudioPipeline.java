/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.dsp.FloatToShortSink;
import com.fupfin.midiraja.dsp.SpectrumAnalyzerFilter;
import com.fupfin.midiraja.midi.NativeAudioEngine;

/**
 * Audio pipeline paired with its {@link SpectrumAnalyzerFilter} reference.
 */
public record NativeAudioPipeline(AudioProcessor pipeline, SpectrumAnalyzerFilter spectrumFilter)
{
    /**
     * Builds a full audio pipeline: {@code NativeAudioEngine → FloatToShortSink →
     * SpectrumAnalyzerFilter → DSP chain → FX conversion}.
     */
    public static NativeAudioPipeline build(int channels, CommonOptions common, FxOptions fxOptions)
            throws Exception
    {
        var audio = new NativeAudioEngine(AudioLibResolver.resolve());
        audio.init(44100, channels, 4096);
        if (common.dumpWav.isPresent())
            audio.enableDump(common.dumpWav.get());
        AudioProcessor pipeline = new FloatToShortSink(audio, channels);
        var spectrumFilter = new SpectrumAnalyzerFilter(pipeline);
        pipeline = common.buildDspChain(spectrumFilter);
        pipeline = fxOptions.wrapWithFloatConversion(pipeline, common);
        return new NativeAudioPipeline(pipeline, spectrumFilter);
    }

}
