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

import com.fupfin.midiraja.io.AltScreenScope;
import com.fupfin.midiraja.io.TerminalModeManager;
import com.fupfin.midiraja.ui.ScreenBuffer;
import com.fupfin.midiraja.ui.Theme;

final class FullScreenMode
{
    private FullScreenMode()
    {
    }

    /** Full-screen alt-buffer menu with title box and optional logo. */
    @Nullable
    @SuppressWarnings("EmptyCatch")
    static <T> T select(List<TerminalSelector.Item<T>> items,
            TerminalSelector.FullScreenConfig config) throws Exception
    {
        int selectedIdx = TerminalSelector.firstSelectable(items);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build();
                var alt = AltScreenScope.enter(terminal.writer()))
        {
            TerminalModeManager.enterRawNoIsig(terminal);
            var km = TerminalSelector.buildNavKeyMap(terminal);
            var bindingReader = new BindingReader(terminal.reader());

            while (true)
            {
                int width = terminal.getWidth();
                int height = terminal.getHeight();
                int boxWidth = Math.max(config.minBoxWidth(),
                        Math.min(config.maxBoxWidth(), width - 4));
                var logoRenderer = config.logoRenderer();
                int logoLines = (logoRenderer != null && width >= config.logoMinWidth())
                        ? config.logoLineCount()
                        : 0;
                int boxHeight = items.size() + 4 + logoLines;
                int padLeft = Math.max(0, (width - boxWidth) / 2);
                int padTop = Math.max(0, (height - boxHeight) / 2);

                var buf = new ScreenBuffer(8192);
                buf.append(Theme.TERM_CURSOR_HOME).append(Theme.TERM_CLEAR_TO_END);
                buf.repeat("\n", padTop);

                if (logoRenderer != null && width >= config.logoMinWidth())
                    logoRenderer.accept(buf, width);

                String title = config.title();
                int titlePad = (boxWidth - title.length() - 2) / 2;
                buf.repeat(" ", padLeft).append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.DECORATOR_LINE, titlePad).append(Theme.COLOR_RESET)
                        .append(Theme.FORMAT_INVERT).append(title).append(Theme.COLOR_RESET)
                        .append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.DECORATOR_LINE, boxWidth - titlePad - title.length())
                        .append(Theme.COLOR_RESET).appendLine();

                for (int i = 0; i < items.size(); i++)
                {
                    var item = items.get(i);
                    buf.repeat(" ", padLeft);
                    if (item.isSeparator())
                    {
                        buf.append("  ").append(Theme.COLOR_HIGHLIGHT).append(item.label())
                                .append(Theme.COLOR_RESET).appendLine();
                    }
                    else
                    {
                        String label = item.label();
                        String desc = item.description();
                        int maxDesc = boxWidth - label.length() - 8;
                        if (maxDesc > 0 && desc.length() > maxDesc)
                            desc = desc.substring(0, maxDesc - 1) + "…";
                        if (i == selectedIdx)
                        {
                            buf.append("    ").append(Theme.COLOR_HIGHLIGHT)
                                    .append(Theme.CHAR_ARROW_RIGHT).append(" ").append(label)
                                    .append("  ").append(desc).append(Theme.COLOR_RESET)
                                    .appendLine();
                        }
                        else
                        {
                            buf.append("      ").append(label).append("  ").append(desc)
                                    .appendLine();
                        }
                    }
                }

