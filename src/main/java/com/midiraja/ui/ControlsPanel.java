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
    public void render(StringBuilder sb)
    {
        if (constraints.height() <= 0) return;

        if (constraints.height() >= 3)
        {
            if (constraints.showHeaders()) sb.append(" [CONTROLS]\n");
            sb.append("  [Spc] Pause/Resume  |  [n] [p] Skip Track    |  [◀] [▶] Seek 10s\n");
            sb.append("  [▲] [▼] Volume      |  [+] [-] Speed         |  [<] [>] Transpose\n");
            sb.append("  [Q] Quit            |\n");
        }
        else
        {
            String minLine = "  [Spc]Pause [n p]Skip [◀ ▶]Seek [+-]Speed [<>]Trans [▲ ▼]Vol [Q]Quit";
            if (constraints.showHeaders() && constraints.height() >= 2)
            {
                sb.append(" [CONTROLS]\n");
                sb.append(truncate(minLine, constraints.width())).append("\n");
            }
            else
            {
                sb.append(truncate(minLine, constraints.width())).append("\n");
            }
        }
    }
}
