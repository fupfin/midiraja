/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.dsp.AudioProcessor;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the DSP pipeline construction logic in CommonOptions.
 */
class CommonOptionsTest
{
    /** A no-op sink used as the base of the pipeline under test. */
    static class NoOpSink implements AudioProcessor
    {
        @Override
        public void process(float[] left, float[] right, int frames)
        {
        }
    }

    private CommonOptions common;
    private AudioProcessor sink;

    @BeforeEach
    void setUp()
    {
        common = new CommonOptions();
        sink = new NoOpSink();
    }

    @Test
    void wrapRetroPipeline_noOptionsSet_returnsSinkUnchanged()
    {
        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertSame(sink, result,
                "With no retro/speaker options, pipeline should be the original sink");
    }

    @Test
    void wrapRetroPipeline_unknownSpeakerProfile_returnsSinkUnchanged()
    {
        common.speakerProfile = Optional.of("totally-unknown-profile");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        // Unknown profile is ignored; pipeline should be unchanged
        assertSame(sink, result,
                "Unknown speaker profile should be silently ignored, leaving sink unchanged");
    }

    @Test
    void wrapRetroPipeline_knownSpeakerProfile_wrapsWithAcousticFilter()
    {
        common.speakerProfile = Optional.of("tin-can");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result,
                "Known speaker profile 'tin-can' should wrap the pipeline with a filter");
        assertInstanceOf(com.fupfin.midiraja.dsp.AcousticSpeakerFilter.class, result,
                "Should be an AcousticSpeakerFilter");
    }

    @Test
    void wrapRetroPipeline_knownSpeakerProfileCaseInsensitive_wrapsWithAcousticFilter()
    {
        common.speakerProfile = Optional.of("TIN-CAN");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.AcousticSpeakerFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroModeMac128k_wrapsWithMacFilter()
    {
        common.retroMode = Optional.of("mac128k");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result,
                "mac128k mode should wrap the pipeline");
        assertInstanceOf(com.fupfin.midiraja.dsp.Mac128kSimulatorFilter.class, result,
                "Should be a Mac128kSimulatorFilter");
    }

    @Test
    void wrapRetroPipeline_retroModeIbmpc_wrapsWithOneBitFilter()
    {
        common.retroMode = Optional.of("ibmpc");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroMode1bit_wrapsWithOneBitFilter()
    {
        common.retroMode = Optional.of("1bit");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroModeRealsound_wrapsWithOneBitFilter()
    {
        common.retroMode = Optional.of("realsound");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroModeCovox_wrapsWithCovoxFilter()
    {
        common.retroMode = Optional.of("covox");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.CovoxDacFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroMode8bit_wrapsWithCovoxFilter()
    {
        common.retroMode = Optional.of("8bit");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.CovoxDacFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroModeApple2_wrapsWithOneBitFilter()
    {
        common.retroMode = Optional.of("apple2");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroModeSpectrum_wrapsWithOneBitFilter()
    {
        common.retroMode = Optional.of("spectrum");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroModeAmiga_wrapsWithCovoxFilter()
    {
        common.retroMode = Optional.of("amiga");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.CovoxDacFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_retroModeDisneysound_wrapsWithCovoxFilter()
    {
        common.retroMode = Optional.of("disneysound");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.CovoxDacFilter.class, result);
    }

    @Test
    void wrapRetroPipeline_unknownRetroMode_returnsSinkUnchanged()
    {
        common.retroMode = Optional.of("totallyunknownmode");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertSame(sink, result,
                "Unknown retro mode should fall through to default, leaving sink unchanged");
    }

    @Test
    void wrapRetroPipeline_retroModeCaseInsensitive_wrapsCorrectly()
    {
        common.retroMode = Optional.of("IBMPC");

        AudioProcessor result = common.wrapRetroPipeline(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }
}
