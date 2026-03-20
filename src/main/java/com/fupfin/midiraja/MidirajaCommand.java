/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja;

import com.fupfin.midiraja.cli.*;

import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.ui.Logo;
import com.fupfin.midiraja.dsp.AudioProcessor;
import java.io.File;
import com.fupfin.midiraja.midi.FFMTsfNativeBridge;
import com.fupfin.midiraja.midi.TsfSynthProvider;
import com.fupfin.midiraja.dsp.FloatToShortSink;
import com.fupfin.midiraja.midi.beep.BeepSynthProvider;
import com.fupfin.midiraja.midi.gus.GusSynthProvider;
import com.fupfin.midiraja.midi.psg.PsgSynthProvider;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.AdlMidiSynthProvider;
import com.fupfin.midiraja.midi.FFMAdlMidiNativeBridge;
import com.fupfin.midiraja.midi.FFMMuntNativeBridge;
import com.fupfin.midiraja.midi.FFMOpnMidiNativeBridge;
import com.fupfin.midiraja.midi.FluidSynthProvider;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.midi.MidiProviderFactory;
import com.fupfin.midiraja.midi.MuntSynthProvider;
import com.fupfin.midiraja.midi.NativeAudioEngine;
import com.fupfin.midiraja.midi.OpnMidiSynthProvider;
import java.io.PrintStream;
import java.io.PrintWriter;
import org.jline.terminal.TerminalBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "midra", mixinStandardHelpOptions = true,
        version = {"MIDIraja " + Version.VERSION + " (" + Version.COMMIT + ")"},
        description = "MIDIraja \u2014 " + Logo.TAGLINE,
        customSynopsis = {"midra [command] [OPTIONS] [<files>...]"},
        subcommands = {FmCommand.class, MuntCommand.class, FluidCommand.class,
                TsfCommand.class, GusCommand.class, BeepCommand.class,
                DeviceCommand.class, PsgCommand.class, DemoCommand.class,
                InfoCommand.class, ResumeCommand.class, CommandLine.HelpCommand.class},
        footer = {"",
                "Run 'midra <command> --help' for command-specific options.", "",
                "Playlist Features:",
                "  Supports .m3u and .txt files containing paths to .mid files.",
                "  You can embed CLI options inside M3U files using the " + "#MIDRA: prefix.",
                "  Example: #MIDRA: --shuffle --loop"})
public class MidirajaCommand implements Callable<Integer>
{
    public static volatile boolean SHUTTING_DOWN = false;
    public static volatile boolean ALT_SCREEN_ACTIVE = false;

    @Parameters(index = "0..*", description = "MIDI files, directories, or .m3u playlists to play.",
            arity = "0..*")
    private List<File> files = new ArrayList<>();

    @Option(names = {"-p", "--port"}, description = "MIDI output port index or partial name.")
    private Optional<String> port = Optional.empty();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    // ── Deprecated legacy options (hidden, for backwards compatibility) ───────

    @Option(names = {"--opl"}, hidden = true, arity = "0..1", fallbackValue = "")
    private Optional<String> legacyOpl = Optional.empty();

    @Option(names = {"--opl-emulator"}, hidden = true)
    private int legacyOplEmulator = 0;

    @Option(names = {"--opl-chips"}, hidden = true)
    private int legacyOplChips = 4;

    @Option(names = {"--opn"}, hidden = true, arity = "0..1", fallbackValue = "")
    private Optional<String> legacyOpn = Optional.empty();

    @Option(names = {"--opn-emulator"}, hidden = true)
    private int legacyOpnEmulator = 0;

    @Option(names = {"--opn-chips"}, hidden = true)
    private int legacyOpnChips = 4;

    @Option(names = {"--munt"}, hidden = true)
    private Optional<String> legacyMunt = Optional.empty();

    @Option(names = {"--fluid"}, hidden = true)
    private Optional<String> legacyFluid = Optional.empty();

    @Option(names = {"--fluid-driver"}, hidden = true)
    private Optional<String> legacyFluidDriver = Optional.empty();

    @Spec
    @Nullable
    private CommandSpec spec;

    // ── Test injection ────────────────────────────────────────────────────────

    @Nullable
    private MidiOutProvider provider;
    @Nullable
    private TerminalIO terminalIO;
    private boolean isTestMode = false;
    private PrintStream stdOut = System.out;
    private PrintStream stdErr = System.err;

