/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.lang.Math.max;
import static java.util.Locale.ROOT;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

import javax.sound.midi.MidiSystem;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.media.MusicFormatLoader;
import com.fupfin.midiraja.engine.MidiPlaybackEngine;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaybackEngineFactory;
import com.fupfin.midiraja.io.JLineTerminalIO;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.media.MediaKeyIntegration;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.midi.SoftSynthProvider;
import com.fupfin.midiraja.ui.DashboardUI;
import com.fupfin.midiraja.ui.DumbUI;
import com.fupfin.midiraja.ui.LineUI;
import com.fupfin.midiraja.ui.PlaybackUI;
import com.fupfin.midiraja.ui.Theme;

/**
 * Orchestrates MIDI playback: builds the playlist, selects a port, opens the provider, sets up the
 * terminal and shutdown hook, then drives the playlist loop.
 * <p>
 * Shared by {@link MidirajaCommand} (native OS MIDI) and all soft-synth subcommands.
 */
@SuppressWarnings("EmptyCatch")
public class PlaybackRunner
{
    private static final java.util.logging.Logger log =
            java.util.logging.Logger.getLogger(PlaybackRunner.class.getName());
    private final PrintStream out;
    private final PrintStream err;
    @Nullable
    private final TerminalIO terminalIO;
    private final boolean isTestMode;
    private PlaybackStatus lastRawStatus = PlaybackStatus.FINISHED;
    private boolean suppressAltScreenRestore = false;
    private boolean suppressHoldAtEnd = false;
    private boolean exitOnNavBoundary = false;
    @Nullable
    private FxOptions fxOptions = null;
    private boolean includeRetroInSuffix = false;
    private java.util.Set<Integer> mutedChannels = java.util.Set.of();
    private final PlaybackEngineFactory engineFactory;

    public void setFxOptions(FxOptions fx) { this.fxOptions = fx; }

    /**
     * Set to true for synths that own the audio pipeline (gus, psg, tsf).
     * OPL/OPN show retro via dacMode in the port name instead, so they leave this false.
     * Fluid, Munt, and OS ports don't process retro at all.
     */
    public void setIncludeRetroInSuffix(boolean include) { this.includeRetroInSuffix = include; }

    /** MIDI channel indices (0-based) to silence during VGM conversion. */
    public void setMutedChannels(java.util.Set<Integer> channels) { this.mutedChannels = channels; }

    public PlaybackStatus getLastRawStatus()
    {
        return lastRawStatus;
    }

    public void setSuppressAltScreenRestore(boolean suppress)
    {
        this.suppressAltScreenRestore = suppress;
    }

    /** When true, the engine will not hold at the end of the last track. */
    public void setSuppressHoldAtEnd(boolean suppress)
    {
        this.suppressHoldAtEnd = suppress;
    }

    /**
     * When true, PREVIOUS at the first track and NEXT at the last track exit the playlist loop
     * instead of wrapping around. The caller can inspect {@link #getLastRawStatus()} to determine
     * the direction of navigation.
     */
    public void setExitOnNavBoundary(boolean exit)
    {
        this.exitOnNavBoundary = exit;
    }

    public PlaybackRunner(PrintStream out, PrintStream err, @Nullable TerminalIO terminalIO,
            boolean isTestMode)
    {
        this(out, err, terminalIO, isTestMode, MidiPlaybackEngine::new);
    }

    /** Test constructor: injects a custom engine factory. */
    public PlaybackRunner(PrintStream out, PrintStream err, @Nullable TerminalIO terminalIO,
            boolean isTestMode, PlaybackEngineFactory engineFactory)
    {
        this.out = out;
        this.err = err;
        this.terminalIO = terminalIO;
        this.isTestMode = isTestMode;
        this.engineFactory = engineFactory;
    }

