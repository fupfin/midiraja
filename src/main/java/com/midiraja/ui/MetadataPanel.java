/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;
import com.midiraja.engine.PlaylistContext;

/**
 * Renders the song title, copyright, and global file metadata.
 * Scales dynamically based on allowed vertical space.
 */
public class MetadataPanel implements Panel
{
    @Override
    public int calculateHeight(int availableHeight)
    {
        // Max height required: 3 lines
        // 1. [NOW PLAYING] Header
        // 2. Blank space
        // 3. Title row
        return Math.min(availableHeight, 3);
    }

    @Override
    public void render(StringBuilder sb, int allocatedWidth, int allocatedHeight,
            PlaybackEngine engine)
    {
        if (allocatedHeight <= 0) return;

        PlaylistContext context = engine.getContext();
        String rawTitle = context.sequenceTitle() != null ? context.sequenceTitle()
                : context.files().get(context.currentIndex()).getName();

        if (allocatedHeight == 1)
        {
            // Ultra-compressed
            sb.append(truncate("  [NOW] " + rawTitle, allocatedWidth)).append("
");
        }
        else if (allocatedHeight == 2)
        {
            sb.append("  [NOW PLAYING]
");
            sb.append(String.format("    Title:     %s
", truncate(rawTitle, allocatedWidth - 16)));
        }
        else
        {
            // Full 3 lines
            sb.append("  [NOW PLAYING]

");
            sb.append(String.format("    Title:     %s
", truncate(rawTitle, allocatedWidth - 16)));
        }
    }
}