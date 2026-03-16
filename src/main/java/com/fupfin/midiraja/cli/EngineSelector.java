/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.io.JLineTerminalIO;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.ScreenBuffer;
import com.fupfin.midiraja.ui.Theme;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Interactive engine selection menu shown when {@code midra} is run without a subcommand.
 *
 * <p>
 * Combines built-in zero-setup synthesizers with available OS MIDI ports into a single list, then
 * lets the user pick one with arrow keys.
 */
public class EngineSelector
{
    public sealed interface Choice permits Choice.Builtin, Choice.Port
    {
        record Builtin(String engineName) implements Choice
        {
        }

        record Port(int portIndex) implements Choice
        {
        }
    }

    private record Entry(@Nullable Choice choice, String col1, String col2)
    {
        boolean isSeparator()
        {
            return choice == null;
        }

        static Entry builtin(String name, String desc)
        {
            return new Entry(new Choice.Builtin(name.strip()), name, desc);
        }

        static Entry port(MidiPort p)
        {
            return new Entry(new Choice.Port(p.index()), p.name(), "OS MIDI port");
        }

        static Entry separator(String label)
        {
            return new Entry(null, label, "");
        }
    }

    // Built-in engines ordered by audio quality (best first)
    private static final List<Entry> BUILTIN_ENTRIES = List.of(
            Entry.builtin("soundfont", "SoundFont            (MuseScore General SF3 bundled)"),
            Entry.builtin("patch    ", "GUS wavetable        (FreePats bundled)"),
            Entry.builtin("opn      ", "OPN FM synthesis     (Sega Genesis / PC-98)"),
            Entry.builtin("opl      ", "OPL FM synthesis     (DOS / AdLib / Sound Blaster)"),
            Entry.builtin("psg      ", "PSG chiptune         (MSX / ZX Spectrum / Atari ST)"),
            Entry.builtin("1bit     ", "1-Bit Beeper         (Apple II / PC Speaker)"));

    /**
     * Shows the engine selection menu and returns the user's choice, or {@code null} if the user
     * quit.
     */
    @Nullable
    public static Choice select(List<MidiPort> ports, boolean preferFullMode, boolean preferMini,
            boolean preferClassic, PrintStream err) throws Exception
    {
        var entries = buildEntries(ports);

        var probe = new JLineTerminalIO();
        probe.init();
        boolean isInteractive = probe.isInteractive();
        probe.close();

        if (!isInteractive || preferClassic) return fallbackSelect(entries, err);

        if (preferMini) return dynamicSelect(entries);
        return preferFullMode ? fullScreenSelect(entries) : dynamicSelect(entries);
    }

    private static List<Entry> buildEntries(List<MidiPort> ports)
    {
        var list = new ArrayList<Entry>();
        if (!ports.isEmpty())
        {
            ports.forEach(p -> list.add(Entry.port(p)));
            list.add(Entry.separator("── Built-in Engines ───────────────────────────────────────"));
        }
        list.addAll(BUILTIN_ENTRIES);
        return list;
    }

    /** Advance {@code from} by {@code direction} (+1 or −1), skipping separator entries. */
    private static int nextSelectable(List<Entry> entries, int from, int direction)
    {
        int size = entries.size();
        int i = (from + direction + size) % size;
        while (entries.get(i).isSeparator())
        {
            i = (i + direction + size) % size;
        }
        return i;
    }

    /** Non-interactive fallback: numbered prompt on stdout/stderr. */
    @Nullable
    private static Choice fallbackSelect(List<Entry> entries, PrintStream err)
    {
        err.println("Select an engine:");
        int n = 0;
        for (var e : entries)
        {
            if (e.isSeparator())
            {
                err.println("  " + e.col1());
            }
            else
            {
                err.println("  [" + (++n) + "] " + e.col1() + " — " + e.col2());
            }
        }
        err.print("Enter number: ");
        err.flush();
        var scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        if (!scanner.hasNextInt()) return null;
        int sel = scanner.nextInt();
        int idx = 0;
        for (var e : entries)
        {
            if (e.isSeparator()) continue;
            if (++idx == sel) return e.choice();
        }
        return null;
    }

