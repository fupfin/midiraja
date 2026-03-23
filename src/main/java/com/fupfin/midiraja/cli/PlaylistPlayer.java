/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaybackEngineFactory;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
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
                        Optional<String> initialStartTime, List<String> originalArgs)
            throws Exception
    {
        // Stub — implementation in Task 2
        return PlaybackStatus.FINISHED;
    }

    static int[] buildPlayOrder(int size, boolean shuffle) {
        return PlaybackRunner.buildPlayOrder(size, shuffle);   // bridge shim — removed in Task 3
    }

    static void reshuffleRemaining(int[] playOrder, int currentIdx, boolean shuffleOn) {
        PlaybackRunner.reshuffleRemaining(playOrder, currentIdx, shuffleOn);  // bridge shim
    }
}
