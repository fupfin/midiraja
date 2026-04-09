/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

import com.fupfin.midiraja.dsp.AudioProcessor;

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
     */
    public AudioProcessor buildDspChain(AudioProcessor sink)
    {
        return builder().build(sink);
    }

    /**
     * Wraps {@code next} with the retro hardware DAC filter if {@code --retro} is set.
     * Called from the float pipeline so the retro filter receives the already-filtered
     * signal before spatial effects (reverb) are applied.
     */
    AudioProcessor wrapRetroFilter(AudioProcessor next)
    {
        return builder().wrapRetro(next);
    }

    private DspChainBuilder builder()
    {
        return new DspChainBuilder(compress, speakerProfile, retroMode, retroDrive, paulaWidth,
                auxOut);
    }
}
