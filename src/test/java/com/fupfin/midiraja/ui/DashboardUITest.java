package com.fupfin.midiraja.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DashboardUITest
{
    @Test
    void playlistTag_bothOff_bothDim()
    {
        String tag = DashboardUI.playlistTag(false, false);
        assertTrue(tag.contains(Theme.COLOR_DIM_FG + Theme.ICON_LOOP));
        assertTrue(tag.contains(Theme.COLOR_DIM_FG + Theme.ICON_SHUFFLE));
        assertFalse(tag.contains(Theme.COLOR_HIGHLIGHT + Theme.ICON_LOOP));
        assertFalse(tag.contains(Theme.COLOR_HIGHLIGHT + Theme.ICON_SHUFFLE));
    }

    @Test
    void playlistTag_loopOnly_loopAmber()
    {
        String tag = DashboardUI.playlistTag(true, false);
        assertTrue(tag.contains(Theme.COLOR_HIGHLIGHT + Theme.ICON_LOOP));
        assertTrue(tag.contains(Theme.COLOR_DIM_FG + Theme.ICON_SHUFFLE));
    }

    @Test
    void playlistTag_shuffleOnly_shuffleAmber()
    {
        String tag = DashboardUI.playlistTag(false, true);
        assertTrue(tag.contains(Theme.COLOR_DIM_FG + Theme.ICON_LOOP));
        assertTrue(tag.contains(Theme.COLOR_HIGHLIGHT + Theme.ICON_SHUFFLE));
    }

    @Test
    void playlistTag_both_bothAmber()
    {
        String tag = DashboardUI.playlistTag(true, true);
        assertTrue(tag.contains(Theme.COLOR_HIGHLIGHT + Theme.ICON_LOOP));
        assertTrue(tag.contains(Theme.COLOR_HIGHLIGHT + Theme.ICON_SHUFFLE));
    }
}
