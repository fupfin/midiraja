/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaybackEngineFactory;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.midi.MidiUtils;
import com.fupfin.midiraja.ui.DashboardUI;
import com.fupfin.midiraja.ui.PlaybackUI;
import java.io.File;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Executes a playlist of MIDI files using a given provider and port.
 * Owns the playback loop, track navigation, and NowPlaying UI updates.
 * Terminal setup and port selection are handled by the caller ({@link PlaybackRunner}).
 */
class PlaylistPlayer {

    private final PlaybackEngineFactory engineFactory;
    private final @Nullable FxOptions fxOptions;
    private final boolean includeRetroInSuffix;
    private final boolean suppressHoldAtEnd;
    private final boolean exitOnNavBoundary;
    private final PrintStream err;

    PlaylistPlayer(PlaybackEngineFactory engineFactory,
                   @Nullable FxOptions fxOptions,
                   boolean includeRetroInSuffix,
                   boolean suppressHoldAtEnd,
                   boolean exitOnNavBoundary,
                   PrintStream err)
    {
        this.engineFactory = engineFactory;
        this.fxOptions = fxOptions;
        this.includeRetroInSuffix = includeRetroInSuffix;
        this.suppressHoldAtEnd = suppressHoldAtEnd;
        this.exitOnNavBoundary = exitOnNavBoundary;
        this.err = err;
    }

    /**
     * Runs the full playlist loop until the user quits or all tracks finish.
     *
     * @return the last raw {@link PlaybackStatus} produced by the final engine
     */
    PlaybackStatus play(List<File> playlist, MidiOutProvider provider, MidiPort port,
                        CommonOptions common, PlaybackUI ui, TerminalIO io,
                        Optional<Long> initialStartTime, List<String> originalArgs)
            throws Exception
    {
        // Use int[][] so the lambda can capture playOrderHolder (effectively final reference)
        // while playOrderHolder[0] can be reassigned on loop wrap-around.
        int[][] playOrderHolder = { buildPlayOrder(playlist.size(), common.shuffle) };
        int[] currentIdxHolder = {0};
        Optional<Long> currentStartTime = initialStartTime;
        boolean wasPaused = false;
        PlaybackStatus lastRawStatus = PlaybackStatus.FINISHED;

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

                var engine = engineFactory.create(sequence, provider, context,
                        new StandardPlaybackPipeline(provider, common.volume,
                                common.transpose.orElse(0)),
                        () -> MidirajaCommand.SHUTTING_DOWN,
                        common.speed, currentStartTime);

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
                    var newCtx = new PlaylistContext(
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

                var status = ScopedValue.where(TerminalIO.CONTEXT, io).call(() -> engine.start(ui));
                lastRawStatus = status;

                currentStartTime = Optional.empty();
                common.volume = (int) (engine.getVolumeScale() * 100);
                common.speed = engine.getCurrentSpeed();
                common.transpose = Optional.of(engine.getCurrentTranspose());
                common.loop = engine.isLoopEnabled();
                common.shuffle = engine.isShuffleEnabled();
                wasPaused = engine.isPaused();

                int nextIdx = handlePlaybackStatus(status, currentIdxHolder[0], playlist, common);

                // Rebuild play order when loop wraps around to the beginning
                if (status == PlaybackStatus.FINISHED && nextIdx == 0
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

        return lastRawStatus;
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

    private void logVerbose(boolean verbose, String message)
    {
        if (verbose) err.println("[VERBOSE] " + message);
    }
}
