/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import static java.lang.IO.*;

import java.io.IOException;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.*;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import org.jspecify.annotations.Nullable;

public class JLineTerminalIO implements TerminalIO
{
    @Nullable
    private Terminal terminal;
    @Nullable
    private BindingReader bindingReader;
    @Nullable
    private KeyMap<TerminalKey> keyMap;

    @Override
    public boolean isInteractive()
    {
        if (terminal == null) return false;
        String type = terminal.getType();
        return !Terminal.TYPE_DUMB.equals(type) && !Terminal.TYPE_DUMB_COLOR.equals(type);
    }

    @Override
    public void init() throws IOException
    {
        terminal = TerminalBuilder.builder().system(true).build();
        terminal.enterRawMode();
        Attributes attr = terminal.getAttributes();
        attr.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        terminal.setAttributes(attr);

        keyMap = buildKeyMap(terminal);
        bindingReader = new BindingReader(terminal.reader());
    }

    private static KeyMap<TerminalKey> buildKeyMap(Terminal terminal)
    {
        var km = new KeyMap<TerminalKey>();
        // Wait up to 100ms to disambiguate ESC-alone from ESC-sequence (e.g. arrow keys).
        // Windows scheduler granularity is ~15ms, so 50ms is too tight.
        km.setAmbiguousTimeout(100);

        // Arrow keys — use terminal capability strings so JLine maps the right sequences
        // for the current platform, then also bind ANSI (CSI) and SS3 variants explicitly
        // as a fallback in case the terminal reports no capabilities.
        bindArrow(km, terminal, InfoCmp.Capability.key_up,    TerminalKey.PREV_TRACK,    "A");
        bindArrow(km, terminal, InfoCmp.Capability.key_down,  TerminalKey.NEXT_TRACK,    "B");
        bindArrow(km, terminal, InfoCmp.Capability.key_right, TerminalKey.SEEK_FORWARD,  "C");
        bindArrow(km, terminal, InfoCmp.Capability.key_left,  TerminalKey.SEEK_BACKWARD, "D");

        // ESC alone → quit
        km.bind(TerminalKey.QUIT, KeyMap.esc());

        // Single-character bindings
        km.bind(TerminalKey.PAUSE,          " ");
        km.bind(TerminalKey.QUIT,           "q", "Q");
        km.bind(TerminalKey.NEXT_TRACK,     "n", "N");
        km.bind(TerminalKey.PREV_TRACK,     "p", "P");
        km.bind(TerminalKey.VOLUME_UP,      "+", "=", "u", "U");
        km.bind(TerminalKey.VOLUME_DOWN,    "-", "_", "d", "D");
        km.bind(TerminalKey.SEEK_FORWARD,   "f", "F");
        km.bind(TerminalKey.SEEK_BACKWARD,  "b", "B");
        km.bind(TerminalKey.TRANSPOSE_UP,   "'");
        km.bind(TerminalKey.TRANSPOSE_DOWN, "/");
        km.bind(TerminalKey.SPEED_UP,       ".", ">");
        km.bind(TerminalKey.SPEED_DOWN,     ",", "<");
        km.bind(TerminalKey.BOOKMARK,       "*");

        return km;
    }

    /** Bind an arrow key using the terminal capability and both CSI/SS3 fallback sequences. */
    private static void bindArrow(KeyMap<TerminalKey> km, Terminal terminal,
            InfoCmp.Capability cap, TerminalKey action, String letter)
    {
        String capSeq = KeyMap.key(terminal, cap);
        if (capSeq != null && !capSeq.isEmpty())
            km.bind(action, capSeq);
        // Always also bind the explicit ANSI (CSI) and SS3 forms as fallbacks
        km.bind(action, "\033[" + letter, "\033O" + letter);
    }

    @Override
    public TerminalKey readKey() throws IOException
    {
        if (bindingReader == null || keyMap == null)
            return TerminalKey.NONE;

        // Peek with a short timeout — return NONE immediately if no input is available.
        var t = terminal;
        if (t == null || t.reader().peek(10) == NonBlockingReader.READ_EXPIRED)
            return TerminalKey.NONE;

        TerminalKey key = bindingReader.readBinding(keyMap, null, false);
        return key != null ? key : TerminalKey.NONE;
    }

    @Override
    public void close() throws IOException
    {
        if (terminal != null)
        {
            try
            {
                terminal.close();
            }
            catch (IOException _)
            {
                // Ignore errors during terminal cleanup to prevent masking main exceptions
            }
        }
    }

    @Override
    public void print(String str)
    {
        if (terminal != null)
        {
            terminal.writer().print(str);
            terminal.writer().flush();
        }
        else
        {
            print(str);
        }
    }

    @Override
    public void println(String str)
    {
        if (terminal != null)
        {
            terminal.writer().println(str);
            terminal.writer().flush();
        }
        else
        {
            println(str);
        }
    }

    @Override
    public int getWidth()
    {
        return terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 80;
    }

    @Override
    public int getHeight()
    {
        return terminal != null && terminal.getHeight() > 0 ? terminal.getHeight() : 24;
    }
}
