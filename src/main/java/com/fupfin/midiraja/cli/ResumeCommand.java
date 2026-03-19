/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "resume",
        mixinStandardHelpOptions = true,
        description = "Select and re-launch a previous playback session.")
public class ResumeCommand implements Callable<Integer>
{
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Spec
    @Nullable
    private CommandSpec spec;

    @Option(names = {"--non-interactive"}, hidden = true,
            description = "Skip interactive selector (for testing).")
    boolean nonInteractive;

    @Override
    public Integer call()
    {
        var histPath = System.getProperty("midiraja.history.path");
        var history = histPath != null
                ? new SessionHistory(Path.of(histPath))
                : new SessionHistory();

        var all = history.getAll();
        if (all.isEmpty()) {
            err().println("No session history.");
            err().flush();
            return 0;
        }

        if (nonInteractive) {
            printList(all, history.getAutoCount());
            return 0;
        }

        return runInteractive(all, history);
    }

    private PrintWriter err()
    {
        return spec != null ? spec.commandLine().getErr() : new PrintWriter(System.err, true); // NOSONAR fallback
    }

    private PrintWriter out()
    {
        return spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out, true); // NOSONAR fallback
    }

    private void printList(List<SessionEntry> all, int autoCount)
    {
        var out = out();
        if (autoCount > 0) {
            out.println("--- Recent ---");
            for (int i = 0; i < autoCount; i++) {
                var e = all.get(i);
                out.printf("[%d] [%s] %s%n", i + 1, FMT.format(e.savedAt()), String.join(" ", e.args()));
            }
        }
        if (all.size() > autoCount) {
            out.println("--- Saved ---");
            for (int i = autoCount; i < all.size(); i++) {
                var e = all.get(i);
                out.printf("[%d] [%s] \u2605 %s%n", i + 1, FMT.format(e.savedAt()), String.join(" ", e.args()));
            }
        }
        out.flush();
    }

    private int runInteractive(List<SessionEntry> all, SessionHistory history)
    {
        int autoCount = history.getAutoCount();
        List<TerminalSelector.Item<Integer>> items = buildItems(all, autoCount);

        var config = new TerminalSelector.FullScreenConfig(" RESUME SESSION ", 60, 200);
        boolean fullMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.fullMode;
        boolean miniMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.miniMode;
        boolean classicMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.classicMode;

        PrintStream errStream = parent != null ? parent.getErr() : System.err;

        while (true) {
            TerminalSelector.SelectResult<Integer> result;
            try {
                result = TerminalSelector.selectWithActions(items, config, fullMode, miniMode,
                        classicMode, errStream);
            } catch (Exception e) {
                err().println("Error: " + e.getMessage());
                err().flush();
                return 1;
            }

            if (result instanceof TerminalSelector.SelectResult.Cancelled<Integer>) return 0;

            if (result instanceof TerminalSelector.SelectResult.Chosen<Integer> chosen) {
                var entry = all.get(chosen.value());
                err().println("Launching: " + String.join(" ", entry.args()));
                err().flush();
                return new CommandLine(new MidirajaCommand())
                        .execute(entry.args().toArray(new String[0]));
            }

            if (result instanceof TerminalSelector.SelectResult.Delete<Integer> del) {
                int idx = del.value();
                int deletedItemsIdx = nearestItemsIdx(items, idx);
                if (idx < autoCount) history.deleteAuto(idx);
                else history.deleteBookmark(idx - autoCount);
                all = history.getAll();
                autoCount = history.getAutoCount();
                if (all.isEmpty()) {
                    err().println("No session history.");
                    err().flush();
                    return 0;
                }
                items = buildItems(all, autoCount);
                int nextIdx = Math.min(deletedItemsIdx, items.size() - 1);
                while (nextIdx > 0 && items.get(nextIdx).isSeparator()) nextIdx--;
                config = config.withInitialIndex(nextIdx);
                continue;
            }
        }
    }

    /** Returns the items-list index of the first selectable item with value >= targetAllIdx,
     *  or the last selectable index if none found. */
    private static int nearestItemsIdx(List<TerminalSelector.Item<Integer>> items, int targetAllIdx)
    {
        int lastSelectable = TerminalSelector.firstSelectable(items);
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (!item.isSeparator()) {
                if (item.requireValue() >= targetAllIdx) return i;
                lastSelectable = i;
            }
        }
        return lastSelectable;
    }

    private ArrayList<TerminalSelector.Item<Integer>> buildItems(List<SessionEntry> all, int autoCount)
    {
        var items = new ArrayList<TerminalSelector.Item<Integer>>();
        int bookmarkCount = all.size() - autoCount;

        if (autoCount > 0) {
            items.add(TerminalSelector.Item.separator("Recent"));
            for (int i = 0; i < autoCount; i++) {
                var e = all.get(i);
                items.add(TerminalSelector.Item.of(i, "[" + FMT.format(e.savedAt()) + "] " + formatArgs(e.args()), ""));
            }
        }

        if (bookmarkCount > 0) {
            items.add(TerminalSelector.Item.separator("Saved"));
            for (int i = autoCount; i < all.size(); i++) {
                var e = all.get(i);
                items.add(TerminalSelector.Item.of(i, "[" + FMT.format(e.savedAt()) + "] \u2605 " + formatArgs(e.args()), ""));
            }
        }

        return items;
    }

    /**
     * Formats arg tokens for single-line display: path tokens are middle-truncated to 35 chars,
     * flags and other short tokens are kept as-is.
     */
    private static String formatArgs(List<String> args)
    {
        var sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            String token = args.get(i);
            sb.append(token.startsWith("-") ? token : truncateMidPath(token, 35));
        }
        return sb.toString();
    }

    /**
     * Middle-truncates a string, preserving the start and end.
     * More space is given to the end (filename is more recognizable than directory prefix).
     */
    static String truncateMidPath(String s, int max)
    {
        if (s.length() <= max) return s;
        int keepEnd = (max - 1) * 3 / 5;
        int keepStart = max - 1 - keepEnd;
        return s.substring(0, keepStart) + "\u2026" + s.substring(s.length() - keepEnd);
    }
}
