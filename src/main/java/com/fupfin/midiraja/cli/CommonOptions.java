/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

import com.fupfin.midiraja.dsp.*;

/**
 * Shared playback options mixed into every command (root and all subcommands).
 */
public class CommonOptions
{
    // ── Playback control ─────────────────────────────────────────────────────

    @Option(names = { "-v",
            "--volume" }, description = "Initial volume percentage. For internal synths with DSP: 0-150 (>100 boosts output, may clip). For external MIDI: 0-100.", defaultValue = "100")
    public int volume = 100;

    @Option(names = { "-x",
            "--speed" }, description = "Playback speed multiplier (e.g. 1.0, 1.2).", defaultValue = "1.0")
    public double speed = 1.0;

    @Option(names = { "-S", "--start" }, description = "Playback start time (e.g. 01:10:12, 05:30, or 90 for seconds).")
    public Optional<String> startTime = Optional.empty();

    @Option(names = { "-t",
            "--transpose" }, description = "Transpose by semitones (e.g. 12 for one octave up, -5 for down).")
    public Optional<Integer> transpose = Optional.empty();

    @Option(names = { "-s", "--shuffle" }, description = "Shuffle the playlist before playing.")
    public boolean shuffle;

    @Option(names = { "-r", "--loop" }, description = "Loop the playlist indefinitely.")
    public boolean loop;

    @Option(names = { "-R", "--recursive" }, description = "Recursively search for MIDI files in given directories.")
    public boolean recursive;

    @Option(names = {
            "--log" }, paramLabel = "LEVEL", description = "Enable logging at the given level (error, warn, info, debug). "
                    + "Written to the midiraja log file; debug also echoes to stderr.")
    public Optional<String> logLevel = Optional.empty();

    /**
     * Parses the {@code --start} string into microseconds.
     * Returns empty if no start time was specified or the value is blank.
     */
    public Optional<Long> startTimeMicroseconds()
    {
        return startTime
                .filter(s -> !s.isBlank())
                .map(CommonOptions::parseTimeToMicroseconds);
    }

