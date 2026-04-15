/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.ui.DashboardUI;
import com.fupfin.midiraja.ui.DumbUI;
import com.fupfin.midiraja.ui.LineUI;

/**
 * Unit tests for UI mode selection ({@code buildUI}) in {@link PlaybackRunner}.
 */
class PlaybackRunnerNavigationTest
{
    private final PrintStream nullStream = new PrintStream(PrintStream.nullOutputStream());

    private PlaybackRunner runner()
    {
        return new PlaybackRunner(nullStream, nullStream, null, true, new FxOptions());
    }

    // ── buildUI ────────────────────────────────────────────────────────────────

    @Test
    void buildUI_classicMode_returnsDumbUI_noAltScreen()
    {
        var opts = new UiModeOptions();
        opts.classicMode = true;
        var result = runner().buildUI(false, opts, true, 24);
        assertInstanceOf(DumbUI.class, result.ui());
        assertFalse(result.useAltScreen());
    }

    @Test
    void buildUI_miniMode_returnsLineUI_noAltScreen()
    {
        var opts = new UiModeOptions();
        opts.miniMode = true;
        var result = runner().buildUI(false, opts, true, 24);
        assertInstanceOf(LineUI.class, result.ui());
        assertFalse(result.useAltScreen());
    }

    @Test
    void buildUI_fullMode_returnsDashboardUI_withAltScreen()
    {
        var opts = new UiModeOptions();
        opts.fullMode = true;
        var result = runner().buildUI(false, opts, true, 24);
        assertInstanceOf(DashboardUI.class, result.ui());
        assertTrue(result.useAltScreen());
    }

    @Test
    void buildUI_nonInteractive_returnsDumbUI_noAltScreen()
    {
        var result = runner().buildUI(false, new UiModeOptions(), false, 24);
        assertInstanceOf(DumbUI.class, result.ui());
        assertFalse(result.useAltScreen());
    }

    @Test
    void buildUI_interactive_shortTerminal_returnsLineUI_noAltScreen()
    {
        var result = runner().buildUI(false, new UiModeOptions(), true, 9);
        assertInstanceOf(LineUI.class, result.ui());
        assertFalse(result.useAltScreen());
    }

    @Test
    void buildUI_interactive_tallTerminal_returnsDashboardUI_withAltScreen()
    {
        var result = runner().buildUI(false, new UiModeOptions(), true, 10);
        assertInstanceOf(DashboardUI.class, result.ui());
        assertTrue(result.useAltScreen());
    }
}
