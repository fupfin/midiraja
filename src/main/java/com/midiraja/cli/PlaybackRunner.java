/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.cli;

import com.midiraja.MidirajaCommand;
import com.midiraja.engine.PlaybackEngine;
import com.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.midiraja.engine.PlaylistContext;
import com.midiraja.io.JLineTerminalIO;
import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.SoftSynthProvider;
import com.midiraja.ui.Theme;
import java.io.File;
import java.io.PrintStream;
import java.lang.ScopedValue;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import org.jspecify.annotations.Nullable;

/**
 * Orchestrates MIDI playback: builds the playlist, selects a port, opens the provider,
 * sets up the terminal and shutdown hook, then drives the playlist loop.
 * <p>
 * Shared by {@link MidirajaCommand} (native OS MIDI) and all soft-synth subcommands.
 */
@SuppressWarnings("EmptyCatch") public class PlaybackRunner
{
    private final PrintStream out;
    private final PrintStream err;
    @Nullable private final TerminalIO terminalIO;
    private final boolean isTestMode;

    public PlaybackRunner(
        PrintStream out, PrintStream err, @Nullable TerminalIO terminalIO, boolean isTestMode)
    {
        this.out = out;
        this.err = err;
        this.terminalIO = terminalIO;
        this.isTestMode = isTestMode;
    }

