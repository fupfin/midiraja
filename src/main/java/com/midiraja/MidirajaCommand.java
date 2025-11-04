/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja;

import com.midiraja.cli.AudioLibResolver;
import com.midiraja.cli.CommonOptions;
import com.midiraja.cli.FluidCommand;
import com.midiraja.cli.JavaSynthCommand;
import com.midiraja.cli.ListPortsCommand;
import com.midiraja.cli.MuntCommand;
import com.midiraja.cli.OplCommand;
import com.midiraja.cli.OpnCommand;
import com.midiraja.cli.PlaybackRunner;
import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.MidiProviderFactory;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "midra", mixinStandardHelpOptions = true, version = "midiraja " + Version.VERSION,
    description = "A fast, cross-platform CLI MIDI player.",
    customSynopsis = {"midra [command] [OPTIONS] [<files>...]"},
    subcommands =
        {
            OplCommand.class,
            OpnCommand.class,
            MuntCommand.class,
            FluidCommand.class,
            JavaSynthCommand.class,
            ListPortsCommand.class,
            CommandLine.HelpCommand.class,
            picocli.AutoComplete.GenerateCompletion.class,
        },
    footer = {"", "Synths (subcommands):", "  opl    OPL2/OPL3 FM  (AdLib / Sound Blaster)",
        "  opn    OPN2 FM       (Sega Genesis / PC-98)",
        "  munt   MT-32         (Roland MT-32/CM-32L)", "  fluid  FluidSynth    (SoundFont .sf2)",
        "  java   Java built-in synthesizer (experimental)", "",
        "Run 'midra <command> --help' for synth-specific options.", "",
        "Playlist Features:", "  Supports .m3u and .txt files containing paths to .mid files.",
        "  You can embed CLI options inside M3U files using the #MIDRA: prefix.",
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

    @Mixin private CommonOptions common = new CommonOptions();

    // ── Deprecated legacy options (hidden, for backwards compatibility) ───────

    @Option(names = {"-l", "--list-ports"}, hidden = true,
        description = "Deprecated: use 'midra ports' instead.")
    private boolean legacyListPorts;

    @Option(names = {"--opl"}, hidden = true, arity = "0..1", fallbackValue = "")
    private Optional<String> legacyOpl = Optional.empty();

    @Option(names = {"--opl-emulator"}, hidden = true) private int legacyOplEmulator = 0;

    @Option(names = {"--opl-chips"}, hidden = true) private int legacyOplChips = 4;

    @Option(names = {"--opn"}, hidden = true, arity = "0..1", fallbackValue = "")
    private Optional<String> legacyOpn = Optional.empty();

    @Option(names = {"--opn-emulator"}, hidden = true) private int legacyOpnEmulator = 0;

    @Option(names = {"--opn-chips"}, hidden = true) private int legacyOpnChips = 4;

    @Option(names = {"--munt"}, hidden = true)
    private Optional<String> legacyMunt = Optional.empty();

    @Option(names = {"--fluid"}, hidden = true)
    private Optional<String> legacyFluid = Optional.empty();

    @Option(names = {"--fluid-driver"}, hidden = true)
    private Optional<String> legacyFluidDriver = Optional.empty();

    @Option(names = {"--synth"}, hidden = true) private boolean legacySynth;

    // ── Test injection ────────────────────────────────────────────────────────

    @Nullable private MidiOutProvider provider;
    @Nullable private TerminalIO terminalIO;
    private boolean isTestMode = false;
    private PrintStream out = System.out;
    private PrintStream err = System.err;

    public void setTestEnvironment(
        MidiOutProvider provider, TerminalIO terminalIO, PrintStream out, PrintStream err)
    {
        this.provider = provider;
        this.terminalIO = terminalIO;
        this.out = out;
        this.err = err;
        this.isTestMode = true;
    }

    public PrintStream getOut()
    {
        return out;
    }
    public PrintStream getErr()
    {
        return err;
    }
    public @Nullable TerminalIO getTerminalIO()
    {
        return terminalIO;
    }
    public boolean isInTestMode()
    {
        return isTestMode;
    }

    // ── Port index lookup (package-private for tests) ─────────────────────────

    int findPortIndex(List<MidiPort> ports, String query)
    {
        return PlaybackRunner.findPortIndex(ports, query, err);
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args)
    {
        int exitCode = new CommandLine(new MidirajaCommand())
                           .setParameterExceptionHandler(MidirajaCommand::handleParameterException)
                           .execute(args);
        System.exit(exitCode);
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

    @Override public Integer call() throws Exception
    {
        // Warn and handle deprecated legacy options
        if (legacyListPorts)
        {
            err.println("Warning: --list-ports / -l is deprecated. Use 'midra ports' instead.");
            var nativeProvider = MidiProviderFactory.createProvider();
            out.println("Available MIDI Output Devices:");
            for (var p : nativeProvider.getOutputPorts())
            {
                out.println("[" + p.index() + "] " + p.name());
            }
            return 0;
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
            err.println(
                "Warning: --munt is deprecated. Use 'midra munt <rom-dir> <files...>' instead.");
            String audioLib = AudioLibResolver.resolve();
            var audio = new com.midiraja.midi.NativeAudioEngine(audioLib);
            var bridge = new com.midiraja.midi.FFMMuntNativeBridge();
            resolvedProvider = new com.midiraja.midi.MuntSynthProvider(bridge, audio);
            soundbankArg = legacyMunt;
        }
        else if (legacyOpl.isPresent())
        {
            err.println(
                "Warning: --opl is deprecated. Use 'midra opl [-b BANK] <files...>' instead.");
            String audioLib = AudioLibResolver.resolve();
            var audio = new com.midiraja.midi.NativeAudioEngine(audioLib);
            var bridge = new com.midiraja.midi.FFMAdlMidiNativeBridge();
            resolvedProvider = new com.midiraja.midi.AdlMidiSynthProvider(
                bridge, audio, legacyOplEmulator, legacyOplChips);
            String val = legacyOpl.get();
            soundbankArg =
                Optional.of(val.isEmpty() ? "bank:0" : (val.matches("\\d+") ? "bank:" + val : val));
        }
        else if (legacyOpn.isPresent())
        {
            err.println(
                "Warning: --opn is deprecated. Use 'midra opn [-b PATH] <files...>' instead.");
            String audioLib = AudioLibResolver.resolve();
            var audio = new com.midiraja.midi.NativeAudioEngine(audioLib);
            var bridge = new com.midiraja.midi.FFMOpnMidiNativeBridge();
            resolvedProvider = new com.midiraja.midi.OpnMidiSynthProvider(
                bridge, audio, legacyOpnEmulator, legacyOpnChips);
            soundbankArg = Optional.of(legacyOpn.get());
        }
        else if (legacyFluid.isPresent())
        {
            err.println("Warning: --fluid is deprecated. Use 'midra fluid <soundfont.sf2> "
                        + "<files...>' instead.");
            resolvedProvider =
                new com.midiraja.midi.FluidSynthProvider(legacyFluidDriver.orElse(null));
            soundbankArg = legacyFluid;
        }
        else if (legacySynth)
        {
            err.println("Warning: --synth is deprecated. Use 'midra java <files...>' instead.");
            resolvedProvider = new com.midiraja.midi.JavaSynthProvider();
        }
        else
        {
            resolvedProvider = MidiProviderFactory.createProvider();
        }

        boolean isSoftSynth = legacyMunt.isPresent() || legacyOpl.isPresent()
            || legacyOpn.isPresent() || legacyFluid.isPresent() || legacySynth
            || (provider != null && isTestMode);

        var runner = new PlaybackRunner(out, err, terminalIO, isTestMode);
        return runner.run(resolvedProvider, isSoftSynth, port, soundbankArg, files, common);
    }
}
