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
 * Applies raw mode with ISIG disabled to a terminal.
 *
 * <p>
 * ISIG is disabled so that Ctrl+C is delivered as the character {@code \x03} (ETX) rather than
 * generating SIGINT. See {@link JLineTerminalIO#init()} and {@code CLAUDE.md} for the full
 * rationale.
 *
 * <p>
 * Attribute restoration is handled by the caller's {@code terminal.close()} — JLine saves the
 * original tty state at open time and restores it on close.
 */
public final class TerminalModeManager
{
    private TerminalModeManager()
    {
    }

    /**
     * Puts the terminal into raw mode with ISIG and ECHO disabled.
     *
     * <p>
     * The caller is responsible for restoring the terminal via {@code terminal.close()}, which
     * JLine implements by restoring the tty attributes saved at open time.
     */
    public static void enterRawNoIsig(Terminal terminal)
    {
        terminal.enterRawMode();
        Attributes attr = terminal.getAttributes();
        attr.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attr.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attr);
    }
}