    /**
     * Run the full playback lifecycle.
     *
     * @param provider      pre-constructed MIDI provider
     * @param isSoftSynth   if {@code true}, port selection is skipped (port 0 is always used)
     * @param portQuery     for native MIDI: optional explicit port index or name
     * @param soundbankArg  for soft synths: argument to pass to {@link
     *     SoftSynthProvider#loadSoundbank}
     * @param rawFiles      raw file/dir/playlist arguments from the command line
     * @param common        shared playback options (may be mutated by M3U directives)
     * @return picocli exit code (0 = success, 1 = error)
     */
    public int run(MidiOutProvider provider, boolean isSoftSynth, Optional<String> portQuery,
        Optional<String> soundbankArg, List<File> rawFiles, CommonOptions common) throws Exception
    {
        AtomicBoolean portClosed = new AtomicBoolean(false);
        var ports = provider.getOutputPorts();

        // ── Playlist ──────────────────────────────────────────────────────────
        PlaylistParser parser = new PlaylistParser(err, common.verbose);
        List<File> playlist = parser.parse(rawFiles, common);

        if (playlist.isEmpty())
        {
            err.println(
                "Error: No MIDI files specified. Use 'midra <file1.mid>' or 'midra -h' for help.");
            return 1;
        }

        if (common.shuffle)
        {
            Collections.shuffle(playlist);
        }

        // ── Port selection ────────────────────────────────────────────────────
        int portIndex;
        if (isSoftSynth)
        {
            portIndex = 0;
        }
        else if (portQuery.isPresent())
        {
            portIndex = findPortIndex(ports, portQuery.get(), err);
            if (portIndex == -1)
            {
                err.println("Error: Could not find MIDI port matching: " + portQuery.get());
                return 1;
            }
        }
        else if (!isTestMode)
        {
            portIndex = interactivePortSelection(ports, common.uiOptions);
            if (portIndex == -1)
                return 0; // User quit
        }
        else
        {
            portIndex = 0;
        }

        try
        {
            int finalPortIndex = portIndex;
            ports.stream()
                .filter(p -> p.index() == finalPortIndex)
                .findFirst()
                .ifPresent(p
                    -> logVerbose(common.verbose,
                        "Opening MIDI Output Port [" + p.index() + "]: \"" + p.name() + "\""));
            provider.openPort(portIndex);

            if (soundbankArg.isPresent() && provider instanceof SoftSynthProvider softSynth)
            {
                softSynth.loadSoundbank(soundbankArg.get());
                logVerbose(common.verbose, "Soundbank loaded: " + soundbankArg.get());
            }

            int currentTrackIdx = 0;
            Optional<String> currentStartTime = common.startTime;

            var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
            activeIO.init();
            boolean isInteractive = activeIO.isInteractive();

            // Shutdown hook for Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                MidirajaCommand.SHUTTING_DOWN = true;
                String safeRestore =
                    (MidirajaCommand.ALT_SCREEN_ACTIVE ? Theme.TERM_ALT_SCREEN_DISABLE : "")
                    + Theme.TERM_MOUSE_DISABLE + Theme.COLOR_RESET + "\033[?7h"
                    + Theme.TERM_SHOW_CURSOR + "\r\033[K\n";
                try
                {
                    activeIO.close();
                }
                catch (Exception _)
                {
                }
                System.out.print(safeRestore);
                System.out.flush();
                try
                {
                    if (portClosed.compareAndSet(false, true))
                    {
                        provider.panic();
                        long endWait = System.currentTimeMillis() + 200;
                        while (System.currentTimeMillis() < endWait)
                        {
                            try
                            {
                                Thread.sleep(Math.max(1, endWait - System.currentTimeMillis()));
                            }
                            catch (Exception ignored)
                            {
                            }
                        }
                        provider.closePort();
                    }
                }
                catch (Exception _)
                {
                }
            }));

            // ── UI mode ───────────────────────────────────────────────────────
            com.midiraja.ui.PlaybackUI ui;
            boolean useAltScreen = false;
            UiModeOptions uiOpts = common.uiOptions;

            if (uiOpts.classicMode)
            {
                ui = new com.midiraja.ui.DumbUI();
            }
            else if (uiOpts.miniMode)
            {
                ui = new com.midiraja.ui.LineUI();
            }
            else if (uiOpts.fullMode)
            {
                ui = new com.midiraja.ui.DashboardUI();
                useAltScreen = true;
            }
            else if (!isInteractive)
            {
                ui = new com.midiraja.ui.DumbUI();
            }
            else if (activeIO.getHeight() < 10)
            {
                ui = new com.midiraja.ui.LineUI();
            }
            else
            {
                ui = new com.midiraja.ui.DashboardUI();
                useAltScreen = true;
            }

            if (useAltScreen && isInteractive)
            {
                out.print("\033[?1049h\033[?25l");
                out.flush();
                MidirajaCommand.ALT_SCREEN_ACTIVE = true;
            }

            try
            {
                while (currentTrackIdx >= 0 && currentTrackIdx < playlist.size())
                {
                    var file = playlist.get(currentTrackIdx);
                    var sequence = MidiSystem.getSequence(file);
                    logVerbose(common.verbose,
                        String.format("Loaded '%s' - Resolution: %d PPQ, Microsecond Length: %d",
                            file.getName(), sequence.getResolution(),
                            sequence.getMicrosecondLength()));

                    String title = com.midiraja.midi.MidiUtils.extractSequenceTitle(sequence);
                    var context =
                        new PlaylistContext(playlist, currentTrackIdx, ports.get(portIndex), title);

                    var engine = new PlaybackEngine(sequence, provider, context, common.volume,
                        common.speed, currentStartTime, common.transpose);
                    if (common.ignoreSysex)
                        engine.setIgnoreSysex(true);
                    if (common.resetType.isPresent())
                        engine.setInitialResetType(common.resetType);

                    var status = ScopedValue.where(TerminalIO.CONTEXT, activeIO)
                                     .call(() -> engine.start(ui));

                    currentStartTime = Optional.empty();
                    common.volume = (int) (engine.getVolumeScale() * 100);
                    common.speed = engine.getCurrentSpeed();
                    common.transpose = Optional.of(engine.getCurrentTranspose());

                    switch (status)
                    {
                        case QUIT_ALL -> currentTrackIdx = -1;
                        case PREVIOUS ->
                        {
                            currentTrackIdx--;
                            if (common.loop && currentTrackIdx < 0)
                                currentTrackIdx = playlist.size() - 1;
                            else
                                currentTrackIdx = Math.max(0, currentTrackIdx);
                        }
                        case FINISHED, NEXT ->
                        {
                            currentTrackIdx++;
                            if (common.loop && currentTrackIdx >= playlist.size())
                            {
                                currentTrackIdx = 0;
                                if (common.shuffle)
                                    Collections.shuffle(playlist);
                            }
                        }
                    }
                }
            }
            finally
            {
                activeIO.close();
                if (isInteractive)
                {
                    String safeRestore = (useAltScreen ? Theme.TERM_ALT_SCREEN_DISABLE : "")
                        + Theme.TERM_MOUSE_DISABLE + Theme.COLOR_RESET + "\033[?7h"
                        + Theme.TERM_SHOW_CURSOR + "\r\033[K\n";
                    out.print(safeRestore);
                    out.flush();
                }
            }
        }
        catch (Exception e)
        {
            err.println("Error during playback: " + e.getMessage());
            if (common.verbose)
                e.printStackTrace(err);
            return 1;
        }
        finally
        {
            if (portClosed.compareAndSet(false, true))
            {
                provider.panic();
                long endWait = System.currentTimeMillis() + 200;
                while (System.currentTimeMillis() < endWait)
                {
                    try
                    {
                        Thread.sleep(Math.max(1, endWait - System.currentTimeMillis()));
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                provider.closePort();
            }
        }

        return 0;
    }

    // ── Port selection ─────────────────────────────────────────────────────────

    /** Finds the port index for an explicit query (index number or partial name). */
    public static int findPortIndex(List<MidiPort> ports, String query, PrintStream err)
    {
        try
        {
            int idx = Integer.parseInt(query);
            if (ports.stream().anyMatch(p -> p.index() == idx))
                return idx;
        }
        catch (NumberFormatException _)
        {
        }

        var lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
        var matches =
            ports.stream()
                .filter(p -> p.name().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery))
                .toList();

        if (matches.size() == 1)
            return matches.get(0).index();
        if (matches.size() > 1)
        {
            err.println("Ambiguous port name. Matches:");
            matches.forEach(m -> err.println("  [" + m.index() + "] " + m.name()));
        }
        return -1;
    }

    private int interactivePortSelection(List<MidiPort> ports, UiModeOptions uiOpts)
        throws Exception
    {
        if (ports.isEmpty())
            return -1;

        var probe = new JLineTerminalIO();
        probe.init();
        boolean isInteractive = probe.isInteractive();
        int termHeight = probe.getHeight();
        probe.close();

        if (!isInteractive)
            return fallbackPortSelection(ports);

        boolean willBeFullMode = uiOpts.fullMode || (!uiOpts.miniMode && termHeight >= 10);
        return willBeFullMode ? fullScreenPortSelection(ports) : dynamicPortSelection(ports);
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
            terminal.writer().print(Theme.TERM_HIDE_CURSOR);
            boolean firstDraw = true;

            while (true)
            {
                if (!firstDraw)
                    terminal.writer().print("\033[" + (numPorts + 1) + "A");
                firstDraw = false;

                terminal.writer().println("Available MIDI Output Devices:");
                for (int i = 0; i < numPorts; i++)
                {
                    String prefix = (i == selectedIndex) ? " > " : "   ";
                    terminal.writer().println(prefix + ports.get(i).name());
                }
                terminal.writer().flush();

                int ch = reader.read(10);
                if (ch <= 0)
                    continue;

                if (ch == 'q' || ch == 'Q')
                {
                    clearMenu(terminal, numPorts);
                    terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return -1;
                }
                if (ch == 13 || ch == 10)
                {
                    clearMenu(terminal, numPorts);
                    terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return ports.get(selectedIndex).index();
                }
                if (ch == 27)
                {
                    int next1 = reader.read(2);
                    if (next1 == '[')
                    {
                        int next2 = reader.read(2);
                        if (next2 == 'A')
                            selectedIndex = (selectedIndex - 1 + numPorts) % numPorts;
                        else if (next2 == 'B')
                            selectedIndex = (selectedIndex + 1) % numPorts;
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
            terminal.writer().print(Theme.TERM_ALT_SCREEN_ENABLE + Theme.TERM_HIDE_CURSOR);
            terminal.writer().flush();

            while (true)
            {
                int width = terminal.getWidth();
                int height = terminal.getHeight();
                int boxWidth = 50;
                int boxHeight = numPorts + 4;
                int padLeft = Math.max(0, (width - boxWidth) / 2);
                int padTop = Math.max(0, (height - boxHeight) / 2);

                com.midiraja.ui.ScreenBuffer buffer = new com.midiraja.ui.ScreenBuffer(4096);
                buffer.append(Theme.TERM_CURSOR_HOME).append(Theme.TERM_CLEAR_TO_END);
                buffer.repeat("\n", padTop);

                String title = " SELECT MIDI TARGET ";
                int titlePad = (boxWidth - title.length() - 2) / 2;
                buffer.repeat(" ", padLeft)
                    .append(Theme.COLOR_HIGHLIGHT)
                    .repeat(Theme.DECORATOR_LINE, titlePad)
                    .append(Theme.COLOR_RESET)
                    .append(Theme.FORMAT_INVERT)
                    .append(title)
                    .append(Theme.COLOR_RESET)
                    .append(Theme.COLOR_HIGHLIGHT)
                    .repeat(Theme.DECORATOR_LINE, boxWidth - titlePad - title.length())
                    .append(Theme.COLOR_RESET)
                    .appendLine();

                for (int i = 0; i < numPorts; i++)
                {
                    buffer.repeat(" ", padLeft);
                    String portName = ports.get(i).name();
                    if (portName.length() > boxWidth - 8)
                        portName = portName.substring(0, boxWidth - 11) + "...";
                    if (i == selectedIndex)
                    {
                        buffer.append("  ")
                            .append(Theme.COLOR_HIGHLIGHT)
                            .append(Theme.CHAR_ARROW_RIGHT)
                            .append(" [")
                            .append(String.valueOf(i))
                            .append("] ")
                            .append(portName)
                            .append(Theme.COLOR_RESET)
                            .appendLine();
                    }
                    else
                    {
                        buffer.append("    [")
                            .append(String.valueOf(i))
                            .append("] ")
                            .append(portName)
                            .appendLine();
                    }
                }

                buffer.repeat(" ", padLeft)
                    .append(Theme.COLOR_HIGHLIGHT)
                    .repeat(Theme.BORDER_HORIZONTAL, boxWidth)
                    .append(Theme.COLOR_RESET)
                    .appendLine();
                String footer = "[▲/▼] Move   [Enter] Select   [Q] Quit";
                int footerPad = (boxWidth - footer.length()) / 2;
                buffer.repeat(" ", padLeft + footerPad)
                    .append(Theme.COLOR_HIGHLIGHT)
                    .append(footer)
                    .append(Theme.COLOR_RESET)
                    .appendLine();

                terminal.writer().print(buffer.toString());
                terminal.writer().flush();

                int ch = reader.read(50);
                if (ch <= 0)
                    continue;

                if (ch == 'q' || ch == 'Q')
                {
                    terminal.writer().print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return -1;
                }
                if (ch == 13 || ch == 10)
                {
                    terminal.writer().print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return ports.get(selectedIndex).index();
                }
                if (ch == 27)
                {
                    int next1 = reader.read(2);
                    if (next1 == '[')
                    {
                        int next2 = reader.read(2);
                        if (next2 == 'A')
                            selectedIndex = (selectedIndex - 1 + numPorts) % numPorts;
                        else if (next2 == 'B')
                            selectedIndex = (selectedIndex + 1) % numPorts;
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
            terminal.writer().println(Theme.TERM_CLEAR_TO_EOL);
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
        var scanner = new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        if (scanner.hasNextInt())
        {
            int selected = scanner.nextInt();
            if (ports.stream().anyMatch(p -> p.index() == selected))
                return selected;
        }
        err.println("Invalid port selection.");
        return -1;
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private void logVerbose(boolean verbose, String message)
    {
        if (verbose)
            err.println("[VERBOSE] " + message);
    }
}
