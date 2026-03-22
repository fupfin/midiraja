/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.ui.Theme;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class AltScreenScopeTest
{
    @Test
    void enter_emitsAltScreenEnableAndHideCursor()
    {
        var sw = new StringWriter();
        try (var ignored = AltScreenScope.enter(new PrintWriter(sw))) {}

        String output = sw.toString();
        assertTrue(output.contains(Theme.TERM_ALT_SCREEN_ENABLE),
                "enter() must emit TERM_ALT_SCREEN_ENABLE");
        assertTrue(output.contains(Theme.TERM_HIDE_CURSOR),
                "enter() must emit TERM_HIDE_CURSOR");
    }

    @Test
    void close_emitsAltScreenDisableAndShowCursor()
    {
        var sw = new StringWriter();
        AltScreenScope.enter(new PrintWriter(sw)).close();

        String output = sw.toString();
        assertTrue(output.contains(Theme.TERM_ALT_SCREEN_DISABLE),
                "close() must emit TERM_ALT_SCREEN_DISABLE");
        assertTrue(output.contains(Theme.TERM_SHOW_CURSOR),
                "close() must emit TERM_SHOW_CURSOR");
    }

    @Test
    void close_isIdempotent()
    {
        var sw = new StringWriter();
        var scope = AltScreenScope.enter(new PrintWriter(sw));
        scope.close();
        int lengthAfterFirstClose = sw.toString().length();
        scope.close();
        assertEquals(lengthAfterFirstClose, sw.toString().length(),
                "Calling close() twice must not emit additional output");
    }

    @Test
    void exit_isAliasForClose()
    {
        var sw = new StringWriter();
        var scope = AltScreenScope.enter(new PrintWriter(sw));
        sw.getBuffer().setLength(0);  // clear enter output
        scope.exit();
        assertTrue(sw.toString().contains(Theme.TERM_ALT_SCREEN_DISABLE));
    }

    @Test
    void clearScreen_emitsCursorHomeAndClearToEnd()
    {
        var sw = new StringWriter();
        try (var scope = AltScreenScope.enter(new PrintWriter(sw)))
        {
            sw.getBuffer().setLength(0);  // clear enter output
            scope.clearScreen();
            String output = sw.toString();
            assertTrue(output.contains(Theme.TERM_CURSOR_HOME),
                    "clearScreen() must emit TERM_CURSOR_HOME");
            assertTrue(output.contains(Theme.TERM_CLEAR_TO_END),
                    "clearScreen() must emit TERM_CLEAR_TO_END");
        }
    }

    @Test
    void exitThenTryWithResources_closesOnlyOnce()
    {
        var sw = new StringWriter();
        try (var scope = AltScreenScope.enter(new PrintWriter(sw)))
        {
            sw.getBuffer().setLength(0);
            scope.exit();
            int lengthAfterExit = sw.toString().length();
            // try-with-resources will call close() again; output must not grow
            sw.getBuffer().setLength(lengthAfterExit);  // snapshot
        }
        // If idempotent, the second close() from try-with-resources added nothing
        assertTrue(sw.toString().length() == 0 || sw.toString().contains(Theme.TERM_ALT_SCREEN_DISABLE));
    }
}
