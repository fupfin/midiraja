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
    private static final String[] GM_FAMILIES = {"Piano", "Chrom Perc", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass", "Reed", "Pipe", "Synth Lead", "Synth Pad", "Synth FX", "Ethnic", "Percussive", "SFX"};

    private LayoutConstraints constraints = new LayoutConstraints(80, 16, false, false);
    private final double[] channelLevels = new double[16];
    private final int[] channelPrograms = new int[16];

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds)
    {
        this.constraints = bounds;
    }

    @Override
    public void onPlaybackStateChanged() {}

    @Override
    public void onTick(long currentMicroseconds) {}

    @Override
    public void onTempoChanged(float bpm) {}

    @Override
    public void onChannelActivity(int channel, int velocity)
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
        if (ch == 9) return "Drums";
        int family = channelPrograms[ch] / 8;
        if (family >= 0 && family < GM_FAMILIES.length) return GM_FAMILIES[family];
        return "Unknown";
    }

    @Override
    public void render(ScreenBuffer buffer)
    {
        if (constraints.height() <= 0) return;

        // Internal Decay Logic
        for (int i = 0; i < 16; i++) {
            channelLevels[i] = Math.max(0, channelLevels[i] - 0.05);
        }

        if (!constraints.isHorizontal() && constraints.height() >= 16)
        {
            int maxMeterLength = Math.max(5, constraints.width() - 26);
            for (int i = 0; i < 16; i++)
            {
                int meterLength = (int) (channelLevels[i] * maxMeterLength);
                String meter = Theme.COLOR_AMBER + Theme.CHAR_BLOCK_FULL.repeat(meterLength) + Theme.COLOR_RESET + " ".repeat(maxMeterLength - meterLength);
                String line = String.format("CH %02d %-11s : %s", i + 1, "(" + getChannelName(i) + ")", meter);
                buffer.append(truncate(line, constraints.width())).append("\n");
            }
        }
        else if (constraints.height() >= 4)
        {
            int colWidth = constraints.width() / 4;
            int maxMeterLength = Math.max(2, colWidth - 7);
            for (int row = 0; row < 4; row++)
            {
                StringBuilder rowSb = new StringBuilder();
                for (int col = 0; col < 4; col++)
                {
                    int ch = row + (col * 4);
                    int meterLength = (int) (channelLevels[ch] * maxMeterLength);
                    String meter = Theme.COLOR_AMBER + Theme.CHAR_BLOCK_FULL.repeat(meterLength) + Theme.COLOR_RESET + " ".repeat(maxMeterLength - meterLength);
                    String cell = String.format("C%02d:%s", ch + 1, meter);
                    if (cell.length() > colWidth) cell = cell.substring(0, colWidth);
                    else cell += " ".repeat(colWidth - cell.length());
                    rowSb.append(cell);
                }
                buffer.append(truncate(rowSb.toString(), constraints.width())).append("\n");
            }
        }
    }
}
