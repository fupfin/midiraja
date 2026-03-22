/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.lang.Math.max;
import static java.util.Locale.ROOT;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.engine.PlaybackEngine;
import com.fupfin.midiraja.engine.PlaybackEngineFactory;

import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.JLineTerminalIO;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.midi.MidiUtils;
import com.fupfin.midiraja.midi.SoftSynthProvider;
import com.fupfin.midiraja.ui.DashboardUI;
import com.fupfin.midiraja.ui.DumbUI;
import com.fupfin.midiraja.ui.LineUI;
import com.fupfin.midiraja.ui.PlaybackUI;
import com.fupfin.midiraja.ui.Theme;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.midi.Sequence;
import org.jspecify.annotations.Nullable;

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
    private final PlaybackEngineFactory engineFactory;

    public void setFxOptions(FxOptions fx) { this.fxOptions = fx; }

    /**
     * Set to true for synths that own the audio pipeline (gus, psg, tsf).
     * OPL/OPN show retro via dacMode in the port name instead, so they leave this false.
     * Fluid, Munt, and OS ports don't process retro at all.
     */
    public void setIncludeRetroInSuffix(boolean include) { this.includeRetroInSuffix = include; }

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
        this(out, err, terminalIO, isTestMode, PlaybackEngine::new);
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
     * @param common shared playback options (may be mutated by M3U directives)
     * @return picocli exit code (0 = success, 1 = error)
     */
    public int run(MidiOutProvider provider, boolean isSoftSynth, Optional<String> portQuery,
            Optional<String> soundbankArg, List<File> rawFiles, CommonOptions common,
            List<String> originalArgs)
            throws Exception
    {
        // FAIL-FAST VALIDATION
        if (validateFiles(rawFiles) != 0)
        {
            return 1;
        }

        AtomicBoolean portClosed = new AtomicBoolean(false);
        var ports = provider.getOutputPorts();

        // ── Playlist ──────────────────────────────────────────────────────────
        PlaylistParser parser = new PlaylistParser(err, common.isVerbose());
        List<File> playlist = parser.parse(rawFiles, common);

        if (playlist.isEmpty())
        {
            err.println("Error: No MIDI files specified. Use 'midra <file1.mid>' "
                    + "or 'midra -h' for help.");
            return 1;
        }

        // Auto-save session to history
        if (!originalArgs.isEmpty()) {
            new SessionHistory().recordAuto(originalArgs);
        }

        // ── Port selection ────────────────────────────────────────────────────
        int portIndex = resolvePortIndex(ports, isSoftSynth, portQuery, common.uiOptions);
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

            Optional<String> currentStartTime = common.startTime;

            var activeIO = this.terminalIO != null ? this.terminalIO : new JLineTerminalIO();
            activeIO.init();
            boolean isInteractive = activeIO.isInteractive();

            // Shutdown hook for Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                MidirajaCommand.SHUTTING_DOWN = true;
                // Always include alt-screen exit: harmless no-op if not in alt screen.
                // Use the same output path (System.out) that was used to enter alt screen
                // so the sequence reaches the terminal reliably.
                String safeRestore = Theme.TERM_ALT_SCREEN_DISABLE
                                + Theme.TERM_MOUSE_DISABLE + Theme.COLOR_RESET + Theme.TERM_AUTOWRAP_ON
                                + Theme.TERM_SHOW_CURSOR + "\r\033[K\n";
                if (isInteractive)
                {
                    // Write via System.out first (same channel used to enter alt screen).
                    try
                    {
                        System.out.print(safeRestore);
                        System.out.flush();
                    }
                    catch (Exception ignored)
                    {
                    }
                    // Also write directly to /dev/tty for robustness.
                    try (var tty = new java.io.FileOutputStream("/dev/tty"))
                    {
                        tty.write(safeRestore.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        tty.flush();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                try
                {
                    activeIO.close();
                }
                catch (Exception e)
                {
                    log.warning("Error closing terminal IO: " + e.getMessage());
                }
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
                                Thread.sleep(max(1, endWait - System.currentTimeMillis()));
                            }
                            catch (Exception ignored)
                            {
                                log.warning("Error during shutdown sleep: " + ignored.getMessage());
                            }
                        }
                        provider.closePort();
                    }
                }
                catch (Exception e)
                {
                    log.warning("Error during shutdown: " + e.getMessage());
                }
            }));

            // ── UI mode ───────────────────────────────────────────────────────
            var uiResult = buildUI(common.uiOptions, isInteractive, activeIO.getHeight());
            PlaybackUI ui = uiResult.ui();
            boolean useAltScreen = uiResult.useAltScreen();

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

            try
            {
                playPlaylistLoop(playlist, provider, ports.get(portIndex), common, ui, activeIO,
                        currentStartTime, originalArgs);
            }
            finally
            {
                System.setErr(savedErr);
                MidirajaCommand.ALT_SCREEN_ACTIVE = false;
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
                    catch (Exception ignored)
                    {
                        log.warning("Error during cleanup sleep: " + ignored.getMessage());
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
        catch (NumberFormatException _)
        {
        }

        var lowerQuery = query.toLowerCase(ROOT);
        var matches = ports.stream()
                .filter(p -> p.name().toLowerCase(ROOT).contains(lowerQuery))
                .toList();

        if (matches.size() == 1) return matches.get(0).index();
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
        if (ports.isEmpty()) return -1;
        var items = ports.stream()
                .map(p -> TerminalSelector.Item.of(p.index(), "[" + p.index() + "] " + p.name(), ""))
                .toList();
        var config = new TerminalSelector.FullScreenConfig(" SELECT MIDI TARGET ", 50, 50);
        Integer result = TerminalSelector.select(items, config, uiOpts.fullMode, uiOpts.miniMode,
                uiOpts.classicMode, err);
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
            Optional<String> portQuery, UiModeOptions uiOpts) throws Exception
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
            return interactivePortSelection(ports, uiOpts);
        }
        else
        {
            return 0;
        }
    }

    record UIResult(PlaybackUI ui, boolean useAltScreen) {}

    UIResult buildUI(UiModeOptions uiOpts, boolean isInteractive, int activeIOHeight)
    {
        if (uiOpts.classicMode)  return new UIResult(new DumbUI(),      false);
        if (uiOpts.miniMode)     return new UIResult(new LineUI(),      false);
        if (uiOpts.fullMode)     return new UIResult(new DashboardUI(), true);
        if (!isInteractive)      return new UIResult(new DumbUI(),      false);
        if (activeIOHeight < 10) return new UIResult(new LineUI(),      false);
        return                        new UIResult(new DashboardUI(), true);
    }

    private void playPlaylistLoop(List<File> playlist, MidiOutProvider provider, MidiPort port,
            CommonOptions common, PlaybackUI ui, TerminalIO activeIO,
            Optional<String> initialStartTime, List<String> originalArgs) throws Exception
    {
        // Use int[][] so the lambda can capture playOrderHolder (effectively final reference)
        // while playOrderHolder[0] can be reassigned on loop wrap-around.
        int[][] playOrderHolder = { buildPlayOrder(playlist.size(), common.shuffle) };
        int[] currentIdxHolder = {0};
        Optional<String> currentStartTime = initialStartTime;
        boolean wasPaused = false;
        PlaybackStatus prevStatus = PlaybackStatus.FINISHED;

        while (currentIdxHolder[0] >= 0 && currentIdxHolder[0] < playlist.size())
        {
            var file = playlist.get(playOrderHolder[0][currentIdxHolder[0]]);
            try
            {
                var sequence = MidiUtils.loadSequence(file);
                logVerbose(common.isVerbose(),
                        String.format("Loaded '%s' - Resolution: %d PPQ, Microsecond Length: %d",
                                file.getName(), sequence.getResolution(),
                                sequence.getMicrosecondLength()));

                var title = MidiUtils.extractSequenceTitle(sequence);

                // Build ordered file view for display (snapshot at track-start time)
                List<File> orderedFiles =
                        java.util.stream.IntStream.of(playOrderHolder[0]).mapToObj(playlist::get).toList();
                var context = new PlaylistContext(orderedFiles, currentIdxHolder[0], port, title,
                        common.loop, common.shuffle);

                var engine = engineFactory.create(sequence, provider, context, common.volume,
                        common.speed, currentStartTime, common.transpose);

                if (common.ignoreSysex) engine.setIgnoreSysex(true);
                if (common.resetType.isPresent()) engine.setInitialResetType(common.resetType);
                if (wasPaused) engine.setInitiallyPaused();
                engine.setFilterDescription(buildFxDescription(fxOptions));
                engine.setPortSuffix(buildRetroSuffix(common, includeRetroInSuffix));

                boolean initiallyBookmarked =
                        !originalArgs.isEmpty() && new SessionHistory().isBookmarked(originalArgs);
                engine.setBookmarked(initiallyBookmarked);
                engine.setBookmarkCallback(bookmarked -> {
                    var h = new SessionHistory();
                    if (bookmarked) {
                        h.saveBookmark(originalArgs);
                    } else {
                        h.removeBookmarkByArgs(originalArgs);
                    }
                });

                // Wire shuffle callback: mutates remaining slice of playOrderHolder[0] live
                // and immediately updates the playlist panel display.
                engine.setShuffleCallback(newState -> {
                    reshuffleRemaining(playOrderHolder[0], currentIdxHolder[0], newState);
                    var newOrdered = java.util.stream.IntStream.of(playOrderHolder[0])
                            .mapToObj(playlist::get).toList();
                    var newCtx = new com.fupfin.midiraja.engine.PlaylistContext(
                            newOrdered, currentIdxHolder[0], port,
                            engine.getContext().sequenceTitle(),
                            engine.isLoopEnabled(), newState);
                    engine.firePlayOrderChanged(newCtx);
                });

                boolean isLastTrack = (currentIdxHolder[0] == playlist.size() - 1);
                if (!suppressHoldAtEnd && isLastTrack && !common.loop && (ui instanceof DashboardUI))
                {
                    engine.setHoldAtEnd(true);
                }

                var status = ScopedValue.where(TerminalIO.CONTEXT, activeIO).call(() -> engine.start(ui));
                lastRawStatus = status;
                prevStatus = status;

                currentStartTime = Optional.empty();
                common.volume = (int) (engine.getVolumeScale() * 100);
                common.speed = engine.getCurrentSpeed();
                common.transpose = Optional.of(engine.getCurrentTranspose());
                common.loop = engine.isLoopEnabled();
                common.shuffle = engine.isShuffleEnabled();
                wasPaused = engine.isPaused();

                int nextIdx = handlePlaybackStatus(status, currentIdxHolder[0], playlist, common);

                // Rebuild play order when loop wraps around to the beginning
                if (prevStatus == PlaybackStatus.FINISHED && nextIdx == 0
                        && currentIdxHolder[0] == playlist.size() - 1)
                {
                    playOrderHolder[0] = buildPlayOrder(playlist.size(), engine.isShuffleEnabled());
                }

                currentIdxHolder[0] = nextIdx;
            }
            catch (Exception e)
            {
                err.println("\n[Error] Failed to load MIDI file: " + file.getName());
                err.println("        Reason: " + e.getMessage());
                err.println("        Skipping to the next track...");
                currentIdxHolder[0]++;
            }
        }
    }

    static int[] buildPlayOrder(int size, boolean shuffle)
    {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) order[i] = i;
        if (shuffle)
        {
            var rng = new java.util.Random();
            for (int i = size - 1; i > 0; i--)
            {
                int j = rng.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
        }
        return order;
    }

    static void reshuffleRemaining(int[] playOrder, int currentIdx, boolean shuffleOn)
    {
        int n = playOrder.length;
        if (n <= 1) return;
        int currentSong = playOrder[currentIdx];
        if (shuffleOn)
        {
            // Full Fisher-Yates shuffle of entire array
            var rng = new java.util.Random();
            for (int i = n - 1; i > 0; i--)
            {
                int j = rng.nextInt(i + 1);
                int tmp = playOrder[i];
                playOrder[i] = playOrder[j];
                playOrder[j] = tmp;
            }
        }
        else
        {
            java.util.Arrays.sort(playOrder);
        }
        // Restore the currently-playing song to currentIdx so the index stays valid
        for (int i = 0; i < n; i++)
        {
            if (playOrder[i] == currentSong)
            {
                playOrder[i] = playOrder[currentIdx];
                playOrder[currentIdx] = currentSong;
                break;
            }
        }
    }

    int handlePlaybackStatus(PlaybackStatus status, int currentTrackIdx,
            List<File> playlist, CommonOptions common)
    {
        return switch (status)
        {
            case QUIT_ALL, RESUME_SESSION -> -1;
            case PREVIOUS ->
            {
                if (currentTrackIdx == 0)
                    yield exitOnNavBoundary ? -1 : playlist.size() - 1;
                yield currentTrackIdx - 1;
            }
            case NEXT ->
            {
                int next = currentTrackIdx + 1;
                if (next >= playlist.size())
                    yield exitOnNavBoundary ? next : 0;
                yield next;
            }
            case FINISHED ->
            {
                int next = currentTrackIdx + 1;
                if (next >= playlist.size())
                {
                    if (common.loop)
                    {
                        yield 0;
                    }
                    yield next; // exits the loop
                }
                yield next;
            }
            default -> currentTrackIdx;
        };
    }

    /** DSP effects line (reverb/chorus/tube/eq) — always shows all four, even when off or null. */
    static String buildFxDescription(@Nullable FxOptions fx)
    {
        var parts = new java.util.ArrayList<String>();
        String hi  = com.fupfin.midiraja.ui.Theme.COLOR_HIGHLIGHT;
        String dim = com.fupfin.midiraja.ui.Theme.COLOR_DIM_FG;
        String rst = com.fupfin.midiraja.ui.Theme.COLOR_RESET;

        String reverbVal = fx != null ? fx.reverb.map(r -> r + " " + (int) fx.reverbLevel + "%").orElse(null) : null;
        parts.add("reverb: " + (reverbVal != null ? hi + reverbVal + rst : dim + "off" + rst));

        String chorusVal = fx != null ? fx.chorus.map(c -> (int) (float) c + "%").orElse(null) : null;
        parts.add("chorus: " + (chorusVal != null ? hi + chorusVal + rst : dim + "off" + rst));

        String tubeVal = fx != null ? fx.tubeDrive.map(t -> (int) (float) t + "%").orElse(null) : null;
        parts.add("tube: " + (tubeVal != null ? hi + tubeVal + rst : dim + "off" + rst));

        boolean eqActive = fx != null && (fx.eqBass != 50 || fx.eqMid != 50 || fx.eqTreble != 50
                || fx.lpfFreq.isPresent() || fx.hpfFreq.isPresent());
        parts.add("eq: " + (eqActive ? hi + "on" + rst : dim + "off" + rst));

        return String.join("  |  ", parts);
    }

    /** Retro/speaker suffix for the Port line — only non-empty when at least one is active. */
    static String buildRetroSuffix(CommonOptions common, boolean includeRetro)
    {
        var parts = new java.util.ArrayList<String>();
        String hi  = com.fupfin.midiraja.ui.Theme.COLOR_HIGHLIGHT;
        String rst = com.fupfin.midiraja.ui.Theme.COLOR_RESET;
        if (includeRetro) common.retroMode.ifPresent(r -> parts.add("retro: " + hi + r + rst));
        common.speakerProfile.ifPresent(s -> parts.add("speaker: " + hi + s + rst));
        return parts.isEmpty() ? "" : "  |  " + String.join("  |  ", parts);
    }
}
