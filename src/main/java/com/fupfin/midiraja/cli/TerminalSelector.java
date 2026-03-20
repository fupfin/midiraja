/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.io.JLineTerminalIO;
import com.fupfin.midiraja.ui.ScreenBuffer;
import com.fupfin.midiraja.ui.Theme;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.function.BiConsumer;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import org.jspecify.annotations.Nullable;

/**
 * Generic interactive selection menu supporting full-screen, mini (in-place), and classic
 * (numbered prompt) modes. Encapsulates mode decision, terminal lifecycle, cross-platform keyboard
 * handling, and rendering.
 *
 * <p>
 * Usage: build a list of {@link Item}s, provide a {@link FullScreenConfig}, and call
 * {@link #select}.
 */
public final class TerminalSelector
{
    private TerminalSelector()
    {}

    /** A selectable item or a visual separator. A null {@code value} denotes a separator. */
    public record Item<T>(@Nullable T value, String label, String description)
    {
        public boolean isSeparator()
        {
            return value == null;
        }

        /** Returns the non-null value; throws if called on a separator. */
        public T requireValue()
        {
            return Objects.requireNonNull(value, "separator has no value");
        }

        public static <T> Item<T> of(T value, String label, String description)
        {
            return new Item<>(value, label, description);
        }

        public static <T> Item<T> separator(String label)
        {
            return new Item<>(null, label, "");
        }
    }

    /**
     * Configuration for the full-screen selection UI.
     *
     * @param title        Title text shown in the header bar (e.g. {@code " SELECT ENGINE "})
     * @param minBoxWidth  Minimum box width
     * @param maxBoxWidth  Maximum box width (clamped to terminal width − 4)
     * @param logoMinWidth Minimum terminal width required to show the logo; ≤0 = never show
     * @param logoLineCount Number of lines the logo occupies (used for vertical centering)
     * @param logoRenderer Lambda that appends logo content to the buffer; null = no logo
     */
    public record FullScreenConfig(
            String title,
            int minBoxWidth,
            int maxBoxWidth,
            int logoMinWidth,
            int logoLineCount,
            @Nullable BiConsumer<ScreenBuffer, Integer> logoRenderer,
            int initialIndex)
    {
        /** Convenience constructor for configs without a logo. */
        public FullScreenConfig(String title, int minBoxWidth, int maxBoxWidth)
        {
            this(title, minBoxWidth, maxBoxWidth, 0, 0, null, 0);
        }

        /** Convenience constructor for configs with a logo but no initial index override. */
        public FullScreenConfig(String title, int minBoxWidth, int maxBoxWidth,
                int logoMinWidth, int logoLineCount,
                @Nullable BiConsumer<ScreenBuffer, Integer> logoRenderer)
        {
            this(title, minBoxWidth, maxBoxWidth, logoMinWidth, logoLineCount, logoRenderer, 0);
        }

        /** Returns a copy with the given initial cursor index. */
        public FullScreenConfig withInitialIndex(int idx)
        {
            return new FullScreenConfig(title, minBoxWidth, maxBoxWidth, logoMinWidth, logoLineCount,
                    logoRenderer, idx);
        }
    }

    public enum SelectionMode
    {
        FULL, MINI, CLASSIC
    }

    /**
     * Result of {@link #selectWithActions}: a chosen item, a delete request, a promote request,
     * or a cancellation. Carries the item's value (not the list index).
     */
    public sealed interface SelectResult<T>
            permits SelectResult.Chosen, SelectResult.Delete, SelectResult.Promote,
                    SelectResult.Cancelled
    {
        record Chosen<T>(T value) implements SelectResult<T> {}

        record Delete<T>(T value) implements SelectResult<T> {}

        record Promote<T>(T value) implements SelectResult<T> {}

        record Cancelled<T>() implements SelectResult<T> {}
    }

    /**
     * Single source of truth for mode decision. Pure function — no terminal access.
     */
    public static SelectionMode resolveMode(boolean isInteractive, int termHeight,
            boolean preferFull, boolean preferMini, boolean preferClassic)
    {
        if (!isInteractive || preferClassic) return SelectionMode.CLASSIC;
        if (preferMini) return SelectionMode.MINI;
        return (preferFull || termHeight >= 10) ? SelectionMode.FULL : SelectionMode.MINI;
    }

