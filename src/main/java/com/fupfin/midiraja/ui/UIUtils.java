/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import org.jline.utils.WCWidth;

public class UIUtils
{
    private UIUtils()
    {
    }

    public static String formatTime(long microseconds, boolean includeHours)
    {
        long totalSeconds = microseconds / 1000000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (includeHours)
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * Returns the number of terminal columns a string occupies, ignoring ANSI escape sequences
     * and using {@link WCWidth#wcwidth} to correctly account for wide (2-column) and ambiguous
     * Unicode characters.
     */
    public static int visibleWidth(String str)
    {
        int width = 0;
        boolean inAnsi = false;
        for (int i = 0; i < str.length();)
        {
            int cp = str.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\033')
            {
                inAnsi = true;
            }
            else if (inAnsi)
            {
                if (cp == 'm' || cp == 'K' || cp == 'J' || cp == 'H'
                        || cp == 'A' || cp == 'l' || cp == 'h')
                    inAnsi = false;
            }
            else if (cp != '\r' && cp != '\n')
            {
                int w = WCWidth.wcwidth(cp);
                if (w > 0)
                    width += w;
            }
        }
        return width;
    }

    public static String truncateAnsi(String str, int maxWidth)
    {
        StringBuilder result = new StringBuilder();
        int visibleCount = 0;
        boolean inAnsi = false;
        for (int i = 0; i < str.length(); i++)
        {
            char c = str.charAt(i);
            if (c == '\033')
            {
                inAnsi = true;
            }

            result.append(c);

            if (inAnsi)
            {
                if (c == 'm' || c == 'K' || c == 'J' || c == 'H' || c == 'A' || c == 'l'
                        || c == 'h')
                {
                    // Primitive check to end ANSI sequences, 'm' is color, others are cursor/screen
                    inAnsi = false;
                }
            }
            else if (c != '\r' && c != '\n')
            {
                visibleCount++;
            }

            if (visibleCount >= maxWidth)
            {
                result.append(Theme.COLOR_RESET);
                break;
            }
        }
        return result.toString();
    }
}
