/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Parameters;

import com.midiraja.engine.PlaybackEngine;
import com.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.midiraja.engine.PlaylistContext;
import com.midiraja.io.JLineTerminalIO;
import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.MidiProviderFactory;
import com.midiraja.ui.Theme;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import java.io.File;
import java.io.PrintStream;
import java.lang.ScopedValue;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "midra", mixinStandardHelpOptions = true, version = "midiraja " + Version.VERSION,
        description = "A fast, cross-platform CLI MIDI player.")
public class MidirajaCommand implements Callable<Integer>
{

    @Parameters(index = "0..*", description = "The MIDI file(s) to play.", arity = "0..*")
    private List<File> files = new ArrayList<>();

    @Option(names = {"-p", "--port"}, description = "MIDI output port index or partial name.")
    private Optional<String> port = Optional.empty();

    @Option(names = {"-v", "--volume"}, description = "Initial volume percentage (0-100).",
            defaultValue = "100")
    private Integer volume = 100;

    @Option(names = {"-x", "--speed"}, description = "Playback speed multiplier (e.g. 1.0, 1.2).",
            defaultValue = "1.0")
    private Double speed = 1.0;

    @Option(names = {"-s", "--start"},
            description = "Playback start time (e.g. 01:10:12, 05:30, or 90 for seconds).")
    private Optional<String> startTime = Optional.empty();

    @Option(names = {"-t", "--transpose"},
            description = "Transpose by semitones (e.g. 12 for one octave up, -5 for down).")
    private Optional<Integer> transpose = Optional.empty();

    @Option(names = {"-z", "--shuffle"}, description = "Shuffle the playlist before playing.")
    private boolean shuffle;

    @Option(names = {"-r", "--loop"}, description = "Loop the playlist indefinitely.")
    private boolean loop;

    @Option(names = {"-l", "--list-ports"}, description = "List all available MIDI output ports.")
    private boolean listPorts;

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    private UiModeOptions uiOptions = new UiModeOptions();

    static class UiModeOptions {
        @Option(names = {"-1", "--classic"}, description = "Basic CLI mode (static logging, pipe-friendly).")
        boolean classicMode;

        @Option(names = {"-2", "--mini"}, description = "Minimal TUI mode (single-line status).")
        boolean miniMode;

        @Option(names = {"-3", "--full"}, description = "Rich TUI mode (full-screen dashboard).")
        boolean fullMode;
    }

    // Optional overrides for testing
    @Nullable private MidiOutProvider provider;
    @Nullable private TerminalIO terminalIO;
    private boolean isTestMode = false;
    private PrintStream out = System.out;
    private PrintStream err = System.err;

    public void setTestEnvironment(MidiOutProvider provider, TerminalIO terminalIO, PrintStream out,
            PrintStream err)
    {
        this.provider = provider;
        this.terminalIO = terminalIO;
        this.out = out;
        this.err = err;
        this.isTestMode = true;
    }

