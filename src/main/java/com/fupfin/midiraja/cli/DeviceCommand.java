package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiProviderFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@Command(name = "device", aliases = {"dev"}, mixinStandardHelpOptions = true,
         description = "Play using the OS's native hardware/software MIDI ports")
public class DeviceCommand implements java.util.concurrent.Callable<Integer>
{
    @ParentCommand @Nullable private MidirajaCommand parent;

    @Parameters(index = "0..*", arity = "1..*",
        description = "[Optional Device ID/Name] followed by MIDI files or directories.\n" +
                      "If the first argument is not a valid file/directory, it is treated as the device query.")
    private List<String> args = new ArrayList<>();

    @Mixin private final CommonOptions common = new CommonOptions();

    @Override public Integer call() throws Exception
    {
        var p = java.util.Objects.requireNonNull(parent);
        MidiOutProvider provider = MidiProviderFactory.createProvider();
        
        // We must separate the potential device query from the files list
        Optional<String> portQuery = Optional.empty();
        List<File> files = new ArrayList<>();
        
        if (!args.isEmpty()) {
            File firstArg = new File(args.get(0));
            // If the first argument does NOT exist on the filesystem, we assume it's a device ID or Name (e.g., "1" or "CoreMIDI")
            if (!firstArg.exists()) {
                portQuery = Optional.of(args.get(0));
                for (int i = 1; i < args.size(); i++) {
                    files.add(new File(args.get(i)));
                }
            } else {
                for (String arg : args) {
                    files.add(new File(arg));
                }
            }
        }
        
        var runner = new PlaybackRunner(p.getOut(), p.getErr(), p.getTerminalIO(), false);
        return runner.run(provider, false, portQuery, Optional.empty(), files, common);
    }
}