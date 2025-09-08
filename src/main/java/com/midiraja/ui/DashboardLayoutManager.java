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

        // Static lines: Top Banner(1) + Bottom Border(1) = 2
        int hNowPlaying = 3; // Minimum 3 lines for NowPlaying content
        int hControls = 1;   // Minimum 1 line for Controls
        int staticOverhead = 2 + 2; // 2 for Top/Bottom + 2 for NowPlaying wrapper (TitledPanel)
        
        int hChannels = 0;
        int hPlaylist = 0;
        boolean isHorizontal = false;

        // Target Two-Column requires:
        // Struct(4) + NowPlayingMin(3) + Center(18: 16+TitledOverhead2) + Controls(1) = 26
        if (termHeight >= 26) {
            isHorizontal = false;
            hChannels = 18;
            hPlaylist = 18;
            
            int surplus = termHeight - 26;
            
            // 1. Give NowPlaying up to 6 lines (+3)
            int addNow = Math.min(surplus, 3);
            hNowPlaying += addNow;
            surplus -= addNow;
            
            // 2. Give Controls up to 3 lines (+2)
            int addControls = Math.min(surplus, 2);
            hControls += addControls;
            surplus -= addControls;
            
            // 3. Give remaining to extra metadata or channels? Extra metadata!
            hNowPlaying += surplus; // Any remaining huge terminal space goes to metadata text
            
        } else {
            isHorizontal = true; // Stacked Mode
            // Calculate available lines for center blocks
            // Center = termHeight - 4 (struct) - hNowPlaying(3) - hControls(1) = termHeight - 8
            int centerSpace = termHeight - 8;
            
            if (centerSpace >= 6) { // Enough for Channels block (Header+4+Bottom=6)
                hChannels = 6;
                if (showPlaylist) {
                    hPlaylist = Math.max(0, centerSpace - 6); 
                }
            } else {
                hChannels = 0;
                if (showPlaylist) {
                    hPlaylist = Math.max(0, centerSpace);
                }
            }
            
            // If we have extra space inside center, distribute to NowPlaying or Controls?
            // Wait, we just gave all to hPlaylist. If hPlaylist > 6, maybe steal some for NowPlaying?
            if (hPlaylist > 8) {
                int steal = Math.min(3, hPlaylist - 8);
                hPlaylist -= steal;
                hNowPlaying += steal;
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
