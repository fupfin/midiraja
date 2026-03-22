/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

/**
 * Applies raw mode with ISIG disabled to a terminal and provides a handle to restore the original
 * attributes.
 *
 * <p>ISIG is disabled so that Ctrl+C is delivered as the character {@code \x03} (ETX) rather than
 * generating SIGINT. See {@link JLineTerminalIO#init()} and {@code CLAUDE.md} for the full
 * rationale.
 *
 * <p>Usage:
 * <pre>{@code
 * var saved = TerminalModeManager.enterRawNoIsig(terminal);
 * try {
 *     // interact with terminal
 * } finally {
 *     saved.close();
 * }
 * }</pre>
 */
public final class TerminalModeManager
{
    private TerminalModeManager()
    {}

    /**
     * Saves the current terminal attributes, then puts the terminal into raw mode with ISIG and
     * ECHO disabled.
     *
     * @return a {@link Restorer} that restores the saved attributes when closed
     */
    public static Restorer enterRawNoIsig(Terminal terminal)
    {
        Attributes saved = terminal.getAttributes();
        terminal.enterRawMode();
        Attributes attr = terminal.getAttributes();
        attr.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attr.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attr);
        return () -> terminal.setAttributes(saved);
    }

    /**
     * Restores the terminal to the state it was in before {@link #enterRawNoIsig}. Implements
     * {@link AutoCloseable} for use in try-with-resources blocks.
     */
    @FunctionalInterface
    public interface Restorer extends AutoCloseable
    {
        /** Restores the terminal attributes. Does not throw checked exceptions. */
        @Override
        void close();
    }
}
