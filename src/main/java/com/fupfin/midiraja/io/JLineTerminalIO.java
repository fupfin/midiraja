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
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
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
        if (terminal == null)
            return false;
        String type = terminal.getType();
        return !Terminal.TYPE_DUMB.equals(type) && !Terminal.TYPE_DUMB_COLOR.equals(type);
    }

    @Override
    public void init() throws IOException
    {
        terminal = TerminalBuilder.builder().system(true).build();
        // enterRawNoIsig disables ISIG so Ctrl+C is delivered as \x03 (ETX) rather than
        // generating SIGINT. This lets JLine route it through the normal QUIT key path,
        // which cleanly stops the render loop before restoring the terminal. Without this,
        // SIGINT causes System.exit() while the render loop is still running, and the loop
        // overwrites the terminal-restore sequences written by the shutdown hook.
        TerminalModeManager.enterRawNoIsig(terminal);

        keyMap = buildKeyMap(terminal);
        bindingReader = new BindingReader(terminal.reader());
    }

    static KeyMap<TerminalKey> buildKeyMap(Terminal terminal)
    {
        // Start with the shared UP/DOWN/ESC/Ctrl+C/q bindings from NavKeyMapFactory,
        // then add the playback-specific bindings on top.
        var km = NavKeyMapFactory.buildNavKeyMap(terminal,
                TerminalKey.PREV_TRACK, TerminalKey.NEXT_TRACK,
                TerminalKey.NONE, TerminalKey.QUIT);

        // Left/right arrows — not part of the minimal nav set
        NavKeyMapFactory.bindArrow(km, terminal, InfoCmp.Capability.key_right,
                TerminalKey.SEEK_FORWARD, "C");
        NavKeyMapFactory.bindArrow(km, terminal, InfoCmp.Capability.key_left,
                TerminalKey.SEEK_BACKWARD, "D");

        // Playback-specific single-character bindings
        km.bind(TerminalKey.PAUSE, " ");
        km.bind(TerminalKey.NEXT_TRACK, "n", "N");
        km.bind(TerminalKey.PREV_TRACK, "p", "P");
        km.bind(TerminalKey.VOLUME_UP, "+", "=", "u", "U");
        km.bind(TerminalKey.VOLUME_DOWN, "-", "_", "d", "D");
        km.bind(TerminalKey.SEEK_FORWARD, "f", "F");
        km.bind(TerminalKey.SEEK_BACKWARD, "b", "B");
        km.bind(TerminalKey.TRANSPOSE_UP, "'");
        km.bind(TerminalKey.TRANSPOSE_DOWN, "/");
        km.bind(TerminalKey.SPEED_UP, ".", ">");
        km.bind(TerminalKey.SPEED_DOWN, ",", "<");
        km.bind(TerminalKey.BOOKMARK, "*");
        km.bind(TerminalKey.RESUME_SESSION, "r", "R");
        km.bind(TerminalKey.TOGGLE_LOOP, "l", "L");
        km.bind(TerminalKey.TOGGLE_SHUFFLE, "s", "S");
        km.bind(TerminalKey.TOGGLE_METER_MODE, "v", "V");

        return km;
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
            // Restore cursor visibility after JLine tears down the terminal.
            // terminal.close() may restore saved terminal attributes or send reset sequences
            // that leave the cursor hidden; writing directly to System.out ensures it is shown.
            try
            {
                System.out.print("\033[?25h\033[?7h");
                System.out.flush();
            }
            catch (Exception _)
            {}
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
    }

    @Override
    public void println(String str)
    {
        if (terminal != null)
        {
            terminal.writer().println(str);
            terminal.writer().flush();
        }
    }

    /**
     * Installs JLine SIGTSTP/SIGCONT handlers.
     *
     * <p>
     * On SIGTSTP: calls {@code onSuspend}, resets the TSTP handler to the OS default, then
     * sends SIGTSTP to the current process (which pauses all threads). Execution resumes here
     * after SIGCONT is received; the TSTP handler is then re-registered and {@code onResume} is
     * called.
     */
    @Override
    public void installSuspendHandlers(Runnable onSuspend, Runnable onResume)
    {
        var t = terminal;
        if (t == null)
            return;
        t.handle(org.jline.terminal.Terminal.Signal.TSTP,
                signal -> handleTstp(t, onSuspend, onResume));
    }

    @SuppressWarnings("EmptyCatch")
    private void handleTstp(org.jline.terminal.Terminal t, Runnable onSuspend, Runnable onResume)
    {
        onSuspend.run();
        // Reset to default before re-raising so the OS actually suspends the process.
        t.handle(org.jline.terminal.Terminal.Signal.TSTP,
                org.jline.terminal.Terminal.SignalHandler.SIG_DFL);
        try
        {
            new ProcessBuilder("/bin/kill", "-TSTP",
                    String.valueOf(ProcessHandle.current().pid())).start().waitFor();
        }
        catch (Exception _)
        {}
        // Execution resumes here after SIGCONT.
        installSuspendHandlers(onSuspend, onResume);
        onResume.run();
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
