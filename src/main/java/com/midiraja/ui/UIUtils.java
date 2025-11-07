/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

public class UIUtils
{
    private UIUtils() {}

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
