/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.io.TerminalIO.TerminalKey;

class JLineTerminalIOTest
{
    /**
     * Regression test: Ctrl+C must be bound to QUIT in the key map.
     *
     * Without this binding (combined with ISIG being disabled in init()), pressing Ctrl+C
     * generates SIGINT which causes System.exit() while the render loop is still running.
     * The running render loop overwrites terminal-restore escape sequences written by the
     * shutdown hook, leaving the cursor invisible and the alt screen active after exit.
     *
     * With ISIG disabled + this binding, Ctrl+C is delivered as the character \x03 (ETX)
     * and routed through the normal QUIT key path, which stops the render loop cleanly
     * before the terminal is restored.
     *
     * If this test fails, Ctrl+C will leave the terminal in a broken state.
     */
    @Test
    void ctrlC_isBoundToQuit() throws IOException
    {
        // Use a dumb terminal so this test runs without a real TTY (including in CI).
        var dumb = TerminalBuilder.builder().dumb(true).build();
        try
        {
            var km = JLineTerminalIO.buildKeyMap(dumb);
            assertEquals(TerminalKey.QUIT, km.getBound("\003"),
                    "Ctrl+C (\\x03/ETX) must be bound to QUIT. "
                            + "Removing this binding breaks terminal restore on Ctrl+C.");
        }
        finally
        {
            dumb.close();
        }
    }

    @Test
    void testDefaultDimensionsWhenUninitialized()
    {
        JLineTerminalIO io = new JLineTerminalIO();
        assertEquals(80, io.getWidth(), "Uninitialized width should default to 80");
        assertEquals(24, io.getHeight(), "Uninitialized height should default to 24");
    }

    @Test
    void testDimensionsAfterInit() throws IOException
    {
        // Skip terminal initialization if running in a headless CI environment
        if (System.getenv("CI") != null)
            return;

        JLineTerminalIO io = new JLineTerminalIO();
        try
        {
            io.init();
            // A real terminal will likely return sizes > 0
            // Even if a dumb terminal returns 0, JLine handles terminal size querying
            // The exact size depends on the host executing the test, so we just test it doesn't
            // crash
            io.getWidth();
            io.getHeight();
        }
        finally
        {
            io.close();
        }
    }
}
