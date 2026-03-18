/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import java.io.PrintStream;

/** ASCII-art logo for Midiraja. */
public final class Logo
{
    private Logo()
    {}

    // "MIDIRAJA" in 5-line ANSI Shadow style — 64 chars wide, 5 lines
    // Top-to-bottom amber gradient: bright (215) → dim (94)
    public static final String[] LINE_COLORS = {
        "\033[38;5;215m",  // line 1 — brightest amber
        "\033[38;5;214m",  // line 2
        "\033[38;5;172m",  // line 3 — medium amber
        "\033[38;5;130m",  // line 4
        "\033[38;5;94m"    // line 5 — darkest amber
    };

    public static final String[] LINES = {
        "███╗   ███╗ ██╗ ██████╗  ██╗ ██████╗   █████╗       ██╗  █████╗ ",
        "████╗ ████║ ██║ ██╔══██╗ ██║ ██╔══██╗ ██╔══██╗      ██║ ██╔══██╗",
        "██╔████╔██║ ██║ ██║  ██║ ██║ ██████╔╝ ███████║      ██║ ███████║",
        "██║ ╚═╝ ██║ ██║ ██████╔╝ ██║ ██║  ██╗ ██║  ██║ ██   ██║ ██║  ██║",
        "╚═╝     ╚═╝ ╚═╝ ╚═════╝  ╚═╝ ╚═╝  ╚═╝ ╚═╝  ╚═╝ ╚█████╔╝ ╚═╝  ╚═╝"
    };

    public static final String VU_BARS = "[▃▅▇▅▆▄]";
    public static final String SUBTITLE_TEXT = "Play MIDI anywhere, any way";
    public static final String SUBTITLE = VU_BARS + "  " + SUBTITLE_TEXT;
    public static final int WIDTH = 64;

    /** Prints the logo and subtitle to {@code out}, with amber color if the terminal supports it. */
    public static void print(PrintStream out)
    {
        for (int i = 0; i < LINES.length; i++)
            out.println(LINE_COLORS[i] + LINES[i] + Theme.COLOR_RESET);
        int pad = (WIDTH - SUBTITLE.length()) / 2;
        out.println(" ".repeat(pad)
                + Theme.COLOR_VU + VU_BARS + Theme.COLOR_DIM_FG + "  " + SUBTITLE_TEXT + Theme.COLOR_RESET);
        out.println();
    }
}
