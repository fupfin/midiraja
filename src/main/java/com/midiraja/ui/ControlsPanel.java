/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

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
    public void onPlaybackStateChanged() {}

    @Override
    public void onTick(long currentMicroseconds) {}

    @Override
    public void onTempoChanged(float bpm) {}

    @Override
    public void onChannelActivity(int channel, int velocity) {}

    @Override
    public void render(ScreenBuffer buffer)
    {
        if (constraints.height() <= 0) return;

        String minLine = "[Spc]Pause [▲ ▼]Track [◀ ▶]Seek [+-]Vol [[ ]]Speed [<>]Trans [Q]Quit";
        
        // We removed the "[CONTROLS]" header to save space per user request.
        // As a result, the max needed height is now 2 lines instead of 3.
        if (constraints.height() >= 2)
        {
            buffer.append("[Spc]Pause [n p]Skip [◀ ▶]Seek [+-]Speed [<>]Trans [▲ ▼]Vol [Q]Quit\n");
            buffer.append("[Spc] Pause/Resume  [n p] Skip Track  [◀ ▶] Seek 10s  [Q] Quit\n");
            buffer.append("[▲ ▼] Volume        [+-]  Speed       [< >] Transpose\n");
        }
        else
        {
            buffer.append(truncate(minLine.trim(), constraints.width())).append("\n");
        }
    }
}