    /** Arrow-key menu that redraws in-place (mini / small terminal). */
    @Nullable
    @SuppressWarnings("EmptyCatch")
    private static Choice dynamicSelect(List<Entry> entries) throws Exception
    {
        int numLines = entries.size() + 1; // +1 for the header line
        int selectedIdx = firstSelectable(entries);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            var reader = terminal.reader();
            terminal.writer().print(Theme.TERM_HIDE_CURSOR);
            boolean firstDraw = true;

            while (true)
            {
                if (!firstDraw) terminal.writer().print("\033[" + numLines + "A");
                firstDraw = false;

                terminal.writer().println("Select an engine:");
                for (int i = 0; i < entries.size(); i++)
                {
                    Entry e = entries.get(i);
                    if (e.isSeparator())
                    {
                        terminal.writer().println(
                                "  " + Theme.COLOR_HIGHLIGHT + e.col1() + Theme.COLOR_RESET
                                        + Theme.TERM_CLEAR_TO_EOL);
                    }
                    else
                    {
                        String prefix = (i == selectedIdx) ? " > " : "   ";
                        terminal.writer().println(prefix + e.col1() + "  " + e.col2()
                                + Theme.TERM_CLEAR_TO_EOL);
                    }
                }
                terminal.writer().flush();

                int ch = reader.read(10);
                if (ch <= 0) continue;

                if (ch == 'q' || ch == 'Q')
                {
                    clearLines(terminal, numLines);
                    terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return null;
                }
                if (ch == 13 || ch == 10)
                {
                    clearLines(terminal, numLines);
                    terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return entries.get(selectedIdx).choice();
                }
                if (ch == 27)
                {
                    int next1 = reader.read(2);
                    if (next1 == '[')
                    {
                        int next2 = reader.read(2);
                        if (next2 == 'A') selectedIdx = nextSelectable(entries, selectedIdx, -1);
                        else if (next2 == 'B')
                            selectedIdx = nextSelectable(entries, selectedIdx, 1);
                    }
                    else
                    {
                        clearLines(terminal, numLines);
                        terminal.writer().print(Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return null;
                    }
                }
            }
        }
    }

    /** Full-screen alt-buffer menu with title box and section headers. */
    @Nullable
    @SuppressWarnings("EmptyCatch")
    private static Choice fullScreenSelect(List<Entry> entries) throws Exception
    {
        int selectedIdx = firstSelectable(entries);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build())
        {
            terminal.enterRawMode();
            var reader = terminal.reader();
            terminal.writer().print(Theme.TERM_ALT_SCREEN_ENABLE + Theme.TERM_HIDE_CURSOR);
            terminal.writer().flush();

            while (true)
            {
                int width = terminal.getWidth();
                int height = terminal.getHeight();
                int boxWidth = Math.max(64, Math.min(84, width - 4));
                int boxHeight = entries.size() + 4;
                int padLeft = Math.max(0, (width - boxWidth) / 2);
                int padTop = Math.max(0, (height - boxHeight) / 2);

                var buf = new ScreenBuffer(8192);
                buf.append(Theme.TERM_CURSOR_HOME).append(Theme.TERM_CLEAR_TO_END);
                buf.repeat("\n", padTop);

                String title = " SELECT PLAYBACK ENGINE ";
                int titlePad = (boxWidth - title.length() - 2) / 2;
                buf.repeat(" ", padLeft).append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.DECORATOR_LINE, titlePad).append(Theme.COLOR_RESET)
                        .append(Theme.FORMAT_INVERT).append(title).append(Theme.COLOR_RESET)
                        .append(Theme.COLOR_HIGHLIGHT)
                        .repeat(Theme.DECORATOR_LINE, boxWidth - titlePad - title.length())
                        .append(Theme.COLOR_RESET).appendLine();

                for (int i = 0; i < entries.size(); i++)
                {
                    Entry e = entries.get(i);
                    buf.repeat(" ", padLeft);
                    if (e.isSeparator())
                    {
                        buf.append("  ").append(Theme.COLOR_HIGHLIGHT).append(e.col1())
                                .append(Theme.COLOR_RESET).appendLine();
                    }
                    else
                    {
                        String label = e.col1();
                        String desc = e.col2();
                        int maxDesc = boxWidth - label.length() - 8;
                        if (desc.length() > maxDesc) desc = desc.substring(0, maxDesc - 1) + "…";
                        if (i == selectedIdx)
                        {
                            buf.append("  ").append(Theme.COLOR_HIGHLIGHT)
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

                int ch = reader.read(50);
                if (ch <= 0) continue;

                if (ch == 'q' || ch == 'Q')
                {
                    terminal.writer()
                            .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return null;
                }
                if (ch == 13 || ch == 10)
                {
                    terminal.writer()
                            .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                    terminal.writer().flush();
                    return entries.get(selectedIdx).choice();
                }
                if (ch == 27)
                {
                    int next1 = reader.read(2);
                    if (next1 == '[')
                    {
                        int next2 = reader.read(2);
                        if (next2 == 'A') selectedIdx = nextSelectable(entries, selectedIdx, -1);
                        else if (next2 == 'B')
                            selectedIdx = nextSelectable(entries, selectedIdx, 1);
                    }
                    else
                    {
                        terminal.writer()
                                .print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
                        terminal.writer().flush();
                        return null;
                    }
                }
            }
        }
    }

    private static int firstSelectable(List<Entry> entries)
    {
        for (int i = 0; i < entries.size(); i++)
        {
            if (!entries.get(i).isSeparator()) return i;
        }
        return 0;
    }

    private static void clearLines(Terminal terminal, int count)
    {
        terminal.writer().print("\033[" + count + "A");
        for (int i = 0; i < count; i++)
        {
            terminal.writer().println(Theme.TERM_CLEAR_TO_EOL);
        }
        terminal.writer().print("\033[" + count + "A");
    }
}
