/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import java.io.IOException;

/**
 * Dependency Inversion Principle (DIP) abstraction for terminal interaction. Allows decoupling the
 * playback engine from standard system streams and JLine, facilitating easy unit testing with Mock
 * implementations.
 */
public interface TerminalIO
{
    /**
     * Context propagation for the active terminal session, preventing the need to pass TerminalIO
     * instances down the call stack or across virtual threads.
     */
    ScopedValue<TerminalIO> CONTEXT = ScopedValue.newInstance();

    /**
     * Enum representing high-level interactive commands triggered by the user.
     */
    enum TerminalKey
    {
        NONE, SEEK_FORWARD, SEEK_BACKWARD, VOLUME_UP, VOLUME_DOWN, SPEED_UP, SPEED_DOWN, TRANSPOSE_UP, TRANSPOSE_DOWN, NEXT_TRACK, PREV_TRACK, QUIT, PAUSE, BOOKMARK, RESUME_SESSION, TOGGLE_LOOP, TOGGLE_SHUFFLE
    }

    /**
     * Returns true if the terminal supports advanced interactions like cursor movement and raw
     * mode.
     */
    boolean isInteractive();

    /**
     * Initializes the terminal in raw mode for non-blocking key reads.
     */
    void init() throws IOException;

    /**
     * Restores the terminal to its original mode.
     */
    void close() throws IOException;

    /**
     * Reads a single keystroke and maps it to a TerminalKey. Non-blocking if the underlying
     * implementation supports it.
     */
    TerminalKey readKey() throws IOException;

    /**
     * Prints a string to the console.
     */
    void print(String str);

    /**
     * Prints a string to the console with a newline.
     */
    void println(String str);

    /**
     * Returns the current width of the terminal (number of columns). Returns a default (e.g. 80) if
     * not interactive or unknown.
     */
    int getWidth();

    /**
     * Returns the current height of the terminal (number of rows). Returns a default (e.g. 24) if
     * not interactive or unknown.
     */
    int getHeight();

    /**
     * Installs SIGTSTP/SIGCONT handlers. {@code onSuspend} is called before the process suspends
     * (so the UI can restore the terminal); {@code onResume} is called after SIGCONT is received
     * (so the UI can re-enter its display mode). Default no-op for non-interactive implementations.
     */
    default void installSuspendHandlers(Runnable onSuspend, Runnable onResume)
    {
    }
}
