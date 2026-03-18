/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.ui.DashboardUI;
import com.fupfin.midiraja.ui.DumbUI;
import com.fupfin.midiraja.ui.LineUI;
import java.io.File;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure navigation logic in {@link PlaybackRunner}: playlist status dispatch
 * ({@code handlePlaybackStatus}) and UI mode selection ({@code buildUI}).
 */
class PlaybackRunnerNavigationTest
{
    private final PrintStream nullStream = new PrintStream(PrintStream.nullOutputStream());
    private final List<File> three = List.of(new File("a"), new File("b"), new File("c"));

    private PlaybackRunner runner()
    {
        return new PlaybackRunner(nullStream, nullStream, null, true);
    }

    // ── handlePlaybackStatus ───────────────────────────────────────────────────

    @Test
    void quitAll_alwaysReturnsMinusOne()
    {
        var common = new CommonOptions();
        assertEquals(-1, runner().handlePlaybackStatus(PlaybackStatus.QUIT_ALL, 1, three, common));
    }

    @Test
    void previous_atFirstTrack_wrapsToLast()
    {
        var r = runner();
        r.setExitOnNavBoundary(false);
        assertEquals(2, r.handlePlaybackStatus(PlaybackStatus.PREVIOUS, 0, three, new CommonOptions()));
    }

    @Test
    void previous_atFirstTrack_withBoundaryExit_returnsMinusOne()
    {
        var r = runner();
        r.setExitOnNavBoundary(true);
        assertEquals(-1, r.handlePlaybackStatus(PlaybackStatus.PREVIOUS, 0, three, new CommonOptions()));
    }

    @Test
    void previous_atMiddleTrack_goesBack()
    {
        assertEquals(1, runner().handlePlaybackStatus(PlaybackStatus.PREVIOUS, 2, three, new CommonOptions()));
    }

    @Test
    void next_atLastTrack_wrapsToFirst()
    {
        var r = runner();
        r.setExitOnNavBoundary(false);
        assertEquals(0, r.handlePlaybackStatus(PlaybackStatus.NEXT, 2, three, new CommonOptions()));
    }

    @Test
    void next_atLastTrack_withBoundaryExit_returnsOutOfBounds()
    {
        var r = runner();
        r.setExitOnNavBoundary(true);
        // Returns playlist.size() (3), which exits the while loop condition
        assertEquals(3, r.handlePlaybackStatus(PlaybackStatus.NEXT, 2, three, new CommonOptions()));
    }

    @Test
    void next_atMiddleTrack_advances()
    {
        assertEquals(2, runner().handlePlaybackStatus(PlaybackStatus.NEXT, 1, three, new CommonOptions()));
    }

    @Test
    void finished_atMiddleTrack_advances()
    {
        assertEquals(2, runner().handlePlaybackStatus(PlaybackStatus.FINISHED, 1, three, new CommonOptions()));
    }

    @Test
    void finished_atLastTrack_withLoopFalse_returnsOutOfBounds()
    {
        var common = new CommonOptions();
        common.loop = false;
        assertEquals(3, runner().handlePlaybackStatus(PlaybackStatus.FINISHED, 2, three, common));
    }

    @Test
    void finished_atLastTrack_withLoopTrue_wrapsToFirst()
    {
        var common = new CommonOptions();
        common.loop = true;
        assertEquals(0, runner().handlePlaybackStatus(PlaybackStatus.FINISHED, 2, three, common));
    }

    // ── buildUI ────────────────────────────────────────────────────────────────

    @Test
    void buildUI_classicMode_returnsDumbUI_noAltScreen()
    {
        var opts = new UiModeOptions();
        opts.classicMode = true;
        boolean[] alt = {false};
        assertInstanceOf(DumbUI.class, runner().buildUI(opts, true, 24, alt));
        assertFalse(alt[0]);
    }

    @Test
    void buildUI_miniMode_returnsLineUI_noAltScreen()
    {
        var opts = new UiModeOptions();
        opts.miniMode = true;
        boolean[] alt = {false};
        assertInstanceOf(LineUI.class, runner().buildUI(opts, true, 24, alt));
        assertFalse(alt[0]);
    }

    @Test
    void buildUI_fullMode_returnsDashboardUI_withAltScreen()
    {
        var opts = new UiModeOptions();
        opts.fullMode = true;
        boolean[] alt = {false};
        assertInstanceOf(DashboardUI.class, runner().buildUI(opts, true, 24, alt));
        assertTrue(alt[0]);
    }

    @Test
    void buildUI_nonInteractive_returnsDumbUI_noAltScreen()
    {
        boolean[] alt = {false};
        assertInstanceOf(DumbUI.class, runner().buildUI(new UiModeOptions(), false, 24, alt));
        assertFalse(alt[0]);
    }

    @Test
    void buildUI_interactive_shortTerminal_returnsLineUI_noAltScreen()
    {
        boolean[] alt = {false};
        assertInstanceOf(LineUI.class, runner().buildUI(new UiModeOptions(), true, 9, alt));
        assertFalse(alt[0]);
    }

    @Test
    void buildUI_interactive_tallTerminal_returnsDashboardUI_withAltScreen()
    {
        boolean[] alt = {false};
        assertInstanceOf(DashboardUI.class, runner().buildUI(new UiModeOptions(), true, 10, alt));
        assertTrue(alt[0]);
    }
}
