/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

/**
 * Reusable UI component for rendering horizontal bars with a highlighted trail, a white peak/head,
 * and a customizable background grid.
 */
public class ProgressBar
{
    public enum Style
    {
        /** Used for playback progress (e.g. [█████░░░]) */
        SOLID_BACKGROUND(Theme.COLOR_RESET, Theme.CHAR_BLOCK_EMPTY),
        /** Used for VU meters (e.g. [█████···]) */
        DOTTED_BACKGROUND(Theme.COLOR_DIM, Theme.CHAR_GRID_DOT);

        final String bgColorCode;
        final String bgChar;

        Style(String bgColorCode, String bgChar)
        {
            this.bgColorCode = bgColorCode;
            this.bgChar = bgChar;
        }
    }

    /**
     * Renders a progress bar string.
     *
     * @param filledLength
     *            The number of active/filled blocks.
     * @param totalLength
     *            The total width of the bar (excluding brackets).
     * @param style
     *            The style preset for the background.
     * @param showBrackets
     *            Whether to wrap the output in '[' and ']'.
     * @return An ANSI-colored string representing the bar.
     */
    public static String render(int filledLength, int totalLength, Style style,
            boolean showBrackets)
    {
        int clampedFilled = Math.max(0, Math.min(totalLength, filledLength));
        StringBuilder sb = new StringBuilder();

        if (showBrackets)
        {
            sb.append(Theme.COLOR_RESET).append("[");
        }

        if (clampedFilled > 0)
        {
            // Amber trail
            if (clampedFilled > 1)
            {
                sb.append(Theme.COLOR_HIGHLIGHT)
                        .append(Theme.CHAR_BLOCK_FULL.repeat(clampedFilled - 1));
            }
            // White peak
            sb.append(Theme.COLOR_RESET).append(Theme.CHAR_BLOCK_FULL);
        }

        // Background
        int bgLength = totalLength - clampedFilled;
        if (bgLength > 0)
        {
            sb.append(style.bgColorCode).append(style.bgChar.repeat(bgLength))
                    .append(Theme.COLOR_RESET);
        }

        if (showBrackets)
        {
            sb.append(Theme.COLOR_RESET).append("]");
        }

        return sb.toString();
    }
}
