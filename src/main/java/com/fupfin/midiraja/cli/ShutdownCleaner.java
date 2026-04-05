/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static java.lang.Math.max;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.ui.Theme;

/**
 * Runnable for the JVM shutdown hook: restores the terminal and closes the MIDI port.
 * Extracted from {@link PlaybackRunner} for independent testability.
 */
class ShutdownCleaner implements Runnable
{
    private static final Logger log = Logger.getLogger(ShutdownCleaner.class.getName());

    private final boolean isInteractive;
    private final boolean useAltScreen;
    private final TerminalIO activeIO;
    private final MidiOutProvider provider;
    private final AtomicBoolean portClosed;

    ShutdownCleaner(boolean isInteractive, TerminalIO activeIO,
            MidiOutProvider provider, AtomicBoolean portClosed, boolean useAltScreen)
    {
        this.isInteractive = isInteractive;
        this.useAltScreen = useAltScreen;
        this.activeIO = activeIO;
        this.provider = provider;
        this.portClosed = portClosed;
    }

    @Override
    public void run()
    {
        MidirajaCommand.SHUTTING_DOWN = true;
        restoreTerminal();
        closeIO();
        closePort();
    }

    private void restoreTerminal()
    {
        if (!isInteractive)
            return;
        String safeRestore = (useAltScreen ? Theme.TERM_ALT_SCREEN_DISABLE : "")
                + Theme.TERM_MOUSE_DISABLE + Theme.COLOR_RESET + Theme.TERM_AUTOWRAP_ON
                + Theme.TERM_SHOW_CURSOR + "\r\033[K\n";
        try
        {
            System.out.print(safeRestore);
            System.out.flush();
        }
        catch (Exception _)
        {}
        try (var tty = new java.io.FileOutputStream("/dev/tty"))
        {
            tty.write(safeRestore.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tty.flush();
        }
        catch (Exception _)
        {}
    }

    private void closeIO()
    {
        try
        {
            activeIO.close();
        }
        catch (Exception e)
        {
            log.warning("Error closing terminal IO: " + e.getMessage());
        }
    }

    private void closePort()
    {
        if (!portClosed.compareAndSet(false, true))
            return;
        try
        {
            provider.panic();
        }
        catch (Exception e)
        {
            log.warning("Error during shutdown panic: " + e.getMessage());
        }
        long endWait = System.currentTimeMillis() + 200;
        while (System.currentTimeMillis() < endWait)
        {
            try
            {
                Thread.sleep(max(1, endWait - System.currentTimeMillis()));
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try
        {
            provider.closePort();
        }
        catch (Exception e)
        {
            log.warning("Error during shutdown closePort: " + e.getMessage());
        }
    }
}
