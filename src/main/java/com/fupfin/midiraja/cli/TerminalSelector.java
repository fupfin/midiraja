/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.io.JLineTerminalIO;
import com.fupfin.midiraja.io.NavKeyMapFactory;
import com.fupfin.midiraja.ui.ScreenBuffer;

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
    {
    }

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
     * @param title
     *            Title text shown in the header bar (e.g. {@code " SELECT ENGINE "})
     * @param minBoxWidth
     *            Minimum box width
     * @param maxBoxWidth
     *            Maximum box width (clamped to terminal width − 4)
     * @param logoMinWidth
     *            Minimum terminal width required to show the logo; ≤0 = never show
     * @param logoLineCount
     *            Number of lines the logo occupies (used for vertical centering)
     * @param logoRenderer
     *            Lambda that appends logo content to the buffer; null = no logo
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
        record Chosen<T>(T value) implements SelectResult<T>
        {
        }

        record Delete<T>(T value) implements SelectResult<T>
        {
        }

        record Promote<T>(T value) implements SelectResult<T>
        {
        }

        record Cancelled<T>() implements SelectResult<T>
        {
        }
    }

    /**
     * Single source of truth for mode decision. Pure function — no terminal access.
     */
    public static SelectionMode resolveMode(boolean isInteractive, int termHeight,
            boolean preferFull, boolean preferMini, boolean preferClassic)
    {
        if (!isInteractive || preferClassic)
            return SelectionMode.CLASSIC;
        if (preferMini)
            return SelectionMode.MINI;
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
            case FULL -> FullScreenMode.select(items, config);
            case MINI -> MiniMode.select(items, config);
            case CLASSIC -> ClassicMode.select(items, config, err);
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
        if (items.isEmpty())
            return new SelectResult.Cancelled<>();

        var probe = new JLineTerminalIO();
        probe.init();
        boolean isInteractive = probe.isInteractive();
        int termHeight = probe.getHeight();
        probe.close();

        return switch (resolveMode(isInteractive, termHeight, preferFull, preferMini, preferClassic))
        {
            case FULL -> FullScreenMode.selectWithActions(items, config);
            case MINI -> MiniMode.selectWithActions(items, config);
            case CLASSIC -> ClassicMode.selectWithActions(items, config, err);
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
            if (!items.get(i).isSeparator())
                return i;
        return 0;
    }

    static KeyMap<String> buildNavKeyMap(Terminal terminal)
    {
        return NavKeyMapFactory.buildNavKeyMap(terminal, "UP", "DOWN", "SELECT", "QUIT");
    }

    static KeyMap<String> buildNavKeyMapWithActions(Terminal terminal)
    {
        var km = buildNavKeyMap(terminal);
        km.bind("DELETE", "d", "D");
        km.bind("CONFIRM", "y", "Y");
        km.bind("ABORT", "n", "N");
        return km;
    }
}
