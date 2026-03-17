/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.ui.ScreenBuffer;
import com.fupfin.midiraja.ui.Theme;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jline.terminal.TerminalBuilder;

/**
 * Full-screen transition screen shown between demo tracks. Displays the upcoming track's title and
 * synthesis engine, and waits for a navigation key before playback begins.
 */
class DemoTransitionScreen
{
    private static final int AUTOPLAY_SECONDS = 5;

    private DemoTransitionScreen()
    {}

    /**
     * Shows the transition screen between demo tracks.
     * <p>
     * In classic mode: prints a one-line summary and returns immediately. In full/mini TUI mode:
     * shows a full-screen panel with a {@value #AUTOPLAY_SECONDS}-second countdown.
     *
     * @param trackIndex  0-based index of the track about to play
     * @param totalTracks total number of tracks in the demo
     * @param songTitle   human-readable song title
     * @param engineLabel human-readable synthesis engine name
     * @param classicMode if {@code true}, skip the TUI and print to {@code out} instead
     * @param out         output stream used in classic mode
     * @return {@code NEXT} to proceed, {@code PREVIOUS} to go back, {@code QUIT_ALL} to exit
     */
    static PlaybackStatus show(int trackIndex, int totalTracks, String songTitle,
            String engineLabel, boolean classicMode, PrintStream out) throws Exception
    {
        if (classicMode)
        {
            out.printf("%n---[ Demo %d/%d ]------%n", trackIndex + 1, totalTracks);
            return PlaybackStatus.NEXT;
        }

        var jlineLog = Logger.getLogger("org.jline.nativ.JLineNativeLoader");
        var savedLevel = jlineLog.getLevel();
        jlineLog.setLevel(Level.SEVERE);
        var terminal = TerminalBuilder.builder().system(true).build();
        jlineLog.setLevel(savedLevel);
        try (terminal)
        {
            if (terminal.getHeight() < 10 || "dumb".equals(terminal.getType()))
            {
                return PlaybackStatus.NEXT;
            }

            terminal.enterRawMode();
            var reader = terminal.reader();
            var writer = terminal.writer();
            writer.print(Theme.TERM_ALT_SCREEN_ENABLE + Theme.TERM_HIDE_CURSOR);
            writer.flush();

            long deadline = System.currentTimeMillis() + AUTOPLAY_SECONDS * 1000L;

            while (true)
            {
                    long remaining = (deadline - System.currentTimeMillis() + 999) / 1000;
                    if (remaining <= 0)
                    {
                        exitAltScreen(writer);
                        return PlaybackStatus.FINISHED;
                    }
                    int width = terminal.getWidth();
                    int height = terminal.getHeight();
                    int boxWidth = Math.max(56, Math.min(76, width - 4));
                    int padLeft = Math.max(0, (width - boxWidth) / 2);
                    // title + blank + trackSection + trackTitle + blank + engineSection +
                    // engineLabel + blank + sep + footer = 10 lines
                    int padTop = Math.max(1, (height - 10) / 2);

                    var buf = new ScreenBuffer();
                    buf.append(Theme.TERM_CURSOR_HOME).append(Theme.TERM_CLEAR_TO_END);
                    buf.repeat("\n", padTop);

                    // ── Title bar ─────────────────────────────────────────────────────
                    String title = " MIDIRAJA ENGINE TOUR ";
                    int titlePad = (boxWidth - title.length()) / 2;
                    buf.repeat(" ", padLeft)
                            .append(Theme.COLOR_HIGHLIGHT).repeat(Theme.DECORATOR_LINE, titlePad)
                            .append(Theme.COLOR_RESET)
                            .append(Theme.FORMAT_INVERT).append(title).append(Theme.COLOR_RESET)
                            .append(Theme.COLOR_HIGHLIGHT)
                            .repeat(Theme.DECORATOR_LINE, boxWidth - titlePad - title.length())
                            .append(Theme.COLOR_RESET).appendLine();

                    buf.appendLine();

                    int maxLen = boxWidth - 6;

                    // ── Track section ─────────────────────────────────────────────────
                    String trackLabel = String.format("TRACK %d / %d", trackIndex + 1, totalTracks);
                    buf.repeat(" ", padLeft)
                            .append(Theme.COLOR_HIGHLIGHT)
                            .append(Theme.DECORATOR_TITLE_PREFIX).append(trackLabel)
                            .append(Theme.DECORATOR_TITLE_SUFFIX)
                            .append(Theme.COLOR_RESET).appendLine();

                    String displayTitle = songTitle.length() > maxLen
                            ? songTitle.substring(0, maxLen - 1) + "…" : songTitle;
                    buf.repeat(" ", padLeft + 4).append(displayTitle).appendLine();

                    buf.appendLine();

                    // ── Engine section ────────────────────────────────────────────────
                    buf.repeat(" ", padLeft)
                            .append(Theme.COLOR_HIGHLIGHT)
                            .append(Theme.DECORATOR_TITLE_PREFIX).append("SYNTHESIS ENGINE")
                            .append(Theme.DECORATOR_TITLE_SUFFIX)
                            .append(Theme.COLOR_RESET).appendLine();

                    String displayEngine = engineLabel.length() > maxLen
                            ? engineLabel.substring(0, maxLen - 1) + "…" : engineLabel;
                    buf.repeat(" ", padLeft + 4).append(displayEngine).appendLine();

                    buf.appendLine();

                    // ── Separator & footer ────────────────────────────────────────────
                    buf.repeat(" ", padLeft)
                            .append(Theme.COLOR_HIGHLIGHT)
                            .repeat(Theme.BORDER_HORIZONTAL, boxWidth)
                            .append(Theme.COLOR_RESET).appendLine();

                    String footer = String.format(
                            "[Enter] Play   [▲/▼] Prev/Next   [Q] Quit   (auto in %ds)", remaining);
                    int footerPad = Math.max(0, (boxWidth - footer.length()) / 2);
                    buf.repeat(" ", padLeft + footerPad)
                            .append(Theme.COLOR_HIGHLIGHT).append(footer)
                            .append(Theme.COLOR_RESET).appendLine();

                    writer.print(buf.toString());
                    writer.flush();

                    int ch = reader.read(200);
                    if (ch <= 0) continue;

                    if (ch == 'q' || ch == 'Q') { exitAltScreen(writer); return PlaybackStatus.QUIT_ALL; }
                    if (ch == 'p' || ch == 'P')
                    {
                        clearScreen(writer);
                        return PlaybackStatus.PREVIOUS;
                    }
                    if (ch == 13 || ch == 10 || ch == ' ' || ch == 'n' || ch == 'N')
                    {
                        exitAltScreen(writer);
                        return PlaybackStatus.FINISHED;
                    }
                    if (ch == 27)
                    {
                        int next1 = reader.read(2);
                        if (next1 == '[')
                        {
                            int next2 = reader.read(2);
                            if (next2 == 'A') { clearScreen(writer); return PlaybackStatus.PREVIOUS; }
                            if (next2 == 'B') { clearScreen(writer); return PlaybackStatus.NEXT; }
                        }
                    }
            }
        }
    }

    /** Clears the alt-screen content (used when transitioning to prev/next track). */
    private static void clearScreen(PrintWriter writer)
    {
        writer.print(Theme.TERM_CURSOR_HOME + Theme.TERM_CLEAR_TO_END);
        writer.flush();
    }

    /** Fully exits the alt screen (used when handing off to playback or quitting). */
    private static void exitAltScreen(PrintWriter writer)
    {
        writer.print(Theme.TERM_ALT_SCREEN_DISABLE + Theme.TERM_SHOW_CURSOR);
        writer.flush();
    }
}
