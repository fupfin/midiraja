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

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import javax.sound.midi.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;

import static java.lang.System.out;
import static java.lang.System.err;
import static java.lang.System.in;



@Command(name = "midra", mixinStandardHelpOptions = true, version = "midiraja " + Version.VERSION,
        description = "Midiraja: A high-performance MIDI player for CLI.",
        footer = {
            "",
            "Interactive Controls (during playback):",
            "  [↑/↓] Volume  [←/→] Seek  [+/-] Speed  [q] Quit"
        })
public class MidirajaCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The MIDI file to play.", arity = "0..1")
    private File file;

    @Option(names = {"-p", "--port"}, description = "MIDI output port index or partial name.")
    private String port;

    @Option(names = {"-v", "--volume"}, description = "Initial volume percentage (0-100).", defaultValue = "100")
    private Integer volume;

    @Option(names = {"-s", "--speed"}, description = "Playback speed multiplier (e.g. 1.0, 1.2).", defaultValue = "1.0")
    private Double speed;

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
            out.println("Available MIDI Output Devices:");
            if (ports.isEmpty()) {
                out.println("No MIDI output devices found.");
            } else {
                ports.forEach(out::println);
            }
            return 0;
        }

        if (file == null) {
            new CommandLine(this).usage(out);
            return 0;
        }

        if (!file.exists()) {
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
            
            // Add a shutdown hook to handle Ctrl+C (SIGINT) gracefully
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.print("\033[?25h"); // 커서 복구
                System.out.flush();
                try {
                    provider.panic();
                    provider.closePort();
                } catch (Exception _) {}
            }));

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

    private int interactivePortSelection(List<MidiPort> ports) throws Exception {
        if (ports.isEmpty()) return -1;
        
        var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
        activeIO.init();
        boolean isInteractive = activeIO.isInteractive();
        activeIO.close(); // JLine Terminal 객체 상태만 확인하고 다시 닫음.

        if (isInteractive) {
            return dynamicPortSelection(ports);
        } else {
            return fallbackPortSelection(ports);
        }
    }

    private int dynamicPortSelection(List<MidiPort> ports) throws Exception {
        int selectedIndex = 0;
        int numPorts = ports.size();

        try (org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder.builder().system(true).build()) {
            terminal.enterRawMode();
            var reader = terminal.reader();
            
            terminal.writer().print("\033[?25l"); // 커서 숨김
            boolean firstDraw = true;

            while (true) {
                if (!firstDraw) {
                    terminal.writer().print("\033[" + (numPorts + 1) + "A"); // 메뉴 위로 커서 이동
                }
                firstDraw = false;

                terminal.writer().println("Available MIDI Output Devices:");
                for (int i = 0; i < numPorts; i++) {
                    String prefix = (i == selectedIndex) ? " > " : "   ";
                    terminal.writer().println(prefix + ports.get(i).name());
                }
                terminal.writer().flush();

                int ch = reader.read(10);
                if (ch <= 0) continue; // Timeout

                if (ch == 'q' || ch == 'Q') {
                    clearMenu(terminal, numPorts);
                    return -1;
                } else if (ch == 27) { // ESC 또는 방향키
                    int next1 = reader.read(10);
                    if (next1 == '[') {
                        int next2 = reader.read(10);
                        if (next2 == 'A') { // UP
                            selectedIndex = Math.max(0, selectedIndex - 1);
                        } else if (next2 == 'B') { // DOWN
                            selectedIndex = Math.min(numPorts - 1, selectedIndex + 1);
                        }
                    } else if (next1 <= 0) { // 단순 ESC
                        clearMenu(terminal, numPorts);
                        return -1;
                    }
                } else if (ch == 10 || ch == 13) { // ENTER
                    clearMenu(terminal, numPorts);
                    return ports.get(selectedIndex).index();
                }
            }
        }
    }

    private void clearMenu(org.jline.terminal.Terminal terminal, int numPorts) {
        terminal.writer().print("\033[" + (numPorts + 1) + "A"); // 메뉴 시작점
        terminal.writer().print("\033[J"); // 커서 아래로 화면 모두 지우기
        terminal.writer().print("\033[?25h"); // 커서 다시 표시
        terminal.writer().flush();
    }

    private int fallbackPortSelection(List<MidiPort> ports) {
        out.println("Available MIDI Output Devices:");
        ports.forEach(out::println);

        var scanner = new Scanner(in);
        while (true) {
            out.print("Select a port by number or name (or type 'q' to quit): ");
            if (!scanner.hasNextLine()) return -1;
            var input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("q")) return -1;
            if (input.isEmpty()) continue;

            int idx = findPortIndex(ports, input);
            if (idx != -1) return idx;
        }
    }

    private void playMidiWithProvider(File file, MidiOutProvider provider, MidiPort targetPort) throws Exception {
        var sequence = MidiSystem.getSequence(file);
        
        out.println("Started playing: " + file.getName() + " to " + targetPort.name());
        extractAndPrintMetadata(sequence);
        out.println("Controls: [↑/↓] Volume  [←/→] Seek  [+/-] Speed  [q] Quit");

        var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
        activeIO.init();
        
        try {
            var engine = new PlaybackEngine(sequence, provider, activeIO, volume, speed);
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

        if (title != null) out.println("  Title: " + title);
        if (copyright != null) out.println("  Copyright: " + copyright);
        texts.forEach(info -> out.println("  Info: " + info));
    }
}
