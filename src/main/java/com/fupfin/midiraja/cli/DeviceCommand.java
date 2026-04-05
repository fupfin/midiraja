package com.fupfin.midiraja.cli;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.AppLogger;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiProviderFactory;

@Command(name = "device", aliases = {
        "dev" }, mixinStandardHelpOptions = true, description = "OS native MIDI ports (CoreMIDI / ALSA / Windows GS).", footer = {
                "", "With no arguments, lists available MIDI output ports.",
                "  midra device                    # list ports",
                "  midra device song.mid           # pick port interactively, then play",
                "  midra device 1 song.mid         # play on port 1" })
public class DeviceCommand implements Callable<Integer>
{
    @Spec
    @Nullable
    private CommandSpec spec;

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Option(names = { "-l", "--list" }, description = "List available MIDI output ports and exit.")
    private boolean list;

    @Parameters(index = "0..*", arity = "0..*", description = "[Optional Device ID/Name] followed by MIDI files or directories.\n"
            + "If the first argument is not a valid file/directory, it is treated as the device query.")
    private List<String> args = new ArrayList<>();

    @Mixin
    private final CommonOptions common = new CommonOptions();

    @Override
    public Integer call() throws Exception
    {
        AppLogger.configure(common.logLevel.orElse(null));
        var p = requireNonNull(parent);
        MidiOutProvider provider = MidiProviderFactory.createProvider();

        if (list || args.isEmpty())
        {
            var ports = provider.getOutputPorts();
            p.getOut().println("Available MIDI Output Devices:");
            for (var port : ports)
            {
                p.getOut().println("[" + port.index() + "] " + port.name());
            }
            return 0;
        }

        // Separate the optional device query from the files list
        Optional<String> portQuery = Optional.empty();
        List<File> files = new ArrayList<>();

        File firstArg = new File(args.getFirst());
        // If the first argument does NOT exist on the filesystem, assume it's a device ID or name
        if (!firstArg.exists())
        {
            portQuery = Optional.of(args.getFirst());
            for (int i = 1; i < args.size(); i++)
            {
                files.add(new File(args.get(i)));
            }
        }
        else
        {
            for (String arg : args)
            {
                files.add(new File(arg));
            }
        }

        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), false);
        return runner.run(provider, false, portQuery, Optional.empty(), files, common, originalArgs());
    }

    private List<String> originalArgs()
    {
        var rawArgs = java.util.Objects.requireNonNull(spec).commandLine().getParseResult().originalArgs();
        return rawArgs.stream().map(this::resolveToken).collect(java.util.stream.Collectors.toList());
    }

    private String resolveToken(String token)
    {
        if (!token.startsWith("-"))
        {
            var f = new java.io.File(token);
            if (f.exists())
                return f.getAbsolutePath();
        }
        return token;
    }
}
