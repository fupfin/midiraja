/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaylistContext;
import org.jspecify.annotations.Nullable;

/**
 * Renders the song title and global file metadata.
 */
public class MetadataPanel implements Panel
{
    private LayoutConstraints constraints = new LayoutConstraints(80, 1, false, false);
    @Nullable private String title;

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

    public void updateContext(PlaylistContext context)
    {
        this.title = context.sequenceTitle() != null ? context.sequenceTitle()
                : context.files().get(context.currentIndex()).getName();
    }

    @Override
    public void render(StringBuilder sb)
    {
        if (constraints.height() <= 0) return;
        String rawTitle = title != null ? title : "Unknown";

        if (constraints.height() == 1)
        {
            sb.append(truncate("  " + rawTitle, constraints.width())).append("\n");
        }
        else
        {
            // Always show headers if height > 1, no blank line below it.
            String header = "≡≡≡[ NOW PLAYING ]";
            int padding = Math.max(0, constraints.width() - header.length());
            sb.append(header).append("≡".repeat(padding)).append("\n");
            sb.append(String.format("    Title:     %s\n", truncate(rawTitle, constraints.width() - 16)));
        }
    }
}
