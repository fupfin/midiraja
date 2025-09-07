/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the layout negotiation logic for the Midiraja TUI Dashboard. Orchestrates the
 * distribution of available terminal space among various Panels based on a strict priority
 * algorithm.
 */
public class DashboardLayoutManager
{
    /**
     * Enum for identifying the different panels managed by this layout manager.
     */
    public enum PanelId
    {
        METADATA, STATUS, CHANNELS, PLAYLIST, CONTROLS
    }

    /**
     * Calculates the layout constraints for all panels based on the current terminal dimensions.
     *
     * @param termWidth Current width of the terminal.
     * @param termHeight Current height of the terminal.
     * @param listSize Number of items in the current playlist.
     * @return A map of PanelId to LayoutConstraints.
     */
    public Map<PanelId, LayoutConstraints> calculateLayout(int termWidth, int termHeight,
            int listSize)
    {
        Map<PanelId, LayoutConstraints> layout = new HashMap<>();

        // Total static structural overhead lines in DashboardUI:
        // Top banner: 1 line (inverted solid bar)
        // Separator below controls: 1 line
        // (We removed the simple singleLine '-' separators in favor of the '≡≡≡[ TITLE ]≡≡' headers, 
        //  which are embedded within the panel height calculations)
        int staticOverhead = 2;
        int contentHeight = termHeight - staticOverhead;
        
        boolean showPlaylist = listSize > 1;

        int hMetadata = 1;
        int hStatus = 1;
        int hControls = 1;
        int hChannels = 16;
        int hPlaylist = 0;
        boolean useHorizontalChannels = false;
        boolean showHeaders = false;

        // Absolute minimum required contentHeight for Two-Column Layout:
        // hMetadata(1) + hStatus(1) + hChannels(16) + hControls(1) = 19 lines.
        // Plus 2 lines for " [MIDI CHANNELS ACTIVITY]

// header if showHeaders is true = 21 lines.
        if (contentHeight >= 19)
        {
            // Two-Column Mode
            useHorizontalChannels = false;
            hChannels = 16;
            hPlaylist = 16; // Shares height with channels

            showHeaders = contentHeight >= 21;
            int baseRequired = showHeaders ? 21 : 19;
            int surplus = contentHeight - baseRequired;

            // Distribute surplus up to max bounds
            int addStatus = Math.min(surplus, 4); // Max 5
            hStatus += addStatus;
            surplus -= addStatus;

            int addMeta = Math.min(surplus, 2); // Max 3
            hMetadata += addMeta;
            surplus -= addMeta;

            int addControls = Math.min(surplus, 2); // Max 3
            hControls += addControls;
            surplus -= addControls;
        }
        else
        {
            useHorizontalChannels = true;
            hMetadata = 2; // Header + Title
            
            // Require: Meta(2) + Status(1) + ChanHeader(1) + Chan(4) + Controls(1) = 9
            if (contentHeight >= 9) {
                hChannels = 4;
            } else {
                hChannels = 0; // Extremely short terminal
            }

            if (showPlaylist)
            {
                // Playlist requires 1 line for header, plus items
                int requiredBeforePlaylist = 2 + hStatus + (hChannels > 0 ? 1 + hChannels : 0) + hControls; 
                hPlaylist = contentHeight - requiredBeforePlaylist - 1; // -1 for Playlist Header
                if (hPlaylist < 0) hPlaylist = 0;
            }
        }

        layout.put(PanelId.METADATA, new LayoutConstraints(termWidth, hMetadata, showHeaders, false));
        layout.put(PanelId.STATUS, new LayoutConstraints(termWidth, hStatus, showHeaders, false));
        layout.put(PanelId.CONTROLS, new LayoutConstraints(termWidth, hControls, showHeaders, false));
        
        if (useHorizontalChannels)
        {
            layout.put(PanelId.CHANNELS, new LayoutConstraints(termWidth, hChannels, showHeaders, true));
            layout.put(PanelId.PLAYLIST, new LayoutConstraints(termWidth, hPlaylist, showHeaders, false));
        }
        else
        {
            int leftColWidth = Math.max(35, termWidth / 2);
            int rightColWidth = termWidth - leftColWidth;
            layout.put(PanelId.CHANNELS, new LayoutConstraints(leftColWidth, hChannels, showHeaders, false));
            layout.put(PanelId.PLAYLIST, new LayoutConstraints(rightColWidth, hPlaylist, showHeaders, false));
        }

        return layout;
    }
}
