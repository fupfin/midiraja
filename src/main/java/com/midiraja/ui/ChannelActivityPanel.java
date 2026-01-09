/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import java.util.Arrays;

/**
 * Responsive VU meter display for 16 MIDI channels.
 */
public class ChannelActivityPanel implements Panel
{
    private static final String[] GM_FAMILIES = {"Piano", "Chrom Perc", "Organ", "Guitar", "Bass",
        "Strings", "Ensemble", "Brass", "Reed", "Pipe", "Synth Lead", "Synth Pad", "Synth FX",
        "Ethnic", "Percussive", "SFX"};

    private LayoutConstraints constraints = new LayoutConstraints(80, 16, false, false);
    private final double[] channelLevels = new double[16];
    private final int[] channelPrograms = new int[16];

    @Override public void onLayoutUpdated(LayoutConstraints bounds)
    {
        this.constraints = bounds;
    }

    @Override public void onPlaybackStateChanged()
    {
    }

    @Override public void onTick(long currentMicroseconds)
    {
    }

    @Override public void onTempoChanged(float bpm)
    {
    }

    @Override public void onChannelActivity(int channel, int velocity)
    {
        if (channel >= 0 && channel < 16)
        {
            channelLevels[channel] = Math.max(channelLevels[channel], velocity / 127.0);
        }
    }

    public void updatePrograms(int[] programs)
    {
        System.arraycopy(programs, 0, channelPrograms, 0, 16);
    }

    private String getChannelName(int ch)
    {
        if (ch == 9)
            return "Drums";
        int family = channelPrograms[ch] / 8;
        if (family >= 0 && family < GM_FAMILIES.length)
            return GM_FAMILIES[family];
        return "Unknown";
    }

    @Override public void render(ScreenBuffer buffer)
    {
        if (constraints.height() <= 0)
            return;

        for (int i = 0; i < 16; i++)
        {
            channelLevels[i] = Math.max(0, channelLevels[i] - 0.05);
        }

        int w = constraints.width();
        int h = constraints.height();

        // Determine optimal number of columns based on available space
        int numCols;
        if (h >= 16 && w < 80) {
            numCols = 1; // Lots of vertical space, not enough horizontal -> 1 Column
        } else if (h >= 8 && w >= 60) {
            numCols = 2; // Good amount of both -> 2 Columns
        } else if (h >= 4 && w >= 40) {
            numCols = 4; // Not enough vertical, but enough horizontal -> 4 Columns
        } else if (h >= 16) {
            numCols = 1; // Fallback for very tall but somehow not caught
        } else {
            numCols = 4; // Absolute fallback for small screens (crammed)
        }

        // If layout manager gave us less than needed rows, clip it gracefully
        int numRows = (int) Math.ceil(16.0 / numCols);
        int rowsToDraw = Math.min(numRows, h);

        int colWidth = w / numCols;

        for (int r = 0; r < rowsToDraw; r++)
        {
            StringBuilder rowSb = new StringBuilder();
            for (int c = 0; c < numCols; c++)
            {
                int ch = r + (c * numRows);
                if (ch >= 16) break;

                String cell;
                if (numCols == 4) {
                    // 4-Column: Compact (No instrument name)
                    int maxMeter = Math.max(2, colWidth - 7);
                    int meterLen = (int) (channelLevels[ch] * maxMeter);
                    String meter = Theme.COLOR_HIGHLIGHT + Theme.CHAR_BLOCK_FULL.repeat(meterLen)
                        + Theme.COLOR_RESET + " ".repeat(maxMeter - meterLen);
                    cell = String.format("C%02d:%s", ch + 1, meter);
                    
                    int visibleLen = 4 + maxMeter;
                    int padding = Math.max(0, colWidth - visibleLen);
                    cell += " ".repeat(padding);
                } else {
                    // 1 or 2-Column: Rich (Includes instrument name)
                    String instName = getChannelName(ch);
                    if (instName.length() > 11) {
                        instName = instName.substring(0, 11);
                    }
                    // Format: "CH 01 (Piano      ) : [meter]"
                    int prefixLen = 22; // length of "CH 01 (Piano      ) : "
                    int maxMeter = Math.max(2, colWidth - prefixLen - 2); 
                    int meterLen = (int) (channelLevels[ch] * maxMeter);
                    String meter = Theme.COLOR_HIGHLIGHT + Theme.CHAR_BLOCK_FULL.repeat(meterLen)
                        + Theme.COLOR_RESET + " ".repeat(maxMeter - meterLen);
                        
                    cell = String.format("CH %02d %-13s: %s", ch + 1, "(" + instName + ")", meter);
                    
                    int visibleLen = 20 + maxMeter; 
                    int padding = Math.max(0, colWidth - visibleLen);
                    cell += " ".repeat(padding);
                }
                
                // For safety, hard-truncate if string calculation overshoots
                // (ansi codes make length calculation tricky, but our padding logic is based on visible chars)
                rowSb.append(cell);
            }
            buffer.append(truncate(rowSb.toString(), w)).append("\n");
        }
    }
}
