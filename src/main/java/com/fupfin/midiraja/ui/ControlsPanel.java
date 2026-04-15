/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

/**
 * Displays user control shortcuts.
 */
public class ControlsPanel implements Panel
{
    private LayoutConstraints constraints = new LayoutConstraints(80, 1, false, false);

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds)
    {
        this.constraints = bounds;
    }

    @Override
    public void onPlaybackStateChanged()
    {
    }

    @Override
    public void onTick(long currentMicroseconds)
    {
    }

    @Override
    public void onTempoChanged(float bpm)
    {
    }

    @Override
    public void onChannelActivity(int channel, int velocity)
    {
    }

    @Override
    public void render(ScreenBuffer buffer)
    {
        if (constraints.height() <= 0)
            return;

        String minLine = "[Spc]Pause [▲▼]Skip [◀▶]Seek [+-]Vol [<>]Tempo [/']Trans [L]Loop [S]Shuf [V]Meter [*]Save [Q]Quit";

        if (constraints.height() >= 3)
        {
            buffer.append("[Spc] Pause  [◀ ▶] Seek  [▲ ▼] Skip  [+-] Vol  [< >] Tempo  [/ '] Trans\n");
            buffer.append("[L] Loop  [S] Shuffle  [V] Meter  [*] Save  [R] Resume Session  [Q] Quit\n");
        }
        else if (constraints.height() == 2)
        {
            buffer.append(
                    "[Spc]Pause [◀▶]Seek [▲▼]Skip [+-]Vol [<>]Tempo [/']Trans [L]Loop [S]Shuf [V]Meter [*]Bkm [R]Resume [Q]Quit\n");
        }
        else
        {
            buffer.append(truncate(minLine.trim(), constraints.width())).append("\n");
        }
    }
}
