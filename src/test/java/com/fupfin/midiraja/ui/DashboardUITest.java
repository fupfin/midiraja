package com.fupfin.midiraja.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardUITest {
    @Test void playlistTitle_noneActive() {
        assertEquals("PLAYLIST", DashboardUI.playlistTitle(false, false));
    }
    @Test void playlistTitle_loopOnly() {
        assertEquals("PLAYLIST \u21A9", DashboardUI.playlistTitle(true, false));
    }
    @Test void playlistTitle_shuffleOnly() {
        assertEquals("PLAYLIST \u21C4", DashboardUI.playlistTitle(false, true));
    }
    @Test void playlistTitle_both() {
        assertEquals("PLAYLIST \u21A9 \u21C4", DashboardUI.playlistTitle(true, true));
    }
}
