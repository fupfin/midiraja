package com.fupfin.midiraja.ui;

import static java.lang.Math.*;

import java.util.HashMap;
import java.util.Map;

import com.fupfin.midiraja.ui.LayoutListener.LayoutConstraints;

public class DashboardLayoutManager
{
    public enum PanelId
    {
        METADATA, CHANNELS, PLAYLIST, CONTROLS
    }

    // --- Layout Constants ---
    private static final int TITLED_PANEL_OVERHEAD = 2; // Header (1) + Bottom Border (1)
    private static final int APP_STATIC_OVERHEAD = 2; // Top Banner (1) + Bottom Margin (1)

    private static final int NOW_PLAYING_MIN_CONTENT = 2;
    private static final int NOW_PLAYING_MAX_CONTENT = 6;

    private static final int CHANNELS_MIN_CONTENT = 4;
    private static final int CHANNELS_MAX_CONTENT = 16;

    private static final int PLAYLIST_MIN_CONTENT = 3;

    private static final int CONTROLS_MIN_CONTENT = 1;
    private static final int CONTROLS_MAX_CONTENT = 3;
    // ------------------------

    private int wrapTitled(int contentHeight)
    {
        return contentHeight + TITLED_PANEL_OVERHEAD;
    }

    public Map<PanelId, LayoutConstraints> calculateLayout(int termWidth, int termHeight,
            int listSize)
    {
        Map<PanelId, LayoutConstraints> layout = new HashMap<>();
        boolean showPlaylist = listSize > 1;

        int safeHeight = termHeight - 1; // 1 line margin to prevent auto-scroll

        // 1. Calculate Absolute Minimums Required
        int hNowPlaying = wrapTitled(NOW_PLAYING_MIN_CONTENT);
        int hChannels = wrapTitled(CHANNELS_MIN_CONTENT);
        int hPlaylist = showPlaylist ? wrapTitled(min(PLAYLIST_MIN_CONTENT, listSize)) : 0;
        int hControls = CONTROLS_MIN_CONTENT;

        int absoluteMinHeight = APP_STATIC_OVERHEAD + hNowPlaying + hChannels + hPlaylist + hControls;

        boolean isHorizontal = true; // Default to Stacked

        if (safeHeight >= absoluteMinHeight)
        {
            // 2. Determine Layout Mode based on Two-Column Threshold
            int twoColumnReqHeight = APP_STATIC_OVERHEAD + wrapTitled(NOW_PLAYING_MIN_CONTENT)
                    + wrapTitled(CHANNELS_MAX_CONTENT) + CONTROLS_MIN_CONTENT;

            if (safeHeight >= twoColumnReqHeight && showPlaylist)
            {
                // --- Two-Column Mode ---
                isHorizontal = false;
                hChannels = wrapTitled(CHANNELS_MAX_CONTENT);
                hPlaylist = wrapTitled(CHANNELS_MAX_CONTENT);

                int surplus = safeHeight - twoColumnReqHeight;

                // Distribute surplus
                int addNow = min(surplus, NOW_PLAYING_MAX_CONTENT - NOW_PLAYING_MIN_CONTENT);
                hNowPlaying += addNow;
                surplus -= addNow;

                int addCtrl = min(surplus, CONTROLS_MAX_CONTENT - CONTROLS_MIN_CONTENT);
                hControls += addCtrl;
                surplus -= addCtrl;

                // Remaining to Center
                hChannels += surplus;
                hPlaylist += surplus;
            }
            else
            {
                // --- Stacked Mode ---
                int surplus = safeHeight - absoluteMinHeight;

                int addNow = min(surplus, NOW_PLAYING_MAX_CONTENT - NOW_PLAYING_MIN_CONTENT);
                hNowPlaying += addNow;
                surplus -= addNow;

                int addCtrl = min(surplus, CONTROLS_MAX_CONTENT - CONTROLS_MIN_CONTENT);
                hControls += addCtrl;
                surplus -= addCtrl;

                if (showPlaylist)
                {
                    hPlaylist += surplus;
                }
                else
                {
                    // If no playlist, give half of surplus to metadata and half to channels
                    // but cap metadata at max.
                    int extraNow = min(surplus / 2,
                            NOW_PLAYING_MAX_CONTENT - hNowPlaying + TITLED_PANEL_OVERHEAD);
                    hNowPlaying += extraNow;
                    hChannels += (surplus - extraNow);
                }
            }
        }

        // 3. Output Final Constraints
        layout.put(PanelId.METADATA, new LayoutConstraints(termWidth, hNowPlaying, true, false));
        layout.put(PanelId.CONTROLS, new LayoutConstraints(termWidth, hControls, false, false));

        if (isHorizontal)
        {
            layout.put(PanelId.CHANNELS, new LayoutConstraints(termWidth, hChannels, true, true));
            layout.put(PanelId.PLAYLIST, new LayoutConstraints(termWidth, hPlaylist, true, false));
        }
        else
        {
            int leftColWidth = max(35, termWidth / 2);
            int rightColWidth = termWidth - leftColWidth;
            layout.put(PanelId.CHANNELS,
                    new LayoutConstraints(leftColWidth, hChannels, true, false));
            layout.put(PanelId.PLAYLIST,
                    new LayoutConstraints(rightColWidth, hPlaylist, true, false));
        }
        return layout;
    }
}