    /**
     * Shows the selection menu and returns the chosen item value, or {@code null} if the user
     * quit.
     */
    @Nullable
    public static <T> T select(List<Item<T>> items, FullScreenConfig config,
            boolean preferFull, boolean preferMini, boolean preferClassic,
            PrintStream err) throws Exception
    {
        var probe = new JLineTerminalIO();
        probe.init();
        boolean isInteractive = probe.isInteractive();
        int termHeight = probe.getHeight();
        probe.close();

        return switch (resolveMode(isInteractive, termHeight, preferFull, preferMini, preferClassic))
        {
            case FULL -> fullScreenSelect(items, config);
            case MINI -> miniSelect(items, config);
            case CLASSIC -> classicSelect(items, config, err);
        };
    }

    /**
     * Shows the selection menu and returns a {@link SelectResult}. Supports the same display modes
     * as {@link #select}, but additionally allows {@code D} (delete) and {@code B} (promote) key
     * bindings in interactive modes.
     */
    public static <T> SelectResult<T> selectWithActions(List<Item<T>> items, FullScreenConfig config,
            boolean preferFull, boolean preferMini, boolean preferClassic,
            PrintStream err) throws Exception
    {
        if (items.isEmpty()) return new SelectResult.Cancelled<>();

        var probe = new JLineTerminalIO();
        probe.init();
        boolean isInteractive = probe.isInteractive();
        int termHeight = probe.getHeight();
        probe.close();

        return switch (resolveMode(isInteractive, termHeight, preferFull, preferMini, preferClassic))
        {
            case FULL -> fullScreenSelectWithActions(items, config);
            case MINI -> miniSelectWithActions(items, config);
            case CLASSIC -> classicSelectWithActions(items, config, err);
        };
    }

    /** Advance {@code from} by {@code direction} (+1 or −1), skipping separator items. */
    static int nextSelectable(List<? extends Item<?>> items, int from, int direction)
    {
        int size = items.size();
        int i = (from + direction + size) % size;
        while (items.get(i).isSeparator())
            i = (i + direction + size) % size;
        return i;
    }

    static int firstSelectable(List<? extends Item<?>> items)
    {
        for (int i = 0; i < items.size(); i++)
            if (!items.get(i).isSeparator()) return i;
        return 0;
    }

