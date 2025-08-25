/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;

/**
 * Responsive VU meter display for 16 MIDI channels.
 * Switches between a 16-row vertical layout and a 4-row horizontal grid
 * depending on available vertical space.
 */
public class ChannelActivityPanel implements Panel
{
    private static final String[] GM_FAMILIES = {"Piano", "Chrom Perc", "Organ", "Guitar", "Bass",
            "Strings", "Ensemble", "Brass", "Reed", "Pipe", "Synth Lead", "Synth Pad", "Synth FX",
            "Ethnic", "Percussive", "SFX"};

    private String getChannelName(int[] channelPrograms, int ch)
    {
        if (ch == 9) return "Drums";
        int family = channelPrograms[ch] / 8;
        if (family >= 0 && family < GM_FAMILIES.length) return GM_FAMILIES[family];
        return "Unknown";
    }

    @Override
    public int calculateHeight(int availableHeight)
    {
        if (availableHeight >= 16) return 16; // Vertical primary layout
        if (availableHeight >= 4) return 4;   // Horizontal grid fallback (4 rows x 4 cols)
        return 0; // Completely hidden if less than 4 lines
    }

    @Override
    public void render(StringBuilder sb, int allocatedWidth, int allocatedHeight,
            PlaybackEngine engine)
    {
        if (allocatedHeight <= 0) return;

        double[] levels = engine.getChannelLevels();
        int[] programs = engine.getChannelPrograms();

        if (allocatedHeight >= 16)
        {
            // Primary vertical layout (Fits in left column or full width)
            int maxMeterLength = Math.max(5, allocatedWidth - 26);

            for (int i = 0; i < 16; i++)
            {
                int meterLength = (int) (levels[i] * maxMeterLength);
                String meter = "█".repeat(meterLength) + " ".repeat(maxMeterLength - meterLength);
                String chName = getChannelName(programs, i);
                String line = String.format("  CH %02d %-11s : %s", i + 1, "(" + chName + ")", meter);
                sb.append(truncate(line, allocatedWidth)).append("
");
            }
        }
        else if (allocatedHeight >= 4)
        {
            // Fallback horizontal grid layout (4 rows, 4 columns)
            // Useful for very squashed terminals
            int colWidth = allocatedWidth / 4;
            int maxMeterLength = Math.max(2, colWidth - 7); // "CH01: [██ ]"

            for (int row = 0; row < 4; row++)
            {
                StringBuilder rowSb = new StringBuilder();
                for (int col = 0; col < 4; col++)
                {
                    int ch = row + (col * 4); // Distribute vertically first for easy reading
                    int meterLength = (int) (levels[ch] * maxMeterLength);
                    String meter = "█".repeat(meterLength) + " ".repeat(maxMeterLength - meterLength);
                    String cell = String.format("C%02d:%s", ch + 1, meter);
                    
                    // Pad cell to exact colWidth
                    if (cell.length() > colWidth) cell = cell.substring(0, colWidth);
                    else cell += " ".repeat(colWidth - cell.length());
                    
                    rowSb.append(cell);
                }
                sb.append(truncate(rowSb.toString(), allocatedWidth)).append("
");
            }
        }
    }
}