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
        for (int i = 0; i < all.size(); i++) {
            var e = all.get(i);
            boolean isBookmark = i >= autoCount;
            String marker = isBookmark ? " \u2605" : "";
            out.printf("[%d] %s  [%s]%s%n",
                    i + 1,
                    String.join(" ", e.args()),
                    FMT.format(e.savedAt()),
                    marker);
        }
        out.flush();
    }

    private int runInteractive(List<SessionEntry> all, SessionHistory history)
    {
        int autoCount = history.getAutoCount();
        List<TerminalSelector.Item<Integer>> items = buildItems(all, autoCount);

        var config = new TerminalSelector.FullScreenConfig(" RESUME SESSION ", 60, 60);
        boolean fullMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.fullMode;
        boolean miniMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.miniMode;
        boolean classicMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.classicMode;

        PrintStream errStream = parent != null ? parent.getErr() : System.err;

        while (true) {
            TerminalSelector.SelectResult result;
            try {
                result = TerminalSelector.selectWithActions(items, config, fullMode, miniMode,
                        classicMode, errStream);
            } catch (Exception e) {
                err().println("Error: " + e.getMessage());
                err().flush();
                return 1;
            }

            if (result instanceof TerminalSelector.SelectResult.Cancelled) return 0;

            if (result instanceof TerminalSelector.SelectResult.Chosen chosen) {
                var entry = all.get(chosen.value());
                return new CommandLine(new MidirajaCommand())
                        .execute(entry.args().toArray(new String[0]));
            }

            if (result instanceof TerminalSelector.SelectResult.Delete del) {
                int idx = del.value();
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
                continue;
            }

            if (result instanceof TerminalSelector.SelectResult.Promote promote) {
                int idx = promote.value();
                if (idx < autoCount) {
                    history.promoteToBookmark(idx);
                    all = history.getAll();
                    autoCount = history.getAutoCount();
                    items = buildItems(all, autoCount);
                }
                continue;
            }
        }
    }

    private ArrayList<TerminalSelector.Item<Integer>> buildItems(List<SessionEntry> all, int autoCount)
    {
        var items = new ArrayList<TerminalSelector.Item<Integer>>();
        for (int i = 0; i < all.size(); i++) {
            var e = all.get(i);
            boolean isBookmark = i >= autoCount;
            String label = String.join(" ", e.args());
            String detail = "[" + FMT.format(e.savedAt()) + "]" + (isBookmark ? "  \u2605" : "");
            items.add(TerminalSelector.Item.of(i, label, detail));
        }
        return items;
    }
}