                buf.repeat(" ", padLeft).append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.BORDER_HORIZONTAL, boxWidth).append(Theme.COLOR_RESET)
                        .appendLine();
                String footer = "[▲/▼] Move   [Enter] Select   [Q] Quit";
                int footerPad = (boxWidth - footer.length()) / 2;
                buf.repeat(" ", padLeft + footerPad).append(Theme.COLOR_HIGHLIGHT).append(footer)
                        .append(Theme.COLOR_RESET).appendLine();

                terminal.writer().print(buf.toString());
                terminal.writer().flush();

                if (terminal.reader().peek(50) == NonBlockingReader.READ_EXPIRED)
                    continue;
                String action = bindingReader.readBinding(km, null, false);
                if (action == null)
                    continue;

                switch (action)
                {
                    case "QUIT" ->
                    {
                        alt.exit();
                        return null;
                    }
                    case "SELECT" ->
                    {
                        alt.exit();
                        return items.get(selectedIdx).requireValue();
                    }
                    case "UP" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }

    /** Full-screen alt-buffer menu with D action binding (in-TUI confirmation). */
    @SuppressWarnings("EmptyCatch")
    static <T> TerminalSelector.SelectResult<T> selectWithActions(List<TerminalSelector.Item<T>> items,
            TerminalSelector.FullScreenConfig config) throws Exception
    {
        int selectedIdx = config.initialIndex() > 0 ? config.initialIndex() : TerminalSelector.firstSelectable(items);
        boolean confirmingDelete = false;

        try (Terminal terminal = TerminalBuilder.builder().system(true).build();
                var alt = AltScreenScope.enter(terminal.writer()))
        {
            TerminalModeManager.enterRawNoIsig(terminal);
            var km = TerminalSelector.buildNavKeyMapWithActions(terminal);
            var bindingReader = new BindingReader(terminal.reader());

            while (true)
            {
                int width = terminal.getWidth();
                int height = terminal.getHeight();
                int boxWidth = Math.max(config.minBoxWidth(),
                        Math.min(config.maxBoxWidth(), width - 4));
                var logoRenderer = config.logoRenderer();
                int logoLines = (logoRenderer != null && width >= config.logoMinWidth())
                        ? config.logoLineCount()
                        : 0;
                int boxHeight = items.size() + 4 + logoLines;
                int padLeft = Math.max(0, (width - boxWidth) / 2);
                int padTop = Math.max(0, (height - boxHeight) / 2);

                var buf = new ScreenBuffer(8192);
                buf.append(Theme.TERM_CURSOR_HOME).append(Theme.TERM_CLEAR_TO_END);
                buf.repeat("\n", padTop);

                if (logoRenderer != null && width >= config.logoMinWidth())
                    logoRenderer.accept(buf, width);

                String title = config.title();
                int titlePad = (boxWidth - title.length() - 2) / 2;
                buf.repeat(" ", padLeft).append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.DECORATOR_LINE, titlePad).append(Theme.COLOR_RESET)
                        .append(Theme.FORMAT_INVERT).append(title).append(Theme.COLOR_RESET)
                        .append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.DECORATOR_LINE, boxWidth - titlePad - title.length())
                        .append(Theme.COLOR_RESET).appendLine();

                for (int i = 0; i < items.size(); i++)
                {
                    var item = items.get(i);
                    buf.repeat(" ", padLeft);
                    if (item.isSeparator())
                    {
                        buf.append("  ").append(Theme.COLOR_HIGHLIGHT).append(item.label())
                                .append(Theme.COLOR_RESET).appendLine();
                    }
                    else
                    {
                        String label = item.label();
                        String desc = item.description();
                        int maxDesc = boxWidth - label.length() - 8;
                        if (maxDesc > 0 && desc.length() > maxDesc)
                            desc = desc.substring(0, maxDesc - 1) + "…";
                        if (i == selectedIdx)
                        {
                            buf.append("    ").append(Theme.COLOR_HIGHLIGHT)
                                    .append(Theme.CHAR_ARROW_RIGHT).append(" ").append(label)
                                    .append("  ").append(desc).append(Theme.COLOR_RESET)
                                    .appendLine();
                        }
                        else
                        {
                            buf.append("      ").append(label).append("  ").append(desc)
                                    .appendLine();
                        }
                    }
                }

                buf.repeat(" ", padLeft).append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.BORDER_HORIZONTAL, boxWidth).append(Theme.COLOR_RESET)
                        .appendLine();
                String footer = confirmingDelete
                        ? "Delete this entry? [Y] Confirm   [N] Cancel"
                        : "[▲/▼] Move   [Enter] Select   [D] Delete   [Q] Quit";
                int footerPad = (boxWidth - footer.length()) / 2;
                buf.repeat(" ", padLeft + footerPad).append(Theme.COLOR_HIGHLIGHT).append(footer)
                        .append(Theme.COLOR_RESET).appendLine();

                terminal.writer().print(buf.toString());
                terminal.writer().flush();

                if (terminal.reader().peek(50) == NonBlockingReader.READ_EXPIRED)
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
                            alt.exit();
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
                        alt.exit();
                        return new TerminalSelector.SelectResult.Cancelled<>();
                    }
                    case "SELECT" ->
                    {
                        alt.exit();
                        return new TerminalSelector.SelectResult.Chosen<>(items.get(selectedIdx).requireValue());
                    }
                    case "DELETE" -> confirmingDelete = true;
                    case "UP" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = TerminalSelector.nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }
}
