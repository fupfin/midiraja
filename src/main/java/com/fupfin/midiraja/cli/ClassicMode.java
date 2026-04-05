/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

import org.jspecify.annotations.Nullable;

final class ClassicMode
{
    private ClassicMode()
    {
    }

    /** Non-interactive fallback: numbered prompt on stderr. */
    @Nullable
    static <T> T select(List<TerminalSelector.Item<T>> items,
            TerminalSelector.FullScreenConfig config, PrintStream err)
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
        if (!scanner.hasNextInt())
            return null;
        int sel = scanner.nextInt();
        int idx = 0;
        for (var item : items)
        {
            if (item.isSeparator())
                continue;
            if (++idx == sel)
                return item.value();
        }
        return null;
    }

    /** Non-interactive fallback for selectWithActions: only Chosen and Cancelled available. */
    static <T> TerminalSelector.SelectResult<T> selectWithActions(List<TerminalSelector.Item<T>> items,
            TerminalSelector.FullScreenConfig config, PrintStream err)
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
        if (!scanner.hasNextInt())
            return new TerminalSelector.SelectResult.Cancelled<>();
        int sel = scanner.nextInt();
        if (sel == 0)
            return new TerminalSelector.SelectResult.Cancelled<>();
        int idx = 0;
        for (var item : items)
        {
            if (item.isSeparator())
                continue;
            if (++idx == sel)
                return new TerminalSelector.SelectResult.Chosen<>(item.requireValue());
        }
        return new TerminalSelector.SelectResult.Cancelled<>();
    }
}
