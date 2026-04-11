/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import javax.sound.midi.Sequence;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.dsp.AudioProcessor;
import com.fupfin.midiraja.dsp.FloatToShortSink;
import com.fupfin.midiraja.dsp.SpectrumAnalyzerFilter;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.export.vgm.Ay8910VgmExporter;
import com.fupfin.midiraja.export.vgm.MsxVgmExporter;
import com.fupfin.midiraja.export.vgm.Opl3VgmExporter;
import com.fupfin.midiraja.export.vgm.Ym2413VgmExporter;
import com.fupfin.midiraja.format.MusicFormatLoader;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.vgm.FFMLibvgmBridge;
import com.fupfin.midiraja.midi.vgm.LibvgmPlaybackEngine;
import com.fupfin.midiraja.midi.vgm.LibvgmSynthProvider;

@Command(name = "vgm", aliases = {
        "chiptune" }, mixinStandardHelpOptions = true, description = "VGM/VGZ direct playback or MIDI → VGM → libvgm synthesis.")
public class VgmCommand implements Callable<Integer>
{
    private static final Set<String> VALID_SYSTEMS = Set.of("ay8910", "ym2413", "msx", "opl3");

    @Spec
    @Nullable
    private CommandSpec spec;

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(index = "0..*", arity = "1..*", description = "VGM, VGZ, MIDI, MOD, or other music files to play.")
    private List<File> files = new ArrayList<>();

    @Option(names = {
            "--system" }, defaultValue = "ay8910", description = "Target chip system for non-VGM input: ay8910, ym2413, msx, opl3. Default: ay8910.")
    private String system = "ay8910";

    @Mixin
    private FxOptions fxOptions = new FxOptions();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    // ── Test-accessible setters ───────────────────────────────────────────────

    public String getSystem()
    {
        return system;
    }

    public void setSystem(String system)
    {
        this.system = system;
    }

    public void setFiles(List<File> files)
    {
        this.files = files;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    public void validateSystem()
    {
        if (system == null || system.isEmpty()
                || !VALID_SYSTEMS.contains(system.toLowerCase(Locale.ROOT)))
        {
            throw new IllegalArgumentException(
                    "Unknown --system value: '" + system
                            + "'. Valid values: ay8910, ym2413, msx, opl3");
        }
    }

    public void validateFiles()
    {
        if (files == null || files.isEmpty())
            throw new IllegalArgumentException("At least one input file is required.");
    }

    // ── File type detection ───────────────────────────────────────────────────

    public static boolean isVgmFile(File file)
    {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".vgm") || name.endsWith(".vgz");
    }

    // ── Exporter mapping (for testing / inspection) ───────────────────────────

    public String getExporterClassName()
    {
        return switch (system.toLowerCase(Locale.ROOT))
        {
            case "ay8910" -> "Ay8910VgmExporter";
            case "ym2413" -> "Ym2413VgmExporter";
            case "msx" -> "MsxVgmExporter";
            case "opl3" -> "Opl3VgmExporter";
            default -> throw new IllegalArgumentException("Unknown system: " + system);
        };
    }

    // ── CLI entry point ───────────────────────────────────────────────────────

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);

        String audioLib = AudioLibResolver.resolve();
        var audio = new NativeAudioEngine(audioLib);
        audio.init(44100, 2, 4096);
        if (common.dumpWav.isPresent())
            audio.enableDump(common.dumpWav.get());

        AudioProcessor pipeline = new FloatToShortSink(audio, 2);
        var spectrumFilter = new SpectrumAnalyzerFilter(pipeline);
        pipeline = common.buildDspChain(spectrumFilter);
        pipeline = fxOptions.wrapWithFloatConversion(pipeline, common);

        var bridge = new FFMLibvgmBridge();
        var provider = new LibvgmSynthProvider(bridge, pipeline);
        if (fxOptions.masterGain != null)
            provider.setMasterGain(fxOptions.masterGain);

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), false,
                (seq, prov, ctx, pip, shutdown, speed, startTime) ->
                        buildEngine(seq, provider, ctx, spectrumFilter));
        runner.setFxOptions(fxOptions);
        runner.setSequenceLoader(file -> loadSequenceAndPrimeProvider(file, provider));

        return runner.run(provider, true, Optional.empty(), Optional.empty(), files, common,
                originalArgs());
    }

    private LibvgmPlaybackEngine buildEngine(Sequence seq, LibvgmSynthProvider provider,
            PlaylistContext ctx, SpectrumAnalyzerFilter spectrumFilter)
    {
        var engine = new LibvgmPlaybackEngine(seq, provider, ctx);
        engine.setSpectrumFilter(spectrumFilter);
        return engine;
    }

    private Sequence loadSequenceAndPrimeProvider(File file, LibvgmSynthProvider provider)
            throws Exception
    {
        if (isVgmFile(file))
        {
            provider.loadVgmFile(file.getAbsolutePath());
            return new Sequence(Sequence.PPQ, 480); // unknown duration
        }

        validateSystem();
        var sequence = MusicFormatLoader.load(file, Set.of());
        var vgmBytes = new ByteArrayOutputStream();
        exportSequenceToVgm(sequence, vgmBytes);
        provider.loadVgmData(vgmBytes.toByteArray());
        return sequence;
    }

    private void exportSequenceToVgm(Sequence sequence, java.io.OutputStream out)
    {
        switch (system.toLowerCase(Locale.ROOT))
        {
            case "ay8910" -> new Ay8910VgmExporter().export(sequence, out);
            case "ym2413" -> new Ym2413VgmExporter().export(sequence, out);
            case "msx" -> new MsxVgmExporter().export(sequence, out);
            case "opl3" -> new Opl3VgmExporter().export(sequence, out);
            default -> throw new IllegalArgumentException("Unknown system: " + system);
        }
    }

    private List<String> originalArgs()
    {
        var rawArgs = requireNonNull(spec).commandLine().getParseResult().originalArgs();
        return rawArgs.stream().map(this::resolveToken).collect(Collectors.toList());
    }

    private String resolveToken(String token)
    {
        if (!token.startsWith("-"))
        {
            var f = new File(token);
            if (f.exists())
                return f.getAbsolutePath();
        }
        return token;
    }
}
