/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.AdlMidiSynthProvider;
import com.fupfin.midiraja.midi.FFMAdlMidiNativeBridge;
import com.fupfin.midiraja.midi.FFMOpnMidiNativeBridge;
import com.fupfin.midiraja.midi.OpnMidiSynthProvider;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Plays MIDI files using FM synthesis (OPL or OPN chip emulation).
 *
 * <p>Engine selection priority:
 * <ol>
 *   <li>Explicit chip name as first positional argument (e.g. {@code midra fm opl file.mid})
 *   <li>Alias used to invoke the command (e.g. {@code midra adlib file.mid})
 *   <li>Default: OPN
 * </ol>
 */
@Command(name = "fm",
        aliases = {"opl", "opn", "adlib", "genesis", "pc98"},
        mixinStandardHelpOptions = true,
        description = "FM synthesis (OPL2/OPL3 and OPN2 chip emulation).",
        footer = {"",
                "Engine can be specified as the first argument:",
                "  midra fm opl <files...>     OPL2/OPL3 (AdLib / Sound Blaster)",
                "  midra fm adlib <files...>   same as above",
                "  midra fm genesis <files...> OPN2 (Sega Genesis)",
                "  midra fm pc98 <files...>    OPN2 (PC-98)", "",
                "OPL names: opl, opl2, opl3, adlib",
                "OPN names: opn, opn2, opna, genesis, pc98  (default when using 'fm')", "",
                "OPL Emulator IDs: 0=Nuked OPL3 v1.8, 1=Nuked v1.7.4, 5=ESFMu, 6=MAME OPL2, 7=YMFM OPL2, 8=YMFM OPL3",
                "OPN Emulator IDs: 0=MAME YM2612, 1=Nuked YM3438, 2=GENS, 3=YMFM OPN2, 4=NP2 OPNA, 5=MAME YM2608, 6=YMFM OPNA"})
public class FmCommand implements Callable<Integer>
{
    private static final Set<String> OPL_NAMES = Set.of("opl", "opl2", "opl3", "adlib");
    private static final Set<String> OPN_NAMES = Set.of("opn", "opn2", "opna", "genesis", "pc98");
    private static final Set<String> ALL_ENGINE_NAMES;

    static
    {
        ALL_ENGINE_NAMES = new java.util.HashSet<>();
        ALL_ENGINE_NAMES.addAll(OPL_NAMES);
        ALL_ENGINE_NAMES.addAll(OPN_NAMES);
    }

    @Spec
    @Nullable
    private CommandSpec spec;

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Parameters(arity = "1..*",
            description = "[engine] <files...>  Optional engine name (opl, adlib, opn, genesis, ...) followed by MIDI files.")
    private List<String> rawArgs = new java.util.ArrayList<>();

    @Option(names = {"-b", "--bank"}, arity = "0..1", fallbackValue = "",
            description = "OPL: embedded bank number (0-75) or .wopl path. OPN: .wopn file path.")
    private Optional<String> bank = Optional.empty();

    @Mixin
    private final FmSynthOptions fmOptions = new FmSynthOptions();

    @Mixin
    private final FxOptions fxOptions = new FxOptions();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    /** Returns the explicit engine name from the first positional arg, or null if not given. */
    private @Nullable String explicitEngine()
    {
        if (!rawArgs.isEmpty() && ALL_ENGINE_NAMES.contains(rawArgs.get(0)))
        {
            return rawArgs.get(0);
        }
        return null;
    }

    /** Returns the effective list of file arguments after stripping the engine name if present. */
    private List<File> effectiveFiles()
    {
        var args = explicitEngine() != null ? rawArgs.subList(1, rawArgs.size()) : rawArgs;
        return args.stream().map(File::new).toList();
    }

    private boolean isOpl()
    {
        // Priority 1: explicit engine name as first positional argument
        var engine = explicitEngine();
        if (engine != null)
        {
            return OPL_NAMES.contains(engine);
        }

        // Priority 2: alias used to invoke the command
        if (spec != null)
        {
            var cl = spec.commandLine();
            if (cl != null)
            {
                var parentCl = cl.getParent();
                if (parentCl != null)
                {
                    var pr = parentCl.getParseResult();
                    if (pr != null)
                    {
                        var subs = parentCl.getSubcommands();
                        for (String arg : pr.originalArgs())
                        {
                            if (subs.containsKey(arg))
                            {
                                return OPL_NAMES.contains(arg);
                            }
                        }
                    }
                }
            }
        }

        // Priority 3: default → OPN
        return false;
    }

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.verbose, common.debug);
        var p = requireNonNull(parent);
        var files = effectiveFiles();
        var pipeline = FmSynthOptions.buildStereoFmPipeline(common, fxOptions);
        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), p.isInTestMode());

        if (isOpl())
        {
            return callOpl(pipeline, runner, files);
        }
        return callOpn(pipeline, runner, files);
    }

    private Integer callOpl(com.fupfin.midiraja.dsp.AudioProcessor pipeline, PlaybackRunner runner,
            List<File> files) throws Exception
    {
        var bridge = new FFMAdlMidiNativeBridge();
        var provider = new AdlMidiSynthProvider(bridge, pipeline,
                fmOptions.emulator, fmOptions.chips, common.retroMode.orElse(null));
        if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);

        String soundbankArg =
                bank.map(v -> v.isEmpty() ? "bank:0" : (v.matches("\\d+") ? "bank:" + v : v))
                        .orElse("bank:0");

        return runner.run(provider, true, Optional.empty(), Optional.of(soundbankArg), files,
                common);
    }

    private Integer callOpn(com.fupfin.midiraja.dsp.AudioProcessor pipeline, PlaybackRunner runner,
            List<File> files) throws Exception
    {
        var bridge = new FFMOpnMidiNativeBridge();
        var provider = new OpnMidiSynthProvider(bridge, pipeline,
                fmOptions.emulator, fmOptions.chips, common.retroMode.orElse(null));
        if (fxOptions.masterGain != null) provider.setMasterGain(fxOptions.masterGain);

        String soundbankArg = bank.orElse("");

        return runner.run(provider, true, Optional.empty(), Optional.of(soundbankArg), files,
                common);
    }
}
