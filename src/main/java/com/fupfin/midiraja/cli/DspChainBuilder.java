/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import com.fupfin.midiraja.dsp.*;

/**
 * Constructs the DSP effect chain from playback options.
 *
 * <p>
 * Effects are ordered by priority — lower numbers applied first (outermost in the chain):
 * </p>
 * <ul>
 * <li>{@value #COMPRESSOR_PRIORITY} — dynamics compressor ({@code --compress})</li>
 * <li>{@value #SPEAKER_PRIORITY} — vintage speaker coloration ({@code --speaker})</li>
 * </ul>
 */
class DspChainBuilder
{
    static final int COMPRESSOR_PRIORITY = 200;
    static final int SPEAKER_PRIORITY = 700;

    private final Optional<String> compress;
    private final Optional<String> speakerProfile;
    private final Optional<String> retroMode;
    private final double retroDrive;
    private final Optional<Integer> paulaWidth;
    private final boolean auxOut;

    DspChainBuilder(Optional<String> compress, Optional<String> speakerProfile,
            Optional<String> retroMode, double retroDrive, Optional<Integer> paulaWidth,
            boolean auxOut)
    {
        this.compress = compress;
        this.speakerProfile = speakerProfile;
        this.retroMode = retroMode;
        this.retroDrive = retroDrive;
        this.paulaWidth = paulaWidth;
        this.auxOut = auxOut;
    }

    /** Builds the full DSP chain wrapping {@code sink}. */
    AudioProcessor build(AudioProcessor sink)
    {
        var entries = new ArrayList<DspEntry>();
        compress.ifPresent(preset -> entries.add(compressorEntry(preset)));
        speakerProfile.ifPresent(profile -> entries.add(speakerEntry(profile)));
        entries.sort(Comparator.comparingInt(DspEntry::priority).reversed());

        AudioProcessor pipeline = sink;
        for (var e : entries)
            pipeline = e.factory().apply(pipeline);
        return pipeline;
    }

    /** Wraps {@code next} with the retro hardware DAC filter if {@code --retro} is set. */
    AudioProcessor wrapRetro(AudioProcessor next)
    {
        return retroMode.map(mode -> buildRetroFilter(mode.toLowerCase(Locale.ROOT), next))
                .orElse(next);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private record DspEntry(int priority, Function<AudioProcessor, AudioProcessor> factory)
    {
    }

    private DspEntry compressorEntry(String preset)
    {
        return new DspEntry(COMPRESSOR_PRIORITY,
                next -> new DynamicsCompressor(parseCompressPreset(preset), next));
    }

    private DspEntry speakerEntry(String profile)
    {
        return new DspEntry(SPEAKER_PRIORITY, next -> buildSpeakerFilter(profile, next));
    }

    private static DynamicsCompressor.Preset parseCompressPreset(String value)
    {
        try
        {
            return DynamicsCompressor.Preset.valueOf(value.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException(
                    "Unknown --compress preset '" + value
                            + "'. Valid values: soft, gentle, moderate, aggressive");
        }
    }

    private AudioProcessor buildRetroFilter(String mode, AudioProcessor next)
    {
        return switch (mode)
        {
            case "compactmac" -> new CompactMacSimulatorFilter(true, auxOut, next);
            // Empirically measured from original RealSound demos: 15.2kHz carrier
            // (1.19318MHz / 78 steps ≈ 15.3kHz), 78 discrete levels (~6.3-bit).
            // 7-pole IIR (1 electrical τ=10µs + 6 mechanical τ=37.9µs) gives -3dB at 1.4kHz
            // and -68dB carrier suppression. No resonance peaks: spectral analysis of reference
            // RealSound recordings shows no constant-frequency peaks.
            case "pc" -> new OneBitHardwareFilter(true, "pwm", 15200.0, 78.0, 37.9, 8, retroDrive,
                    null, auxOut, next);
            // DAC522 technique: each audio sample is encoded as TWO 46-cycle pulses.
            // Two pulses together (92 cycles) ≈ the original 93-cycle 11kHz sample period,
            // but the carrier noise is now at 22.05kHz — above the hearing limit.
            // 32 discrete widths per pulse (6-37 out of 46 cycles, ~5-bit).
            case "apple2" ->
                new OneBitHardwareFilter(true, "pwm", 22050.0, 32.0, 28.4, 8, retroDrive, null, auxOut, next);
            case "spectrum" -> new SpectrumBeeperFilter(true, auxOut, next);
            case "covox", "disneysound" -> new CovoxDacFilter(true, next);
            case "amiga", "a500" -> new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A500,
                    resolvePaulaWidth(), next);
            case "a1200" -> new AmigaPaulaFilter(true, AmigaPaulaFilter.Profile.A1200,
                    resolvePaulaWidth(), next);
            default -> throw new IllegalArgumentException(
                    "Unknown retro hardware mode '" + retroMode.orElse(mode)
                            + "'. Valid values: compactmac, pc, apple2, spectrum, covox, disneysound, amiga/a500, a1200");
        };
    }

    private static AudioProcessor buildSpeakerFilter(String profile, AudioProcessor next)
    {
        String profileStr = profile.toUpperCase(Locale.ROOT).replace("-", "_");
        try
        {
            AcousticSpeakerFilter.Profile p = AcousticSpeakerFilter.Profile.valueOf(profileStr);
            return new AcousticSpeakerFilter(true, p, next);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException(
                    "Unknown speaker profile '" + profile
                            + "'. Valid values: tin-can, warm-radio, telephone, pc");
        }
    }

    private float resolvePaulaWidth()
    {
        int pct = paulaWidth.orElse(60);
        if (pct < 0 || pct > 300)
            throw new IllegalArgumentException(
                    "--paula-width must be between 0 and 300, got: " + pct);
        return 1.0f + pct / 100.0f;
    }
}
