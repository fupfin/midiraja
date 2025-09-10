package com.midiraja.ui;

import java.util.HashMap;
import java.util.Map;

public class DashboardLayoutManager
{
    public enum PanelId { METADATA, CHANNELS, PLAYLIST, CONTROLS }

    public Map<PanelId, LayoutConstraints> calculateLayout(int termWidth, int termHeight, int listSize)
    {
        Map<PanelId, LayoutConstraints> layout = new HashMap<>();
        boolean showPlaylist = listSize > 1;

        // Safety Margin: Some terminals scroll if we print to the very last character of the last line.
        // We subtract 1 from the total height to be safe.
        int safeHeight = termHeight;

        // Static lines: Top Banner(1) + Bottom Border(1) = 2
        int hNowPlaying = 3; 
        int hControls = 1;   
        int staticOverhead = 2 + 2; // Banner/Bottom + NowPlaying wrapper
        
        int hChannels = 0;
        int hPlaylist = 0;
        boolean isHorizontal = false;

        // Absolute Minimum Constraints Requested by User:
        // 1. NowPlaying: Header(1) + MinContent(2) + Bottom(1) = 4
        int hNowPlayingMin = 4;
        
        // 2. Channels: Header(1) + MinContent(4) + Bottom(1) = 6 (NEVER disappear)
        int hChannelsMin = 6;
        
        // 3. Playlist: Header(1) + MinItems(Math.min(3, listSize)) + Bottom(1)
        int playListContentMin = Math.min(3, listSize);
        int hPlaylistMin = showPlaylist ? (2 + playListContentMin) : 0;
        
        // 4. Controls: 1
        int hControlsMin = 1;
        
        // 5. Static: TopBanner(1) + BottomBorder(1) = 2
        
        // Base starting point is Stacked Mode at Absolute Minimums
        isHorizontal = true;
        hNowPlaying = hNowPlayingMin;
        hChannels = hChannelsMin;
        hPlaylist = hPlaylistMin;
        hControls = hControlsMin;
        
        // Calculate the physical height required just to draw the minimums
        int absoluteMinRequiredHeight = 2 + hNowPlayingMin + hChannelsMin + hPlaylistMin + hControlsMin;

        if (safeHeight < absoluteMinRequiredHeight) {
            // EXTREME SQUEEZE: The terminal is too small.
            // Do NOT hide any panels or shrink them below their minimums!
            // Let the UI render at the absolute minimums and overflow the terminal bounds at the bottom.
            // (The terminal will naturally scroll or clip, which is expected behavior).
            // No height calculation changes needed, just use the minimums initialized above.
        } else {
            // We have enough space. Let's see if we can trigger Two-Column mode.
            // Target Two-Column:
            // Static(2) + NowPlaying(4) + Center(18: Header+16+Bottom) + Controls(1) = 25
            if (safeHeight >= 25) {
                isHorizontal = false;
                hNowPlaying = 4; // 2 content lines
                hChannels = 18;  // 16 content lines
                hPlaylist = showPlaylist ? 18 : 0;
                hControls = 1;
                
                int surplus = safeHeight - 25;
                
                // Distribute surplus logically
                int addNow = Math.min(surplus, 2); // Up to 6 content lines
                hNowPlaying += addNow;
                surplus -= addNow;
                
                int addCtrl = Math.min(surplus, 2); // Up to 3 lines for controls
                hControls += addCtrl;
                surplus -= addCtrl;
                
                int addMeta = 0; // Extra metadata lines
                hNowPlaying += addMeta;
                surplus -= addMeta;
                
                // Infinite remaining to Center
                hChannels += surplus;
                if (showPlaylist) hPlaylist += surplus;
            } else {
                // Comfortable Stacked Mode
                isHorizontal = true;
                
                // We start from minimums. How much surplus do we have to distribute?
                int surplus = safeHeight - absoluteMinRequiredHeight;
                
                // 1. Give NowPlaying up to 4 more lines (Content 6 max in stacked)
                int addNow = Math.min(surplus, 4);
                hNowPlaying += addNow;
                surplus -= addNow;
                
                // 2. Give Playlist the rest! It's better to show more songs.
                if (showPlaylist) {
                    hPlaylist += surplus;
                } else {
                    hChannels += surplus;
                }
            }
        }

        layout.put(PanelId.METADATA, new LayoutConstraints(termWidth, hNowPlaying, true, false));
        layout.put(PanelId.CONTROLS, new LayoutConstraints(termWidth, hControls, false, false));
        
        if (isHorizontal) {
            layout.put(PanelId.CHANNELS, new LayoutConstraints(termWidth, hChannels, true, true));
            layout.put(PanelId.PLAYLIST, new LayoutConstraints(termWidth, hPlaylist, true, false));
        } else {
            int leftColWidth = Math.max(35, termWidth / 2);
            int rightColWidth = termWidth - leftColWidth;
            layout.put(PanelId.CHANNELS, new LayoutConstraints(leftColWidth, hChannels, true, false));
            layout.put(PanelId.PLAYLIST, new LayoutConstraints(rightColWidth, hPlaylist, true, false));
        }
        return layout;
    }
}