    /**
     * Run the full playback lifecycle.
     *
     * @param provider pre-constructed MIDI provider
     * @param isSoftSynth if {@code true}, port selection is skipped (port 0 is always used)
     * @param portQuery for native MIDI: optional explicit port index or name
     * @param soundbankArg for soft synths: argument to pass to
     *        {@link SoftSynthProvider#loadSoundbank}
     * @param rawFiles raw file/dir/playlist arguments from the command line
     * @param common shared playback options; M3U playlist directives are applied to this
     *        object via {@link PlaylistDirectives#applyTo} after parsing
     * @return picocli exit code (0 = success, 1 = error)
     */
    public int run(MidiOutProvider provider, boolean isSoftSynth, Optional<String> portQuery,
            Optional<String> soundbankArg, List<File> rawFiles, CommonOptions common,
            List<String> originalArgs)
            throws Exception
    {
        // Apply --mute from CommonOptions if not already set via setMutedChannels().
        if (mutedChannels.isEmpty())
            mutedChannels = common.parsedMutedChannels(err);

        // FAIL-FAST VALIDATION
        if (validateFiles(rawFiles) != 0)
        {
            return 1;
        }

        AtomicBoolean portClosed = new AtomicBoolean(false);
        var ports = provider.getOutputPorts();

        // ── Playlist ──────────────────────────────────────────────────────────
        PlaylistParser parser = new PlaylistParser(err, common.isVerbose());
        var parseResult = parser.parse(rawFiles, common);
        parseResult.directives().applyTo(common);
        List<File> playlist = parseResult.files();

        if (playlist.isEmpty())
        {
            err.println("Error: No MIDI files specified. Use 'midra <file1.mid>' "
                    + "or 'midra -h' for help.");
            return 1;
        }

        // ── Export mode: convert and write to file, no playback ──────────────
        if (common.exportMidi.isPresent())
        {
            var input = playlist.get(0);
            var sequence = MusicFormatLoader.load(input, mutedChannels);
            MidiSystem.write(sequence, 1, common.exportMidi.get());
            out.println("Exported: " + common.exportMidi.get());
            return 0;
        }

        // Auto-save session to history
        if (!originalArgs.isEmpty()) {
            new SessionHistory().recordAuto(originalArgs);
        }

        // ── Port selection ────────────────────────────────────────────────────
        int portIndex = resolvePortIndex(ports, isSoftSynth, portQuery, common.uiOptions, common.quietMode);
        if (portIndex == -2) return 1; // Error finding port
        if (portIndex == -1) return 0; // User quit

        try
        {
            int finalPortIndex = portIndex;
            ports.stream().filter(p -> p.index() == finalPortIndex).findFirst()
                    .ifPresent(p -> logVerbose(common.isVerbose(),
                            "Opening MIDI Output Port [" + p.index() + "]: \"" + p.name() + "\""));
            provider.openPort(portIndex);

            if (soundbankArg.isPresent() && provider instanceof SoftSynthProvider softSynth)
            {
                softSynth.loadSoundbank(soundbankArg.get());
                logVerbose(common.isVerbose(), "Soundbank loaded: " + soundbankArg.get());
                // Refresh port list — loadSoundbank may update port names (e.g. Munt ROM version)
                ports = provider.getOutputPorts();
            }

            Optional<Long> currentStartTime = common.startTimeMicroseconds();

            var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
            activeIO.init();
            boolean isInteractive = activeIO.isInteractive();

            // ── UI mode ───────────────────────────────────────────────────────
            var uiResult = buildUI(common.quietMode, common.uiOptions, isInteractive, activeIO.getHeight());
            PlaybackUI ui = uiResult.ui();
            boolean useAltScreen = uiResult.useAltScreen();

            // Shutdown hook for Ctrl+C
            Runtime.getRuntime().addShutdownHook(
                    new Thread(new ShutdownCleaner(isInteractive, activeIO, provider, portClosed,
                            useAltScreen)));

            // Install SIGTSTP/SIGCONT handlers so Ctrl+Z cleanly suspends and restores the UI.
            // Each UI mode encapsulates its own terminal state (alt screen, cursor, autowrap).
            boolean inAltScreen = useAltScreen && isInteractive && !suppressAltScreenRestore;
            activeIO.installSuspendHandlers(
                    () -> ui.suspend(activeIO, inAltScreen),
                    () -> ui.resume(activeIO, inAltScreen));

            PrintStream savedErr = System.err;
            ByteArrayOutputStream errBuffer = null;
            if (useAltScreen && isInteractive && !suppressAltScreenRestore)
            {
                out.print("\033[?1049h\033[?25l");
                out.flush();
                MidirajaCommand.ALT_SCREEN_ACTIVE = true;
                errBuffer = new ByteArrayOutputStream();
                System.setErr(new PrintStream(errBuffer, true));
            }

            var mediaKeys = MediaKeyIntegration.create();
            try
            {
                var player = new PlaylistPlayer(engineFactory, fxOptions, includeRetroInSuffix,
                        suppressHoldAtEnd, exitOnNavBoundary, err, mediaKeys, mutedChannels);
                lastRawStatus = player.play(playlist, provider, ports.get(portIndex), common,
                        ui, activeIO, currentStartTime, originalArgs);
            }
            finally
            {
                System.setErr(savedErr);
                MidirajaCommand.ALT_SCREEN_ACTIVE = false;
                mediaKeys.close();    // stop native thread BEFORE tearing down terminal I/O
                activeIO.close();
                if (isInteractive)
                {
                    String safeRestore = (useAltScreen && !suppressAltScreenRestore
                                    ? Theme.TERM_ALT_SCREEN_DISABLE : "")
                            + Theme.TERM_MOUSE_DISABLE + Theme.COLOR_RESET + Theme.TERM_AUTOWRAP_ON
                            + Theme.TERM_SHOW_CURSOR + "\r\033[K\n";
                    out.print(safeRestore);
                    out.flush();
                }
                if (errBuffer != null && errBuffer.size() > 0)
                    savedErr.print(errBuffer.toString());
            }
        }
        catch (Exception e)
        {
            log.warning("Error during playback: " + e.getMessage());
            err.println("Error during playback: " + e.getMessage());
            if (common.isVerbose()) e.printStackTrace(err);
            return 1;
        }
        finally
        {
            // Normal-path port close. The shutdown hook (ShutdownCleaner) also
            // closes the port; portClosed guards against double-close.
            if (portClosed.compareAndSet(false, true))
            {
                provider.panic();
                long endWait = System.currentTimeMillis() + 200;
                while (System.currentTimeMillis() < endWait)
                {
                    try
                    {
                        Thread.sleep(max(1, endWait - System.currentTimeMillis()));
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                provider.closePort();
            }
        }

        if (lastRawStatus == PlaybackStatus.RESUME_SESSION)
            return new ResumeCommand().call();

        return 0;
    }

    // ── Port selection ─────────────────────────────────────────────────────────

    /**
     * Finds the port index for an explicit query (index number or partial name).
     */
    public static int findPortIndex(List<MidiPort> ports, String query, PrintStream err)
    {
        try
        {
            int idx = Integer.parseInt(query);
            if (ports.stream().anyMatch(p -> p.index() == idx)) return idx;
        }
        catch (NumberFormatException e)
        {
        }

        var lowerQuery = query.toLowerCase(ROOT);
        var matches = ports.stream()
                .filter(p -> p.name().toLowerCase(ROOT).contains(lowerQuery))
                .toList();

        if (matches.size() == 1) return matches.getFirst().index();
        if (matches.size() > 1)
        {
            err.println("Ambiguous port name. Matches:");
            matches.forEach(m -> err.println("  [" + m.index() + "] " + m.name()));
        }
        return -1;
    }

    private int interactivePortSelection(List<MidiPort> ports, UiModeOptions uiOpts, boolean quietMode)
            throws Exception
    {
        if (ports.isEmpty()) return -1;
        var items = ports.stream()
                .map(p -> TerminalSelector.Item.of(p.index(), "[" + p.index() + "] " + p.name(), ""))
                .toList();
        var config = new TerminalSelector.FullScreenConfig(" SELECT MIDI TARGET ", 50, 50);
        Integer result = TerminalSelector.select(items, config, uiOpts.fullMode, uiOpts.miniMode,
                quietMode || uiOpts.classicMode, err);
        return result != null ? result : -1;
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private void logVerbose(boolean verbose, String message)
    {
        if (verbose) err.println("[VERBOSE] " + message);
    }


    private int validateFiles(List<File> rawFiles)
    {
        if (rawFiles != null)
        {
            for (File f : rawFiles)
            {
                f = PlaylistParser.normalize(f);
                if (!f.exists())
                {
                    err.println(
                            "Error: The file or directory '" + f.getPath() + "' does not exist.");
                    err.println(
                            "Hint: Did you misspell a command or option? (e.g. 'midra fluidsynth' instead of 'midra fluid')");
                    err.println("Run 'midra --help' for usage instructions.");
                    return 1;
                }
            }
        }
        return 0;
    }

    private int resolvePortIndex(List<MidiPort> ports, boolean isSoftSynth,
            Optional<String> portQuery, UiModeOptions uiOpts, boolean quietMode) throws Exception
    {
        if (isSoftSynth)
        {
            return 0;
        }
        else if (portQuery.isPresent())
        {
            int idx = findPortIndex(ports, portQuery.get(), err);
            if (idx == -1)
            {
                err.println("Error: Could not find MIDI port matching: " + portQuery.get());
                return -2;
            }
            return idx;
        }
        else if (!isTestMode)
        {
            return interactivePortSelection(ports, uiOpts, quietMode);
        }
        else
        {
            return 0;
        }
    }

    record UIResult(PlaybackUI ui, boolean useAltScreen) {}

    UIResult buildUI(boolean quietMode, UiModeOptions uiOpts, boolean isInteractive, int activeIOHeight)
    {
        if (quietMode)           return new UIResult(new DumbUI(true),  false);
        if (uiOpts.classicMode)  return new UIResult(new DumbUI(false), false);
        if (uiOpts.miniMode)     return new UIResult(new LineUI(),      false);
        if (uiOpts.fullMode)     return new UIResult(new DashboardUI(), true);
        if (!isInteractive)      return new UIResult(new DumbUI(false), false);
        if (activeIOHeight < 10) return new UIResult(new LineUI(),      false);
        return                        new UIResult(new DashboardUI(), true);
    }
}