    public void setTestEnvironment(MidiOutProvider provider, TerminalIO terminalIO, PrintStream out,
            PrintStream err)
    {
        this.provider = provider;
        this.terminalIO = terminalIO;
        this.stdOut = out;
        this.stdErr = err;
        this.isTestMode = true;
    }

    public PrintStream getOut()
    {
        return stdOut;
    }

    public PrintStream getErr()
    {
        return stdErr;
    }

    public @Nullable TerminalIO getTerminalIO()
    {
        return terminalIO;
    }

    public boolean isInTestMode()
    {
        return isTestMode;
    }

    public CommonOptions getCommonOptions()
    {
        return common;
    }

    // ── Port index lookup (package-private for tests) ─────────────────────────

    int findPortIndex(List<MidiPort> ports, String query)
    {
        return PlaybackRunner.findPortIndex(ports, query, stdErr);
    }

    private java.util.List<String> originalArgs()
    {
        if (spec == null) return java.util.List.of();
        var rawArgs = spec.commandLine().getParseResult().originalArgs();
        return rawArgs.stream().map(token -> {
            if (!token.startsWith("-")) {
                var f = new File(token);
                if (f.exists()) return f.getAbsolutePath();
            }
            return token;
        }).collect(java.util.stream.Collectors.toList());
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * On Windows, JLine extracts jlinenative.dll to the system temp directory.
     * When the temp path contains non-ASCII characters (e.g. Korean username), the extraction
     * fails and JLine spams WARNING messages. Fix: redirect {@code jline.tmpdir} to
     * {@code %ProgramData%\midiraja\tmp} which is always ASCII.
     */
    private static void fixJLineTmpDirOnWindows()
    {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (tmp.chars().allMatch(c -> c < 128)) return;
        String pd = System.getenv("ProgramData");
        if (pd == null) pd = "C:\\ProgramData";
        File safeDir = new File(pd, "midiraja\\tmp");
        if (safeDir.mkdirs() || safeDir.exists())
            System.setProperty("jline.tmpdir", safeDir.getAbsolutePath());
    }

    public static void main(String[] args)
    {
        fixJLineTmpDirOnWindows();
        var cmd = new CommandLine(new MidirajaCommand())
                .setParameterExceptionHandler(MidirajaCommand::handleParameterException)
                .setExecutionExceptionHandler(MidirajaCommand::handleExecutionException);
        // Show Commands before Options (matches "midra [command] [OPTIONS]" synopsis order)
        cmd.setHelpSectionKeys(List.of(
                "headerHeading", "header", "descriptionHeading", "description",
                "synopsisHeading", "synopsis",
                "parameterListHeading", "parameterList",
                "commandListHeading", "commandList",
                "optionListHeading", "optionList",
                "exitCodeListHeading", "exitCodeList",
                "footerHeading", "footer"));
        // Show only primary name; list aliases in brackets so they're visually secondary
        cmd.getHelpSectionMap().put("commandList", MidirajaCommand::renderCommandList);
        System.exit(cmd.execute(args));
    }

    private static String renderCommandList(CommandLine.Help help)
    {
        if (help.subcommands().isEmpty()) return "";
        var sb = new StringBuilder();
        // Deduplicate by CommandSpec identity so aliases don't produce duplicate entries
        var seen = new java.util.IdentityHashMap<Object, Boolean>();
        for (var entry : help.subcommands().entrySet())
        {
            var sub = entry.getValue();
            if (sub.commandSpec().usageMessage().hidden()) continue;
            if (seen.put(sub.commandSpec(), Boolean.TRUE) != null) continue;

            String name = sub.commandSpec().name();
            if (name == null || name.isEmpty()) name = entry.getKey();
            String[] aliases = sub.commandSpec().aliases();
            String[] descs = sub.commandSpec().usageMessage().description();
            // Strip picocli format sequences (e.g. %n) for single-line list display
            String desc = descs.length > 0 ? descs[0].replace("%n", " ").trim() : "";

            sb.append(String.format("  %-12s  %s", name, desc));
            if (aliases != null && aliases.length > 0)
                sb.append("  [").append(String.join(", ", aliases)).append("]");
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static int handleExecutionException(
            Exception ex, CommandLine cmd, CommandLine.ParseResult parseResult)
    {
        if (ex instanceof IllegalArgumentException)
        {
            cmd.getErr().println("Error: " + ex.getMessage());
            return 2;
        }
        throw new RuntimeException(ex);
    }

    private static int handleParameterException(CommandLine.ParameterException ex, String[] args)
    {
        CommandLine failedCmd = ex.getCommandLine();
        PrintWriter err = failedCmd.getErr();
        err.println("Error: " + ex.getMessage());

        if (ex instanceof CommandLine.MissingParameterException)
        {
            CommandLine parent = failedCmd.getParent();
            if (parent != null)
            {
                CommandLine.ParseResult parentResult = parent.getParseResult();
                if (parentResult != null && !parentResult.matchedPositionals().isEmpty())
                {
                    err.println("Hint: The command must come before the files.");
                    err.println(
                            "  Try: midra " + failedCmd.getCommandName() + " [OPTIONS] <files...>");
                }
            }
        }
        err.println("Run 'midra " + failedCmd.getCommandName() + " --help' for usage.");
        return 2;
    }

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        boolean hasLegacyOption = legacyMunt.isPresent() || legacyOpl.isPresent()
                || legacyOpn.isPresent() || legacyFluid.isPresent();
        if ((files == null || files.isEmpty()) && port.isEmpty() && !hasLegacyOption
                && provider == null)
        {
            try (var terminal = TerminalBuilder.builder().system(true).build())
            {
                Logo.print(terminal.writer());
                terminal.writer().println("Use 'midra <file1.mid>' or 'midra -h' for help.");
                terminal.writer().flush();
            }
            return 0;
        }

        // Fail-fast validation: Ensure all provided file paths actually exist
        if (files != null)
        {
            for (File file : files)
            {
                file = PlaylistParser.normalize(file);
                if (!file.exists())
                {
                    stdErr.println("Error: The file or directory '" + file.getPath()
                            + "' does not exist.");
                    stdErr.println(
                            "Hint: Did you misspell a command? (e.g., 'midra fluidsynth' instead of 'midra fluid')");
                    stdErr.println("Run .midra --help. for a list of available commands.");
                    return 1;
                }
            }
        }

        MidiOutProvider resolvedProvider;
        Optional<String> soundbankArg = Optional.empty();

        if (provider != null)
        {
            // Test mode: provider already injected
            resolvedProvider = provider;
        }
        else if (legacyMunt.isPresent())
        {
            stdErr.println("Warning: --munt is deprecated. Use 'midra munt <rom-dir> "
                    + "<files...>' instead.");
            String audioLib = AudioLibResolver.resolve();
            var audio = new NativeAudioEngine(audioLib);
            var bridge = new FFMMuntNativeBridge();
            audio.init(32000, 2, 4096);
            AudioProcessor pipeline = new FloatToShortSink(audio);
            resolvedProvider = new MuntSynthProvider(bridge, pipeline);
            soundbankArg = legacyMunt;
        }
        else if (legacyOpl.isPresent())
        {
            stdErr.println("Warning: --opl is deprecated. Use 'midra opl [-b BANK] "
                    + "<files...>' instead.");
            String audioLib = AudioLibResolver.resolve();
            var audio = new NativeAudioEngine(audioLib);
            audio.init(44100, 2, 4096);
            AudioProcessor pipeline = new FloatToShortSink(audio);
            var bridge = new FFMAdlMidiNativeBridge();
            resolvedProvider = new AdlMidiSynthProvider(bridge, pipeline,
                    legacyOplEmulator, legacyOplChips, null);
            String val = legacyOpl.get();
            soundbankArg = Optional
                    .of(val.isEmpty() ? "bank:0" : (val.matches("\\d+") ? "bank:" + val : val));
        }
        else if (legacyOpn.isPresent())
        {
            stdErr.println("Warning: --opn is deprecated. Use 'midra opn [-b PATH] "
                    + "<files...>' instead.");
            String audioLib = AudioLibResolver.resolve();
            var audio = new NativeAudioEngine(audioLib);
            var bridge = new FFMOpnMidiNativeBridge();
            audio.init(44100, 2, 4096);
            AudioProcessor pipeline = new FloatToShortSink(audio);
            resolvedProvider = new OpnMidiSynthProvider(bridge, pipeline,
                    legacyOpnEmulator, legacyOpnChips, null);
            soundbankArg = Optional.of(legacyOpn.get());
        }
        else if (legacyFluid.isPresent())
        {
            stdErr.println("Warning: --fluid is deprecated. Use 'midra fluid <soundfont.sf2> "
                    + "<files...>' instead.");
            resolvedProvider = new FluidSynthProvider(legacyFluidDriver.orElse(null));
            soundbankArg = legacyFluid;
        }
        else if (!files.isEmpty() && port.isEmpty())
        {
            var nativePorts = MidiProviderFactory.createProvider().getOutputPorts();
            var choice = EngineSelector.select(nativePorts, common.uiOptions, stdErr);
            if (choice == null) return 0;
            return switch (choice)
            {
                case EngineSelector.Choice.Builtin b -> runBuiltinEngine(b.engineName().strip(),
                        files, common);
                case EngineSelector.Choice.Port p -> {
                    var runner = new PlaybackRunner(stdOut, stdErr, terminalIO, isTestMode);
                    yield runner.run(MidiProviderFactory.createProvider(), false,
                            Optional.of(String.valueOf(p.portIndex())), Optional.empty(), files,
                            common, originalArgs());
                }
            };
        }
        else
        {
            resolvedProvider = MidiProviderFactory.createProvider();
        }

        boolean isSoftSynth = legacyMunt.isPresent() || legacyOpl.isPresent()
                || legacyOpn.isPresent() || legacyFluid.isPresent()
                || (provider != null && isTestMode);

        var runner = new PlaybackRunner(stdOut, stdErr, terminalIO, isTestMode);
        return runner.run(resolvedProvider, isSoftSynth, port, soundbankArg, files, common, originalArgs());
    }

    private int runBuiltinEngine(String engine, List<File> files, CommonOptions common)
            throws Exception
    {
        String audioLib = AudioLibResolver.resolve();
        var audio = new NativeAudioEngine(audioLib);
        Optional<String> soundbankArg = Optional.empty();
        MidiOutProvider builtinProvider;

        switch (engine)
        {
            case "patch" -> {
                audio.init(44100, 2, 4096);
                AudioProcessor pipeline = new FloatToShortSink(audio);
                builtinProvider = new GusSynthProvider(pipeline, null);
            }
            case "soundfont" -> {
                audio.init(44100, 2, 4096);
                String sfPath = TsfCommand.findBundledSf3();
                if (sfPath == null)
                {
                    stdErr.println("Error: Bundled FluidR3 GM SF3 not found. "
                            + "Run 'midra soundfont --help' for details.");
                    return 1;
                }
                AudioProcessor pipeline = new FloatToShortSink(audio);
                builtinProvider = new TsfSynthProvider(new FFMTsfNativeBridge(), pipeline);
                soundbankArg = Optional.of(sfPath);
            }
            case "opl" -> {
                audio.init(44100, 2, 4096);
                AudioProcessor pipeline = new FloatToShortSink(audio);
                builtinProvider = new AdlMidiSynthProvider(new FFMAdlMidiNativeBridge(), pipeline,
                        0, 4, null);
                soundbankArg = Optional.of("bank:0");
            }
            case "opn" -> {
                audio.init(44100, 2, 4096);
                AudioProcessor pipeline = new FloatToShortSink(audio);
                builtinProvider = new OpnMidiSynthProvider(new FFMOpnMidiNativeBridge(), pipeline,
                        0, 4, null);
                soundbankArg = Optional.of("");
            }
            case "1bit" -> {
                audio.init(44100, 1, 4096);
                AudioProcessor pipeline = new FloatToShortSink(audio, 1);
                builtinProvider = new BeepSynthProvider(pipeline, 2, 2.0, 2.0, 1, "dsd", "square");
            }
            case "psg" -> {
                audio.init(44100, 1, 4096);
                AudioProcessor pipeline = new FloatToShortSink(audio, 1);
                builtinProvider = new PsgSynthProvider(pipeline, 4, 5.0, 25.0, false, false);
            }
            default -> {
                stdErr.println("Error: Unknown built-in engine: " + engine);
                return 1;
            }
        }

        var runner = new PlaybackRunner(stdOut, stdErr, terminalIO, isTestMode);
        return runner.run(builtinProvider, true, Optional.empty(), soundbankArg, files, common, originalArgs());
    }
}
