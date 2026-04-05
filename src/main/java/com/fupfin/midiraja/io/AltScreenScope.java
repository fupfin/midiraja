/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import java.io.PrintWriter;

import com.fupfin.midiraja.ui.Theme;

/**
 * Manages the alt-screen and cursor-visibility lifecycle for a full-screen TUI.
 *
 * <p>
 * Sends {@code TERM_ALT_SCREEN_ENABLE + TERM_HIDE_CURSOR} on {@link #enter} and
 * {@code TERM_ALT_SCREEN_DISABLE + TERM_SHOW_CURSOR} on {@link #close}. Designed for
 * try-with-resources use.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * try (var alt = AltScreenScope.enter(terminal.writer()))
 * {
 *     // render full-screen UI
 *     if (quit)
 *     {
 *         alt.exit();
 *         return;
 *     }
 * } // alt screen is exited here even on exception
 * }</pre>
 */
public final class AltScreenScope implements AutoCloseable
{
    private final PrintWriter writer;
    private boolean closed = false;

    private AltScreenScope(PrintWriter writer)
    {
        this.writer = writer;
    }

    /** Enters the alt screen and hides the cursor. */
    public static AltScreenScope enter(PrintWriter writer)
    {
        writer.print(Theme.TERM_ALT_SCREEN_ENABLE + Theme.TERM_HIDE_CURSOR);
        writer.flush();
        return new AltScreenScope(writer);
    }

    /**
     * Clears the alt-screen content without exiting the alt buffer. Used when transitioning
     * between items while staying in the alt screen.
     */
    public void clearScreen()
    {
        writer.print(Theme.TERM_CURSOR_HOME + Theme.TERM_CLEAR_TO_END);
        writer.flush();
    }

    /**
     * Exits the alt screen and shows the cursor immediately. Idempotent — safe to call before
     * the try-with-resources close.
     */
    public void exit()
    {
        close();
    }

    /** Exits the alt screen and shows the cursor. Idempotent. */
    @Override
    public void close()
    {
        if (!closed)
        {
            closed = true;
            writer.print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
            writer.flush();
        }
    }
}
