/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;

/**
 * Responsive status panel showing tempo, progress, transpose, and volume.
 * Supports Min (1 line), Medium (2-3 lines), and Max (5 lines) layouts.
 */
public class StatusPanel implements Panel
{
    private String formatTime(long microseconds, boolean includeHours)
    {
        long totalSeconds = microseconds / 1000000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (includeHours)
        {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        else
        {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    private String buildProgressBar(int percent, int termWidth)
    {
        int barWidth = Math.max(10, termWidth - 40);
        int filled = (int) ((percent / 100.0) * barWidth);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barWidth; i++)
        {
            if (i < filled) bar.append("=");
            else if (i == filled) bar.append(">");
            else bar.append("-");
        }
        bar.append("]");
        return bar.toString();
    }

    @Override
    public int calculateHeight(int availableHeight)
    {
        if (availableHeight >= 5) return 5; // Ideal full size
        if (availableHeight >= 2) return 2; // Medium compact mode
        return 1; // Minimum classic CLI mode
    }

    @Override
    public void render(StringBuilder sb, int allocatedWidth, int allocatedHeight,
            PlaybackEngine engine)
    {
        if (allocatedHeight <= 0) return;

        long totalMicros = engine.getTotalMicroseconds();
        long currentMicros = engine.getCurrentMicroseconds();
        boolean incHrs = (totalMicros / 1000000) >= 3600;

        String totStr = formatTime(totalMicros, incHrs);
        String curStr = formatTime(currentMicros, incHrs);

        int percent = (int) (totalMicros > 0 ? (currentMicros * 100 / totalMicros) : 0);
        percent = Math.min(100, Math.max(0, percent));
        String bar = buildProgressBar(percent, allocatedWidth);

        if (allocatedHeight == 1)
        {
            // 1-line Min mode (similar to LineUI)
            String s = String.format("    %s / %s %s %3d%%  Vol:%d%% Spd:%.1fx Tr:%+d", curStr,
                    totStr, bar, percent, (int) (engine.getVolumeScale() * 100),
                    engine.getCurrentSpeed(), engine.getCurrentTranspose());
            sb.append(truncate(s, allocatedWidth)).append("
");
        }
        else if (allocatedHeight == 2 || allocatedHeight == 3 || allocatedHeight == 4)
        {
            // 2-line Medium mode (compressed into 2 lines, ignore extra padding lines)
            String line1 = String.format("    Time: %s / %s  %s  %3d%%", curStr, totStr, bar,
                    percent);
            String line2 = String.format("    Tempo: %3.0f BPM (%.1fx) | Vol: %d%% | Trans: %+d",
                    engine.getCurrentBpm(), engine.getCurrentSpeed(),
                    (int) (engine.getVolumeScale() * 100), engine.getCurrentTranspose());

            sb.append(truncate(line1, allocatedWidth)).append("
");
            sb.append(truncate(line2, allocatedWidth)).append("
");

            // Pad remaining lines with blank spaces to satisfy allocatedHeight contract
            for (int i = 2; i < allocatedHeight; i++)
            {
                sb.append("
");
            }
        }
        else
        {
            // 5-line Max mode
            sb.append(String.format("    Tempo:     %3.0f BPM  (Speed: %3.1fx)
",
                    engine.getCurrentBpm(), engine.getCurrentSpeed()));
            sb.append(String.format("    Time:      %s / %s  %s  %3d%%
", curStr, totStr, bar,
                    percent));
            sb.append(String.format("    Transpose: %+d
", engine.getCurrentTranspose()));
            sb.append(String.format("    Volume:    %d%%
", (int) (engine.getVolumeScale() * 100)));
            sb.append(String.format("    Port:      [%d] %s
", engine.getContext().targetPort().index(),
                    engine.getContext().targetPort().name()));
        }
    }
}