    public static void main(String[] args)
    {
        int exitCode = new CommandLine(new MidirajaCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception
    {
        java.util.concurrent.atomic.AtomicBoolean portClosed = new java.util.concurrent.atomic.AtomicBoolean(false);
        if (provider == null)
        {
            provider = MidiProviderFactory.createProvider();
        }

        var ports = provider.getOutputPorts();

        if (listPorts)
        {
            out.println("Available MIDI Output Devices:");
            for (var p : ports)
            {
                out.println("[" + p.index() + "] " + p.name());
            }
            return 0;
        }

        List<File> playlist = new ArrayList<>();
        for (File f : files)
        {
            if (f.isDirectory())
            {
                var dirFiles = f.listFiles((_, name) -> name.toLowerCase(Locale.ROOT).endsWith(".mid")
                        || name.toLowerCase(Locale.ROOT).endsWith(".midi"));
                if (dirFiles != null)
                {
                    playlist.addAll(Arrays.asList(dirFiles));
                }
            }
            else
            {
                playlist.add(f);
            }
        }

        if (playlist.isEmpty())
        {
            err.println(
                    "Error: No MIDI files specified. Use 'midra <file1.mid> <file2.mid>' or 'midra -h' for help.");
            return 1;
        }

        if (shuffle)
        {
            Collections.shuffle(playlist);
        }

        int portIndex = -1;
        if (port.isPresent())
        {
            String portQuery = port.get();
            portIndex = findPortIndex(ports, portQuery);
            if (portIndex == -1)
            {
                err.println("Error: Could not find MIDI port matching: " + portQuery);
                return 1;
            }
        }
        else if (!isTestMode)
        {
            if (uiOptions.classicMode) {
                // In classic/batch mode, skip the fancy TUI menu and just use standard text prompt
                portIndex = fallbackPortSelection(ports);
            } else {
                portIndex = interactivePortSelection(ports, uiOptions.fullMode, uiOptions.miniMode);
            }
            if (portIndex == -1) return 0; // User quit
        }
        else
        {
            portIndex = 0;
        }

        try
        {
            provider.openPort(portIndex);

            // Add a shutdown hook to handle Ctrl+C (SIGINT) gracefully
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.print(Theme.TERM_SHOW_CURSOR + Theme.TERM_ALT_SCREEN_DISABLE); // Restore cursor, exit alt screen
                System.out.flush();
                try
                {
                    if (provider != null && portClosed.compareAndSet(false, true)) {
                        provider.panic();
                        try { Thread.sleep(200); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                        provider.closePort();
                    }
                }
                catch (Exception _) { // ignored
                }
            }));

            int currentTrackIdx = 0;
            Optional<String> currentStartTime = startTime;

            var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
            activeIO.init();
            boolean isInteractive = activeIO.isInteractive();
            
            com.midiraja.ui.PlaybackUI ui;
            boolean useAltScreen = false;

            if (uiOptions.classicMode) {
                ui = new com.midiraja.ui.DumbUI();
            } else if (uiOptions.miniMode) {
                ui = new com.midiraja.ui.LineUI();
            } else if (uiOptions.fullMode) {
                ui = new com.midiraja.ui.DashboardUI();
                useAltScreen = true;
            } else {
                // Auto mode logic fallback
                if (!isInteractive) {
                    ui = new com.midiraja.ui.DumbUI();
                } else if (activeIO.getHeight() < 10) {
                    ui = new com.midiraja.ui.LineUI();
                } else {
                    ui = new com.midiraja.ui.DashboardUI();
                    useAltScreen = true;
                }
            }

            if (useAltScreen && isInteractive) {
                out.print("\033[?1049h\033[?25l"); // Alt screen, hide cursor
                out.flush();
            }

            try {
                while (currentTrackIdx >= 0 && currentTrackIdx < playlist.size())
                {
                    var file = playlist.get(currentTrackIdx);
                    var sequence = MidiSystem.getSequence(file);
                    String title = extractSequenceTitle(sequence);
                    var context = new PlaylistContext(playlist, currentTrackIdx, ports.get(portIndex), title);
                    
                    var result = playMidiWithProvider(context, provider, currentStartTime, activeIO, ui);
                    var status = result.status();
                    currentStartTime = Optional.empty();
                    
                    // Preserve user-adjusted playback state for the next track
                    this.volume = (int) (result.engine().getVolumeScale() * 100);
                    this.speed = result.engine().getCurrentSpeed();
                    this.transpose = java.util.Optional.of(result.engine().getCurrentTranspose());

                    switch (status)
                    {
                        case QUIT_ALL -> { currentTrackIdx = -1; }
                        case PREVIOUS -> {
                            currentTrackIdx--;
                            if (loop && currentTrackIdx < 0) currentTrackIdx = playlist.size() - 1;
                            else currentTrackIdx = Math.max(0, currentTrackIdx);
                        }
                        case FINISHED, NEXT -> {
                            currentTrackIdx++;
                            if (loop && currentTrackIdx >= playlist.size()) {
                                currentTrackIdx = 0;
                                if (shuffle) Collections.shuffle(playlist);
                            }
                        }
                    }
                }
            } finally {
                if (useAltScreen && isInteractive) {
                    out.print(Theme.TERM_SHOW_CURSOR + Theme.TERM_ALT_SCREEN_DISABLE); // Show cursor, exit alt screen
                    out.flush();
                }
                activeIO.close();
            }
        }
        catch (Exception e)
        {
            err.println("Error during playback: " + e.getMessage());
            e.printStackTrace(err);
            return 1;
        }
        finally
        {
            if (provider != null && portClosed.compareAndSet(false, true)) {
                provider.panic();
                try { Thread.sleep(200); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                provider.closePort();
            }
        }

        return 0;
    }

    int findPortIndex(List<MidiPort> ports, String query)
    {
        try
        {
            int idx = Integer.parseInt(query);
            if (ports.stream().anyMatch(p -> p.index() == idx))
            {
                return idx;
            }
        }
        catch (NumberFormatException _) { // ignored
        }

        var lowerQuery = query.toLowerCase(Locale.ROOT);
        var matches =
                ports.stream().filter(p -> p.name().toLowerCase(Locale.ROOT).contains(lowerQuery)).toList();

        if (matches.size() == 1) return matches.get(0).index();
        if (matches.size() > 1)
        {
            err.println("Ambiguous port name. Matches:");
            matches.forEach(m -> err.println("  [" + m.index() + "] " + m.name()));
        }
        return -1;
    }

    private int interactivePortSelection(List<MidiPort> ports, boolean isExplicitFullMode, boolean isExplicitMiniMode) throws Exception
    {
        if (ports.isEmpty()) return -1;

        var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
        activeIO.init();
        boolean isInteractive = activeIO.isInteractive();
        int termHeight = activeIO.getHeight();
        activeIO.close(); // Probe terminal capabilities and release immediately

        if (isInteractive)
        {
            boolean willBeFullMode = isExplicitFullMode || (!isExplicitMiniMode && termHeight >= 10);
            
            if (willBeFullMode) {
                return fullScreenPortSelection(ports);
            } else {
                return dynamicPortSelection(ports);
            }
        }
        else
        {
            return fallbackPortSelection(ports);
        }
    }

    private int dynamicPortSelection(List<MidiPort> ports) throws Exception
    {
        int selectedIndex = 0;
        int numPorts = ports.size();

        try (org.jline.terminal.Terminal terminal =
                org.jline.terminal.TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            var reader = terminal.reader();

            terminal.writer().print(Theme.TERM_HIDE_CURSOR); // Hide cursor
            boolean firstDraw = true;

            while (true)
            {
                if (!firstDraw)
                {
                    terminal.writer().print("\033[" + (numPorts + 1) + "A"); // Move cursor above
                                                                             // menu
                }
                firstDraw = false;

                terminal.writer().println("Available MIDI Output Devices:");
                for (int i = 0; i < numPorts; i++)
                {
                    String prefix = (i == selectedIndex) ? " > " : "   ";
                    terminal.writer().println(prefix + ports.get(i).name());
                }
                terminal.writer().flush();

                int ch = reader.read(10);
                if (ch <= 0) continue; // Timeout

                if (ch == 'q' || ch == 'Q')
                {
                    clearMenu(terminal, numPorts);
                    terminal.writer().print(Theme.TERM_SHOW_CURSOR); // Restore cursor
                    terminal.writer().flush();
                    return -1;
                }
                if (ch == 13 || ch == 10)
                { // Enter
                    clearMenu(terminal, numPorts);
                    terminal.writer().print(Theme.TERM_SHOW_CURSOR); // Restore cursor
                    terminal.writer().flush();
                    return ports.get(selectedIndex).index();
                }

                if (ch == 27)
                { // ANSI Escape Sequence
                    int next1 = reader.read(2);
                    if (next1 == '[')
                    {
                        int next2 = reader.read(2);
                        if (next2 == 'A')
                        { // Up arrow
                            selectedIndex = (selectedIndex - 1 + numPorts) % numPorts;
                        }
                        else if (next2 == 'B')
                        { // Down arrow
                            selectedIndex = (selectedIndex + 1) % numPorts;
                        }
                    }
                }
            }
        }
    }

    
    private int fullScreenPortSelection(List<MidiPort> ports) throws Exception
    {
        int selectedIndex = 0;
        int numPorts = ports.size();

        try (org.jline.terminal.Terminal terminal =
                org.jline.terminal.TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            var reader = terminal.reader();

            // Enable Alt Screen and Hide Cursor
            terminal.writer().print(Theme.TERM_ALT_SCREEN_ENABLE + Theme.TERM_HIDE_CURSOR);
            terminal.writer().flush();

            while (true)
            {
                int width = terminal.getWidth();
                int height = terminal.getHeight();
                
                // Calculate box dimensions
                int boxWidth = 50;
                int boxHeight = numPorts + 4; // Title + ports + divider + footer
                
                int padLeft = Math.max(0, (width - boxWidth) / 2);
                int padTop = Math.max(0, (height - boxHeight) / 2);

                com.midiraja.ui.ScreenBuffer buffer = new com.midiraja.ui.ScreenBuffer(4096);
                buffer.append(Theme.TERM_CURSOR_HOME).append(Theme.TERM_CLEAR_TO_END);
                
                // Top padding
                buffer.repeat("\n", padTop);
                
                // Title
                String title = " SELECT MIDI TARGET ";
                int titlePad = (boxWidth - title.length() - 2) / 2; // -2 for the border lines
                buffer.repeat(" ", padLeft)
                      .append(Theme.COLOR_HIGHLIGHT)
                      .repeat(Theme.DECORATOR_LINE, titlePad)
                      .append(Theme.COLOR_RESET)
                      .append(Theme.FORMAT_INVERT).append(title).append(Theme.COLOR_RESET)
                      .append(Theme.COLOR_HIGHLIGHT)
                      .repeat(Theme.DECORATOR_LINE, boxWidth - titlePad - title.length())
                      .append(Theme.COLOR_RESET)
                      .appendLine();
                
                // Ports
                for (int i = 0; i < numPorts; i++)
                {
                    buffer.repeat(" ", padLeft);
                    String portName = ports.get(i).name();
                    if (portName.length() > boxWidth - 8) {
                        portName = portName.substring(0, boxWidth - 11) + "...";
                    }
                    
                    if (i == selectedIndex) {
                        buffer.append("  ").append(Theme.COLOR_HIGHLIGHT).append(Theme.CHAR_ARROW_RIGHT).append(" [").append(String.valueOf(i)).append("] ")
                              .append(portName).append(Theme.COLOR_RESET).appendLine();
                    } else {
                        buffer.append("    [").append(String.valueOf(i)).append("] ").append(portName).appendLine();
                    }
                }
                
                // Footer
                buffer.repeat(" ", padLeft)
                      .append(Theme.COLOR_HIGHLIGHT)
                      .repeat(Theme.BORDER_HORIZONTAL, boxWidth)
                      .append(Theme.COLOR_RESET)
                      .appendLine();
                
                String footer = "[▲/▼] Move   [Enter] Select   [Q] Quit";
                int footerPad = (boxWidth - footer.length()) / 2;
                buffer.repeat(" ", padLeft + footerPad)
                      .append(Theme.COLOR_HIGHLIGHT).append(footer).append(Theme.COLOR_RESET)
                      .appendLine();
                
                terminal.writer().print(buffer.toString());
                terminal.writer().flush();

                int ch = reader.read(50);
                if (ch <= 0) continue; // Timeout, redraw (handles resize)

                if (ch == 'q' || ch == 'Q')
                {
                    terminal.writer().print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return -1;
                }
                if (ch == 13 || ch == 10)
                { // Enter
                    terminal.writer().print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return ports.get(selectedIndex).index();
                }

                if (ch == 27)
                { // ANSI Escape Sequence
                    int next1 = reader.read(2);
                    if (next1 == '[')
                    {
                        int next2 = reader.read(2);
                        if (next2 == 'A')
                        { // Up arrow
                            selectedIndex = (selectedIndex - 1 + numPorts) % numPorts;
                        }
                        else if (next2 == 'B')
                        { // Down arrow
                            selectedIndex = (selectedIndex + 1) % numPorts;
                        }
                    }
                }
            }
        }
    }
private void clearMenu(org.jline.terminal.Terminal terminal, int numPorts)
    {
        terminal.writer().print("\033[" + (numPorts + 1) + "A");
        for (int i = 0; i <= numPorts; i++)
        {
            terminal.writer().println(Theme.TERM_CLEAR_TO_EOL); // Clear line
        }
        terminal.writer().print("\033[" + (numPorts + 1) + "A");
    }

    private int fallbackPortSelection(List<MidiPort> ports)
    {
        out.println("Available MIDI Output Devices:");
        for (var p : ports)
        {
            out.println("[" + p.index() + "] " + p.name());
        }

        out.print("Select a port index: ");
        out.flush();
        var scanner = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        if (scanner.hasNextInt())
        {
            int selected = scanner.nextInt();
            if (ports.stream().anyMatch(p -> p.index() == selected))
            {
                return selected;
            }
        }
        err.println("Invalid port selection.");
        return -1;
    }

    private PlaybackResult playMidiWithProvider(PlaylistContext context, MidiOutProvider provider,
            Optional<String> currentStartTime, TerminalIO activeIO, com.midiraja.ui.PlaybackUI ui) throws Exception
    {
        var file = context.files().get(context.currentIndex());
        var sequence = MidiSystem.getSequence(file);

        try
        {
            var engine = new PlaybackEngine(sequence, provider, context, volume, speed, currentStartTime,
                    transpose);
            return ScopedValue.where(TerminalIO.CONTEXT, activeIO)
                    .call(() -> new PlaybackResult(engine.start(ui), 0, engine));
        }
        finally
        {
            // Do not close activeIO here, managed by main loop
        }
    }

    private @Nullable String extractSequenceTitle(Sequence sequence)
    {
        for (Track track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                MidiMessage msg = track.get(i).getMessage();
                if (msg instanceof MetaMessage meta && meta.getType() == 0x03)
                {
                    byte[] data = meta.getData();
                    if (data != null && data.length > 0)
                    {
                        String text = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (!text.isEmpty() && !text.matches("^[\\s\\p{C}]+$")) return text;
                    }
                }
            }
        }
        return null;
    }

    private record PlaybackResult(PlaybackStatus status, int linesPrinted, com.midiraja.engine.PlaybackEngine engine)
    {
    }
}
