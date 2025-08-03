package com.midiraja;

import com.midiraja.engine.PlaybackEngine;
import com.midiraja.io.JLineTerminalIO;
import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.MidiProviderFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import javax.sound.midi.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;

import static java.lang.System.err;
import static java.lang.IO.*;

@Command(name = "midra", mixinStandardHelpOptions = true, version = "midra 1.1",
        description = "Midiraja: A high-performance MIDI player for CLI.")
public class MidirajaCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The MIDI file to play.", arity = "0..1")
    private File file;

    @Option(names = {"-p", "--port"}, description = "MIDI output port index or partial name.")
    private String port;

    @Option(names = {"-v", "--volume"}, description = "Initial volume percentage (0-100).", defaultValue = "100")
    private Integer volume;

    @Option(names = {"-t", "--transpose"}, description = "Transpose semitones (+/-).")
    private Integer transpose;

    @Option(names = {"-l", "--list"}, description = "List available MIDI output devices.")
    private boolean listDevices;

    private boolean isTestMode = false;
    private MidiOutProvider provider;
    private TerminalIO terminalIO;

    public void setTestMode(boolean testMode) {
        this.isTestMode = testMode;
    }

    public void setProvider(MidiOutProvider provider) {
        this.provider = provider;
    }

    public void setTerminalIO(TerminalIO terminalIO) {
        this.terminalIO = terminalIO;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MidirajaCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (provider == null) {
            provider = MidiProviderFactory.createProvider();
        }
        var ports = provider.getOutputPorts();

        if (listDevices) {
            println("Available MIDI Output Devices:");
            if (ports.isEmpty()) {
                println("No MIDI output devices found.");
            } else {
                ports.forEach(java.lang.IO::println);
            }
            return 0;
        }

        if (file == null || !file.exists()) {
            err.println("Error: Missing or invalid MIDI file.");
            new CommandLine(this).usage(err);
            return 1;
        }

        int portIndex = -1;
        if (port != null) {
            portIndex = findPortIndex(ports, port);
            if (portIndex == -1) {
                err.println("Error: Could not find MIDI port matching: " + port);
                return 1;
            }
        } else if (!isTestMode) {
            portIndex = interactivePortSelection(ports);
            if (portIndex == -1) return 0; // User quit
        } else {
            portIndex = 0;
        }

        try {
            provider.openPort(portIndex);
            playMidiWithProvider(file, provider, ports.get(portIndex));
        } catch (Exception e) {
            err.println("Error during playback: " + e.getMessage());
            return 1;
        } finally {
            provider.closePort();
        }

        return 0;
    }

    int findPortIndex(List<MidiPort> ports, String query) {
        try {
            int idx = Integer.parseInt(query);
            if (ports.stream().anyMatch(p -> p.index() == idx)) {
                return idx;
            }
        } catch (NumberFormatException _) {}

        var lowerQuery = query.toLowerCase();
        var matches = ports.stream()
                .filter(p -> p.name().toLowerCase().contains(lowerQuery))
                .toList();

        if (matches.size() == 1) return matches.get(0).index();
        if (matches.size() > 1) {
            err.println("Ambiguous port name. Matches:");
            matches.forEach(m -> err.println("  [" + m.index() + "] " + m.name()));
        }
        return -1;
    }

    private int interactivePortSelection(List<MidiPort> ports) {
        println("Available MIDI Output Devices:");
        ports.forEach(java.lang.IO::println);

        while (true) {
            String input = readln("Select a port by number or name (or type 'q' to quit): ");
            if (input == null || input.trim().equalsIgnoreCase("q")) return -1;
            input = input.trim();
            if (input.isEmpty()) continue;

            int idx = findPortIndex(ports, input);
            if (idx != -1) return idx;
        }
    }

    private void playMidiWithProvider(File file, MidiOutProvider provider, MidiPort targetPort) throws Exception {
        var sequence = MidiSystem.getSequence(file);
        
        println("Started playing: " + file.getName() + " to " + targetPort.name());
        extractAndPrintMetadata(sequence);

        var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
        activeIO.init();
        
        try {
            var engine = new PlaybackEngine(sequence, provider, activeIO, volume);
            engine.start();
        } finally {
            activeIO.close();
        }
    }

    private void extractAndPrintMetadata(Sequence sequence) {
        String title = null;
        String copyright = null;
        List<String> texts = new ArrayList<>();

        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage msg = track.get(i).getMessage();
                if (msg instanceof MetaMessage meta) {
                    byte[] data = meta.getData();
                    if (data != null && data.length > 0) {
                        String text = new String(data).trim();
                        if (text.isEmpty() || text.matches("^[\\s\\p{C}]+$")) continue;

                        switch (meta.getType()) {
                            case 0x03 -> { if (title == null) title = text; }
                            case 0x02 -> { if (copyright == null) copyright = text; }
                            case 0x01 -> { if (texts.size() < 3 && !texts.contains(text)) texts.add(text); }
                        }
                    }
                }
            }
        }

        if (title != null) println("  Title: " + title);
        if (copyright != null) println("  Copyright: " + copyright);
        texts.forEach(info -> println("  Info: " + info));
    }
}