    /** Full-screen alt-buffer menu with title box and optional logo. */
    @Nullable
    @SuppressWarnings("EmptyCatch")
    private static <T> T fullScreenSelect(List<Item<T>> items, FullScreenConfig config)
            throws Exception
    {
        int selectedIdx = firstSelectable(items);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            disableIsig(terminal);
            var km = buildNavKeyMap(terminal);
            var bindingReader = new BindingReader(terminal.reader());
            terminal.writer().print(Theme.TERM_ALT_SCREEN_ENABLE + Theme.TERM_HIDE_CURSOR);
            terminal.writer().flush();

            while (true)
            {
                int width = terminal.getWidth();
                int height = terminal.getHeight();
                int boxWidth = Math.max(config.minBoxWidth(),
                        Math.min(config.maxBoxWidth(), width - 4));
                var logoRenderer = config.logoRenderer();
                int logoLines =
                        (logoRenderer != null && width >= config.logoMinWidth())
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

                if (terminal.reader().peek(50) == NonBlockingReader.READ_EXPIRED) continue;
                String action = bindingReader.readBinding(km, null, false);
                if (action == null) continue;

                switch (action)
                {
                    case "QUIT" -> {
                        terminal.writer()
                                .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return null;
                    }
                    case "SELECT" -> {
                        terminal.writer()
                                .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return items.get(selectedIdx).requireValue();
                    }
                    case "UP" -> selectedIdx = nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }

    /** Arrow-key menu that redraws in-place (mini / small terminal). */
    @Nullable
    @SuppressWarnings("EmptyCatch")
    private static <T> T miniSelect(List<Item<T>> items, FullScreenConfig config) throws Exception
    {
        int numLines = items.size() + 1; // +1 for the header line
        int selectedIdx = firstSelectable(items);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            disableIsig(terminal);
            var km = buildNavKeyMap(terminal);
            var bindingReader = new BindingReader(terminal.reader());
            terminal.writer().print(Theme.TERM_HIDE_CURSOR);
            boolean firstDraw = true;

            while (true)
            {
                if (!firstDraw) terminal.writer().print("\033[" + numLines + "A");
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

                if (terminal.reader().peek(100) == NonBlockingReader.READ_EXPIRED) continue;
                String action = bindingReader.readBinding(km, null, false);
                if (action == null) continue;

                switch (action)
                {
                    case "QUIT" -> {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return null;
                    }
                    case "SELECT" -> {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return items.get(selectedIdx).requireValue();
                    }
                    case "UP" -> selectedIdx = nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }

    /** Non-interactive fallback: numbered prompt on stderr. */
    @Nullable
    private static <T> T classicSelect(List<Item<T>> items, FullScreenConfig config,
            PrintStream err)
    {
        err.println(config.title().strip() + ":");
        int n = 0;
        for (var item : items)
        {
            if (item.isSeparator())
                err.println("  " + item.label());
            else
                err.println("  [" + (++n) + "] " + item.label() + " — " + item.description());
        }
        err.print("Enter number: ");
        err.flush();
        var scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        if (!scanner.hasNextInt()) return null;
        int sel = scanner.nextInt();
        int idx = 0;
        for (var item : items)
        {
            if (item.isSeparator()) continue;
            if (++idx == sel) return item.value();
        }
        return null;
    }

    /** Full-screen alt-buffer menu with D action binding (in-TUI confirmation). */
    @SuppressWarnings("EmptyCatch")
    private static <T> SelectResult<T> fullScreenSelectWithActions(List<Item<T>> items,
            FullScreenConfig config) throws Exception
    {
        int selectedIdx = config.initialIndex() > 0 ? config.initialIndex() : firstSelectable(items);
        boolean confirmingDelete = false;

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            disableIsig(terminal);
            var km = buildNavKeyMapWithActions(terminal);
            var bindingReader = new BindingReader(terminal.reader());
            terminal.writer().print(Theme.TERM_ALT_SCREEN_ENABLE + Theme.TERM_HIDE_CURSOR);
            terminal.writer().flush();

            while (true)
            {
                int width = terminal.getWidth();
                int height = terminal.getHeight();
                int boxWidth = Math.max(config.minBoxWidth(),
                        Math.min(config.maxBoxWidth(), width - 4));
                var logoRenderer = config.logoRenderer();
                int logoLines =
                        (logoRenderer != null && width >= config.logoMinWidth())
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

                if (terminal.reader().peek(50) == NonBlockingReader.READ_EXPIRED) continue;
                String action = bindingReader.readBinding(km, null, false);
                if (action == null) continue;

                if (confirmingDelete)
                {
                    switch (action)
                    {
                        case "CONFIRM" -> {
                            terminal.writer()
                                    .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                            terminal.writer().flush();
                            return new SelectResult.Delete<>(items.get(selectedIdx).requireValue());
                        }
                        case "ABORT", "QUIT" -> confirmingDelete = false;
                    }
                    continue;
                }

                switch (action)
                {
                    case "QUIT" -> {
                        terminal.writer()
                                .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return new SelectResult.Cancelled<>();
                    }
                    case "SELECT" -> {
                        terminal.writer()
                                .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return new SelectResult.Chosen<>(items.get(selectedIdx).requireValue());
                    }
                    case "DELETE" -> confirmingDelete = true;
                    case "UP" -> selectedIdx = nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }

    /** Arrow-key mini menu with D action binding (in-TUI confirmation). */
    @SuppressWarnings("EmptyCatch")
    private static <T> SelectResult<T> miniSelectWithActions(List<Item<T>> items,
            FullScreenConfig config) throws Exception
    {
        int numLines = items.size() + 2; // +1 header, +1 footer/status line
        int selectedIdx = config.initialIndex() > 0 ? config.initialIndex() : firstSelectable(items);
        boolean confirmingDelete = false;

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            disableIsig(terminal);
            var km = buildNavKeyMapWithActions(terminal);
            var bindingReader = new BindingReader(terminal.reader());
            terminal.writer().print(Theme.TERM_HIDE_CURSOR);
            boolean firstDraw = true;

            while (true)
            {
                if (!firstDraw) terminal.writer().print("\033[" + numLines + "A");
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

                if (terminal.reader().peek(100) == NonBlockingReader.READ_EXPIRED) continue;
                String action = bindingReader.readBinding(km, null, false);
                if (action == null) continue;

                if (confirmingDelete)
                {
                    switch (action)
                    {
                        case "CONFIRM" -> {
                            clearLines(terminal, numLines);
                            terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                            terminal.writer().flush();
                            return new SelectResult.Delete<>(items.get(selectedIdx).requireValue());
                        }
                        case "ABORT", "QUIT" -> confirmingDelete = false;
                    }
                    continue;
                }

                switch (action)
                {
                    case "QUIT" -> {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return new SelectResult.Cancelled<>();
                    }
                    case "SELECT" -> {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return new SelectResult.Chosen<>(items.get(selectedIdx).requireValue());
                    }
                    case "DELETE" -> confirmingDelete = true;
                    case "UP" -> selectedIdx = nextSelectable(items, selectedIdx, -1);
                    case "DOWN" -> selectedIdx = nextSelectable(items, selectedIdx, 1);
                }
            }
        }
    }

    /** Non-interactive fallback for selectWithActions: only Chosen and Cancelled available. */
    private static <T> SelectResult<T> classicSelectWithActions(List<Item<T>> items,
            FullScreenConfig config, PrintStream err)
    {
        err.println(config.title().strip() + ":");
        int n = 0;
        for (var item : items)
        {
            if (item.isSeparator())
                err.println("  " + item.label());
            else
                err.println("  [" + (++n) + "] " + item.label() + " — " + item.description());
        }
        err.print("Enter number (0 to cancel): ");
        err.flush();
        var scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        if (!scanner.hasNextInt()) return new SelectResult.Cancelled<>();
        int sel = scanner.nextInt();
        if (sel == 0) return new SelectResult.Cancelled<>();
        int idx = 0;
        for (var item : items)
        {
            if (item.isSeparator()) continue;
            if (++idx == sel) return new SelectResult.Chosen<>(item.requireValue());
        }
        return new SelectResult.Cancelled<>();
    }

    /**
     * Disables ISIG so Ctrl+C is delivered as the character {@code \x03} (ETX) rather than
     * generating SIGINT. Combined with the {@code \003} → QUIT binding, this routes Ctrl+C
     * through the normal quit path and ensures the terminal is restored cleanly on exit.
     * Same rationale as {@link com.fupfin.midiraja.io.JLineTerminalIO#init()}.
     */
    private static void disableIsig(Terminal terminal)
    {
        Attributes attr = terminal.getAttributes();
        attr.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attr);
    }

    private static KeyMap<String> buildNavKeyMap(Terminal terminal)
    {
        var km = new KeyMap<String>();
        km.setAmbiguousTimeout(100);
        String upSeq = KeyMap.key(terminal, InfoCmp.Capability.key_up);
        if (upSeq != null && !upSeq.isEmpty()) km.bind("UP", upSeq);
        km.bind("UP", "\033[A", "\033OA");
        String downSeq = KeyMap.key(terminal, InfoCmp.Capability.key_down);
        if (downSeq != null && !downSeq.isEmpty()) km.bind("DOWN", downSeq);
        km.bind("DOWN", "\033[B", "\033OB");
        km.bind("SELECT", "\r", "\n");
        km.bind("QUIT", "q", "Q", "\033", "\003"); // \003 = Ctrl+C (ETX), ISIG disabled
        return km;
    }

    private static KeyMap<String> buildNavKeyMapWithActions(Terminal terminal)
    {
        var km = buildNavKeyMap(terminal);
        km.bind("DELETE", "d", "D");
        km.bind("CONFIRM", "y", "Y");
        km.bind("ABORT",   "n", "N");
        return km;
    }

    private static void clearLines(Terminal terminal, int count)
    {
        terminal.writer().print("\033[" + count + "A");
        for (int i = 0; i < count; i++)
            terminal.writer().println(Theme.TERM_CLEAR_TO_EOL);
        terminal.writer().print("\033[" + count + "A");
    }
}