    private static long parseTimeToMicroseconds(String timeStr)
    {
        try
        {
            String[] parts = timeStr.trim().split(":", -1);
            long seconds = 0;
            for (String part : parts)
            {
                seconds = seconds * 60 + Long.parseLong(part);
            }
            return seconds * 1000000L;
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    /** Returns true when log level is info or debug (enables stack traces and detailed messages). */
    public boolean isVerbose()
    {
        return logLevel.map(l -> l.equals("info") || l.equals("debug")).orElse(false);
    }

    @Option(names = {
            "--ignore-sysex" }, description = "Filter out hardware-specific System Exclusive (SysEx) messages.")
    public boolean ignoreSysex;

    @Option(names = {
            "--reset" }, description = "Send a SysEx reset before each track (gm, gm2, gs, xg, mt32, or raw hex "
                    + "like F0...F7).")
    public Optional<String> resetType = Optional.empty();

    @Option(names = { "--dump-wav" }, description = "Dump the real-time audio output to a specified WAV file.")
    public Optional<String> dumpWav = Optional.empty();

    // ── DSP effects ──────────────────────────────────────────────────────────

    @Option(names = {
            "--compress" }, paramLabel = "PRESET", description = "Dynamics compressor preset applied before the retro DAC stage "
                    + "(soft, gentle, moderate, aggressive). Boosts quiet passages to use more "
                    + "of the hardware dynamic range, improving perceived S/N in retro modes. "
                    + "Also useful without --retro as a general loudness-levelling stage.")
    public Optional<String> compress = Optional.empty();

    @Option(names = {
            "--retro" }, description = "Retro hardware physical acoustic simulation (compactmac, pc, apple2, spectrum, covox, disneysound, amiga/a500, a1200)")
    public Optional<String> retroMode = Optional.empty();

    @Option(names = {
            "--retro-drive" }, paramLabel = "GAIN", description = "Drive gain for --retro pc and --retro apple2 (default: 4.0). "
                    + "Higher values use more PWM duty-cycle levels, improving S/N for quiet "
                    + "input. Rule of thumb: GAIN ≈ 1 / peak_amplitude. "
                    + "Signals above 1/GAIN will be hard-clipped.", defaultValue = "4.0")
    public double retroDrive = 4.0;

    @Option(names = {
            "--paula-width" }, paramLabel = "PCT", description = "Stereo width for Amiga Paula modes (0-300). "
                    + "0=original stereo, 60=default (Paula hard-pan feel), 100=maximum safe. "
                    + "Values above 100 may cause clipping.")
    public Optional<Integer> paulaWidth = Optional.empty();

    @Option(names = {
            "--speaker" }, description = "Vintage speaker acoustic simulation (tin-can, warm-radio, telephone, pc, none)")
    public Optional<String> speakerProfile = Optional.empty();

    @Option(names = { "--aux" }, description = "Bypass internal speaker simulation for retro modes that model one "
            + "(compactmac, pc, apple2, spectrum). Outputs the raw electrical signal "
            + "instead of the speaker-filtered sound. Ignored by amiga, covox.")
    public boolean auxOut = false;

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    public UiModeOptions uiOptions = new UiModeOptions();

    @Option(names = {
            "--quiet" }, description = "Suppress all terminal output. Useful for scripting and background playback.")
    public boolean quietMode;

    @Option(names = {
            "--export-midi" }, paramLabel = "FILE", description = "Convert the input file to MIDI and write to FILE without playing.")
    public Optional<File> exportMidi = Optional.empty();

    @Option(names = {
            "--mute" }, paramLabel = "CHANNELS", description = "Comma-separated MIDI channel numbers (1-based, matching the UI display) or ranges to silence "
                    + "during VGM playback. Examples: --mute 4-9 (YM2612 only), --mute 1-3,10 (PSG only). "
                    + "Channel map: 1-3=PSG tone, 4-9=YM2612, 10=PSG noise, 11-15=SCC.")
    private Optional<String> muteChannels = Optional.empty();

    /** Parses --mute (1-based channel numbers) into a set of 0-based MIDI channel indices. */
    public Set<Integer> parsedMutedChannels(PrintStream err)
    {
        if (muteChannels.isEmpty() || muteChannels.get().isBlank())
            return Set.of();
        var channels = new HashSet<Integer>();
        for (var token : muteChannels.get().split(",", -1))
        {
            var part = token.trim();
            if (part.isEmpty())
                continue;
            try
            {
                int dash = part.indexOf('-');
                if (dash > 0)
                {
                    int from = Integer.parseInt(part.substring(0, dash)) - 1;
                    int to = Integer.parseInt(part.substring(dash + 1)) - 1;
                    for (int ch = from; ch <= to; ch++)
                        channels.add(ch);
                }
                else
                {
                    channels.add(Integer.parseInt(part) - 1);
                }
            }
            catch (NumberFormatException e)
            {
                err.println("Warning: invalid --mute channel '" + part
                        + "' (expected number or range, e.g. 4 or 4-9)");
            }
        }
        return Set.copyOf(channels);
    }

    // ── DSP chain construction ────────────────────────────────────────────────

    /**
     * Builds the DSP effect chain for this command's options and returns the outermost
     * {@link AudioProcessor} wrapping {@code sink}.
     *
     * <p>
     * Effects are ordered by their DSP priority — lower numbers are applied first
     * (outermost in the chain). Current priority assignments:
     * </p>
     * <ul>
     * <li>200 — dynamics compressor ({@code --compress})</li>
     * <li>700 — vintage speaker coloration ({@code --speaker})</li>
     * </ul>
     *
     * <p>
     * Note: the retro hardware DAC filter ({@code --retro}) is intentionally applied
     * in the float pipeline, <em>before</em> spatial effects (reverb). See
     * {@link #wrapRetroFilter(AudioProcessor)}.
     * </p>
     */
    public AudioProcessor buildDspChain(AudioProcessor sink)
    {
        var entries = new ArrayList<DspEntry>();

        // Priority 200: dynamics compressor — before retro DAC so quiet passages
        // use more of the quantiser/PWM dynamic range.
        compress.ifPresent(preset -> entries.add(compressorEntry(preset)));

        // Priority 700: vintage speaker coloration — after DAC, shapes the final tone.
        speakerProfile.ifPresent(profile -> entries.add(speakerEntry(profile)));

        // Sort descending so the innermost processor (highest priority) is built first.
        entries.sort(Comparator.comparingInt(DspEntry::priority).reversed());

        AudioProcessor pipeline = sink;
        for (var e : entries)
            pipeline = e.factory().apply(pipeline);
        return pipeline;
    }

    /**
     * Wraps {@code next} with the retro hardware DAC filter if {@code --retro} is set.
     * Called from the float pipeline so the retro filter receives the already-filtered
     * signal before spatial effects (reverb) are applied.
     */
    AudioProcessor wrapRetroFilter(AudioProcessor next)
    {
        return retroMode.map(mode -> buildRetroFilter(mode.toLowerCase(Locale.ROOT), next))
                .orElse(next);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** One entry in the DSP chain: a priority and a factory that wraps the downstream processor. */
    private record DspEntry(int priority, Function<AudioProcessor, AudioProcessor> factory)
    {
    }

    private DspEntry compressorEntry(String preset)
    {
        return new DspEntry(200, next -> new DynamicsCompressor(parseCompressPreset(preset), next));
    }

    private DspEntry speakerEntry(String profile)
    {
        return new DspEntry(700, next -> buildSpeakerFilter(profile, next));
    }

    private DynamicsCompressor.Preset parseCompressPreset(String value)
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
                    "Unknown retro hardware mode '" + retroMode.get()
                            + "'. Valid values: compactmac, pc, apple2, spectrum, covox, disneysound, amiga/a500, a1200");
        };
    }

    private AudioProcessor buildSpeakerFilter(String profile, AudioProcessor next)
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
                    "Unknown speaker profile '" + profile + "'. Valid values: tin-can, warm-radio, telephone, pc");
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
