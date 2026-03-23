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
    void buildDspChain_noOptionsSet_returnsSinkUnchanged()
    {
        AudioProcessor result = common.buildDspChain(sink);

        assertSame(sink, result,
                "With no retro/speaker options, pipeline should be the original sink");
    }

    @Test
    void buildDspChain_unknownSpeakerProfile_throwsIllegalArgument()
    {
        common.speakerProfile = Optional.of("totally-unknown-profile");

        assertThrows(IllegalArgumentException.class,
                () -> common.buildDspChain(sink),
                "Unknown speaker profile should throw IllegalArgumentException");
    }

    @Test
    void buildDspChain_knownSpeakerProfile_wrapsWithAcousticFilter()
    {
        common.speakerProfile = Optional.of("tin-can");

        AudioProcessor result = common.buildDspChain(sink);

        assertNotSame(sink, result,
                "Known speaker profile 'tin-can' should wrap the pipeline with a filter");
        assertInstanceOf(com.fupfin.midiraja.dsp.AcousticSpeakerFilter.class, result,
                "Should be an AcousticSpeakerFilter");
    }

    @Test
    void buildDspChain_knownSpeakerProfileCaseInsensitive_wrapsWithAcousticFilter()
    {
        common.speakerProfile = Optional.of("TIN-CAN");

        AudioProcessor result = common.buildDspChain(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.AcousticSpeakerFilter.class, result);
    }

    @Test
    void wrapRetroFilter_retroModeCompactMac_wrapsWithCompactMacFilter()
    {
        common.retroMode = Optional.of("compactmac");

        AudioProcessor result = common.wrapRetroFilter(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.CompactMacSimulatorFilter.class, result);
    }


    @Test
    void wrapRetroFilter_retroModePc_wrapsWithOneBitFilter()
    {
        common.retroMode = Optional.of("pc");

        AudioProcessor result = common.wrapRetroFilter(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    @Test
    void wrapRetroFilter_retroModeCovox_wrapsWithCovoxFilter()
    {
        common.retroMode = Optional.of("covox");

        AudioProcessor result = common.wrapRetroFilter(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.CovoxDacFilter.class, result);
    }


    @Test
    void wrapRetroFilter_retroModeApple2_wrapsWithOneBitFilter()
    {
        common.retroMode = Optional.of("apple2");

        AudioProcessor result = common.wrapRetroFilter(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    @Test
    void wrapRetroFilter_retroModeSpectrum_wrapsWithSpectrumBeeperFilter()
    {
        common.retroMode = Optional.of("spectrum");

        AudioProcessor result = common.wrapRetroFilter(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.SpectrumBeeperFilter.class, result);
    }


    @Test
    void wrapRetroFilter_retroModeDisneysound_wrapsWithCovoxFilter()
    {
        common.retroMode = Optional.of("disneysound");

        AudioProcessor result = common.wrapRetroFilter(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.CovoxDacFilter.class, result);
    }

    @Test
    void wrapRetroFilter_unknownRetroMode_throwsIllegalArgument()
    {
        common.retroMode = Optional.of("totallyunknownmode");

        assertThrows(IllegalArgumentException.class,
                () -> common.wrapRetroFilter(sink),
                "Unknown retro mode should throw IllegalArgumentException");
    }

    @Test
    void wrapRetroFilter_retroModeCaseInsensitive_wrapsCorrectly()
    {
        common.retroMode = Optional.of("PC");

        AudioProcessor result = common.wrapRetroFilter(sink);

        assertNotSame(sink, result);
        assertInstanceOf(com.fupfin.midiraja.dsp.OneBitHardwareFilter.class, result);
    }

    // ── startTimeMicroseconds ─────────────────────────────────────────────────

    @Test
    void startTimeMicroseconds_seconds_returnsCorrectValue()
    {
        common.startTime = Optional.of("30");
        assertEquals(Optional.of(30_000_000L), common.startTimeMicroseconds());
    }

    @Test
    void startTimeMicroseconds_minutesAndSeconds_returnsCorrectValue()
    {
        common.startTime = Optional.of("1:30");
        assertEquals(Optional.of(90_000_000L), common.startTimeMicroseconds());
    }

    @Test
    void startTimeMicroseconds_hoursMinutesSeconds_returnsCorrectValue()
    {
        common.startTime = Optional.of("1:00:00");
        assertEquals(Optional.of(3_600_000_000L), common.startTimeMicroseconds());
    }

    @Test
    void startTimeMicroseconds_zero_returnsZeroWrapped()
    {
        common.startTime = Optional.of("0");
        assertEquals(Optional.of(0L), common.startTimeMicroseconds());
    }

    @Test
    void startTimeMicroseconds_blank_returnsEmpty()
    {
        common.startTime = Optional.of("   ");
        assertEquals(Optional.empty(), common.startTimeMicroseconds());
    }

    @Test
    void startTimeMicroseconds_nonNumeric_returnsZeroWrapped()
    {
        common.startTime = Optional.of("abc");
        assertEquals(Optional.of(0L), common.startTimeMicroseconds());
    }

    @Test
    void startTimeMicroseconds_leadingTrailingWhitespace_ignored()
    {
        common.startTime = Optional.of("  30  ");
        assertEquals(Optional.of(30_000_000L), common.startTimeMicroseconds());
    }

    @Test
    void startTimeMicroseconds_notSet_returnsEmpty()
    {
        // startTime defaults to Optional.empty()
        assertEquals(Optional.empty(), common.startTimeMicroseconds());
    }
}
