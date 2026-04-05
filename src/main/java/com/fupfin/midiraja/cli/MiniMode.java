/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.util.List;

import org.jline.keymap.BindingReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;
import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.io.TerminalModeManager;
import com.fupfin.midiraja.ui.Theme;

final class MiniMode
{
    private MiniMode()
    {
    }

    /** Arrow-key menu that redraws in-place (mini / small terminal). */
    @Nullable
    @SuppressWarnings("EmptyCatch")
    static <T> T select(List<TerminalSelector.Item<T>> items,
            TerminalSelector.FullScreenConfig config) throws Exception
    {
        int numLines = items.size() + 1; // +1 for the header line
        int selectedIdx = TerminalSelector.firstSelectable(items);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            TerminalModeManager.enterRawNoIsig(terminal);
            var km = TerminalSelector.buildNavKeyMap(terminal);
            var bindingReader = new BindingReader(terminal.reader());
            terminal.writer().print(Theme.TERM_HIDE_CURSOR);
            boolean firstDraw = true;

            while (true)
            {
                if (!firstDraw)
                    terminal.writer().print("\033[" + numLines + "A");
                firstDraw = false;

                terminal.writer().println(config.title().strip() + ":");
                for (int i = 0; i < items.size(); i++)
                {
                    var item = items.get(i);
                    if (item.isSeparator())
                    {
                        terminal.writer().println(
                                "  " + Theme.COLOR_HIGHLIGHT + item.label() + Theme.COLOR_RESET
                                        + Theme.TERM_CLEAR_TO_EOL);
                    }
                    else
                    {
                        String prefix = (i == selectedIdx) ? " > " : "   ";
                        terminal.writer().println(prefix + item.label() + "  " + item.description()
                                + Theme.TERM_CLEAR_TO_EOL);
                    }
                }
                terminal.writer().flush();

                if (terminal.reader().peek(100) == NonBlockingReader.READ_EXPIRED)
                    continue;
                String action = bindingReader.readBinding(km, null, false);
                if (action == null)
                    continue;

                switch (action)
                {
                    case "QUIT" ->
                    {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return null;
                    }
                    case "SELECT" ->
                    {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return items.get(selectedIdx).requireValue();
                    }
                    case "UP" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }

    /** Arrow-key mini menu with D action binding (in-TUI confirmation). */
    @SuppressWarnings("EmptyCatch")
    static <T> TerminalSelector.SelectResult<T> selectWithActions(List<TerminalSelector.Item<T>> items,
            TerminalSelector.FullScreenConfig config) throws Exception
    {
        int numLines = items.size() + 2; // +1 header, +1 footer/status line
        int selectedIdx = config.initialIndex() > 0 ? config.initialIndex() : TerminalSelector.firstSelectable(items);
        boolean confirmingDelete = false;

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            TerminalModeManager.enterRawNoIsig(terminal);
            var km = TerminalSelector.buildNavKeyMapWithActions(terminal);
            var bindingReader = new BindingReader(terminal.reader());
            terminal.writer().print(Theme.TERM_HIDE_CURSOR);
            boolean firstDraw = true;

            while (true)
            {
                if (!firstDraw)
                    terminal.writer().print("\033[" + numLines + "A");
                firstDraw = false;

                terminal.writer().println(config.title().strip() + ":");
                for (int i = 0; i < items.size(); i++)
                {
                    var item = items.get(i);
                    if (item.isSeparator())
                    {
                        terminal.writer().println(
                                "  " + Theme.COLOR_HIGHLIGHT + item.label() + Theme.COLOR_RESET
                                        + Theme.TERM_CLEAR_TO_EOL);
                    }
                    else
                    {
                        String prefix = (i == selectedIdx) ? " > " : "   ";
                        terminal.writer().println(prefix + item.label() + "  " + item.description()
                                + Theme.TERM_CLEAR_TO_EOL);
                    }
                }
                String statusLine = confirmingDelete
                        ? "Delete this entry? [Y] Confirm  [N] Cancel"
                        : "[Enter] Select  [D] Delete  [Q] Quit";
                terminal.writer().println(Theme.COLOR_HIGHLIGHT + statusLine
                        + Theme.COLOR_RESET + Theme.TERM_CLEAR_TO_EOL);
                terminal.writer().flush();

                if (terminal.reader().peek(100) == NonBlockingReader.READ_EXPIRED)
                    continue;
                String action = bindingReader.readBinding(km, null, false);
                if (action == null)
                    continue;

                if (confirmingDelete)
                {
                    switch (action)
                    {
                        case "CONFIRM" ->
                        {
                            clearLines(terminal, numLines);
                            terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                            terminal.writer().flush();
                            return new TerminalSelector.SelectResult.Delete<>(items.get(selectedIdx).requireValue());
                        }
                        case "ABORT", "QUIT" -> confirmingDelete = false;
                    }
                    continue;
                }

                switch (action)
                {
                    case "QUIT" ->
                    {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return new TerminalSelector.SelectResult.Cancelled<>();
                    }
                    case "SELECT" ->
                    {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return new TerminalSelector.SelectResult.Chosen<>(items.get(selectedIdx).requireValue());
                    }
                    case "DELETE" -> confirmingDelete = true;
                    case "UP" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }

    static void clearLines(Terminal terminal, int count)
    {
        terminal.writer().print("\033[" + count + "A");
        for (int i = 0; i < count; i++)
            terminal.writer().println(Theme.TERM_CLEAR_TO_EOL);
        terminal.writer().print("\033[" + count + "A");
    }
}
