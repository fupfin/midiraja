/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.PrintStream;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.io.AltScreenScope;
import com.fupfin.midiraja.io.NavKeyMapFactory;
import com.fupfin.midiraja.io.TerminalModeManager;
import com.fupfin.midiraja.ui.Logo;
import com.fupfin.midiraja.ui.ScreenBuffer;
import com.fupfin.midiraja.ui.Theme;

/**
 * Full-screen transition screen shown between demo tracks. Displays the upcoming track's title and
 * synthesis engine, and waits for a navigation key before playback begins.
 */
class DemoTransitionScreen
{
    private static final int AUTOPLAY_SECONDS = 5;

    private DemoTransitionScreen()
    {
    }

    /**
     * Shows the transition screen between demo tracks.
     * <p>
     * In classic mode: prints a one-line summary and returns immediately. In full/mini TUI mode:
     * shows a full-screen panel with a {@value #AUTOPLAY_SECONDS}-second countdown.
     *
     * @param trackIndex
     *            0-based index of the track about to play
     * @param totalTracks
     *            total number of tracks in the demo
     * @param songTitle
     *            human-readable song title
     * @param engineLabel
     *            human-readable synthesis engine name
     * @param classicMode
     *            if {@code true}, skip the TUI and print to {@code out} instead
     * @param out
     *            output stream used in classic mode
     * @return {@code NEXT} to proceed, {@code PREVIOUS} to go back, {@code QUIT_ALL} to exit
     */
    static PlaybackStatus show(int trackIndex, int totalTracks, String songTitle,
            String engineLabel, boolean classicMode, PrintStream out) throws Exception
    {
        if (classicMode)
        {
            out.printf("%n---[ Demo %d/%d ]------%n", trackIndex + 1, totalTracks);
            return PlaybackStatus.FINISHED;
        }

        var terminal = TerminalBuilder.builder().system(true).build();
        try (terminal)
        {
            if (terminal.getHeight() < 10 || "dumb".equals(terminal.getType()))
            {
                return PlaybackStatus.NEXT;
            }

            // enterRawNoIsig disables ISIG so Ctrl+C is delivered as \x03 (ETX) rather than
            // SIGINT; the \003→QUIT_ALL binding below routes it through the clean quit path.
            TerminalModeManager.enterRawNoIsig(terminal);
            var writer = terminal.writer();

            var keyMap = buildKeyMap(terminal);
            var bindingReader = new BindingReader(terminal.reader());

            long deadline = System.currentTimeMillis() + AUTOPLAY_SECONDS * 1000L;

            try (var alt = AltScreenScope.enter(writer))
            {
                while (true)
                {
                    long remaining = (deadline - System.currentTimeMillis() + 999) / 1000;
                    if (remaining <= 0)
                    {
                        alt.clearScreen();
                        return PlaybackStatus.FINISHED;
                    }
                    int width = terminal.getWidth();
                    int height = terminal.getHeight();
                    int boxWidth = Math.max(56, Math.min(76, width - 4));
                    int padLeft = Math.max(0, (width - boxWidth) / 2);
                    boolean showLogo = width >= Logo.WIDTH + 4;
                    int logoLines = showLogo ? Logo.LINES.size() + 2 : 0; // +2 for subtitle+blank
                    // title + blank + trackSection + trackTitle + blank + engineSection +
                    // engineLabel + blank + sep + footer = 10 lines
                    int padTop = Math.max(1, (height - 10 - logoLines) / 2);

                    var buf = new ScreenBuffer();
                    buf.append(Theme.TERM_CURSOR_HOME).append(Theme.TERM_CLEAR_TO_END);
                    buf.repeat("\n", padTop);

                    if (showLogo)
                    {
                        int logoPad = Math.max(0, (width - Logo.WIDTH) / 2);
                        for (int li = 0; li < Logo.LINES.size(); li++)
                            buf.repeat(" ", logoPad).append(Logo.LINE_COLORS.get(li))
                                    .append(Logo.LINES.get(li))
                                    .append(Theme.COLOR_RESET).appendLine();
                        int subtitlePad = Math.max(0, (width - Logo.SUBTITLE.length()) / 2);
                        buf.repeat(" ", subtitlePad)
                                .append(Theme.COLOR_VU).append(Logo.VU_BARS)
                                .append(Theme.COLOR_DIM_FG).append("  ").append(Logo.SUBTITLE_TEXT)
                                .append(Theme.COLOR_RESET).appendLine();
                        buf.appendLine();
                    }

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
                            ? songTitle.substring(0, maxLen - 1) + "…"
                            : songTitle;
                    buf.repeat(" ", padLeft + 4).append(displayTitle).appendLine();

                    buf.appendLine();

                    // ── Engine section ────────────────────────────────────────────────
                    buf.repeat(" ", padLeft)
                            .append(Theme.COLOR_HIGHLIGHT)
                            .append(Theme.DECORATOR_TITLE_PREFIX).append("SYNTHESIS ENGINE")
                            .append(Theme.DECORATOR_TITLE_SUFFIX)
                            .append(Theme.COLOR_RESET).appendLine();

                    String displayEngine = engineLabel.length() > maxLen
                            ? engineLabel.substring(0, maxLen - 1) + "…"
                            : engineLabel;
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

                    if (terminal.reader().peek(200) == NonBlockingReader.READ_EXPIRED)
                        continue;
                    PlaybackStatus action = bindingReader.readBinding(keyMap, null, false);
                    if (action == null)
                        continue;

                    switch (action)
                    {
                        case QUIT_ALL ->
                        {
                            alt.exit();
                            return PlaybackStatus.QUIT_ALL;
                        }
                        case PREVIOUS ->
                        {
                            alt.clearScreen();
                            return PlaybackStatus.PREVIOUS;
                        }
                        case NEXT ->
                        {
                            alt.clearScreen();
                            return PlaybackStatus.NEXT;
                        }
                        case FINISHED ->
                        {
                            alt.clearScreen();
                            return PlaybackStatus.FINISHED;
                        }
                        default ->
                        {
                            /* ignore */
                        }
                    }
                }
            }
        }
    }

    private static KeyMap<PlaybackStatus> buildKeyMap(org.jline.terminal.Terminal terminal)
    {
        var km = NavKeyMapFactory.buildNavKeyMap(terminal,
                PlaybackStatus.PREVIOUS, PlaybackStatus.NEXT,
                PlaybackStatus.FINISHED, PlaybackStatus.QUIT_ALL);
        km.bind(PlaybackStatus.FINISHED, " ", "n", "N");
        km.bind(PlaybackStatus.PREVIOUS, "p", "P");
        return km;
    }

}
