package com.fupfin.midiraja.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.ui.LayoutListener.LayoutConstraints;

class TitledPanelTest
{

    private static final Panel EMPTY_PANEL = new Panel()
    {
        @Override
        public void render(ScreenBuffer b)
        {
        }

        @Override
        public void onLayoutUpdated(LayoutConstraints c)
        {
        }

        @Override
        public void onPlaybackStateChanged()
        {
        }

        @Override
        public void onTick(long m)
        {
        }

        @Override
        public void onTempoChanged(float b)
        {
        }

        @Override
        public void onChannelActivity(int c, int v)
        {
        }
    };

    @Test
    void noTag_rendersTraditionalHeader()
    {
        TitledPanel panel = new TitledPanel("PLAYLIST", EMPTY_PANEL);
        panel.onLayoutUpdated(new LayoutConstraints(30, 1, false, false));
        ScreenBuffer buf = new ScreenBuffer();
        panel.render(buf);
        String line = buf.toString().split("\n")[0];
        // " ≡≡[ PLAYLIST ]" = 16 chars, then 13 ≡, then " " → total 30
        assertTrue(line.startsWith(" ≡≡[ PLAYLIST ]"), "Should start with header: " + line);
        assertTrue(line.endsWith(" "), "Should end with space: " + line);
        assertEquals(30, line.length(), "Line length should be 30: " + line);
    }

    @Test
    void withTag_placesTagBeforeTrailing2EquivAndSpace()
    {
        TitledPanel panel = new TitledPanel("PLAYLIST", EMPTY_PANEL);
        panel.setRightTag("AB", 2); // visible length = 2
        panel.onLayoutUpdated(new LayoutConstraints(30, 1, false, false));
        ScreenBuffer buf = new ScreenBuffer();
        panel.render(buf);
        String line = buf.toString().split("\n")[0];
        // " ≡≡[ PLAYLIST ]" (16) + padding≡ + "AB" + "≡≡" + " " = 30
        // padding = 30 - 16 - 2 - 3 = 9
        assertTrue(line.startsWith(" ≡≡[ PLAYLIST ]"), "Should start with header: " + line);
        assertTrue(line.endsWith("AB≡≡ "), "Should end with AB≡≡ : " + line);
        assertEquals(30, line.length(), "Line length should be 30: " + line);
    }

    @Test
    void withTag_tooNarrow_paddingClampsToZero()
    {
        TitledPanel panel = new TitledPanel("PLAYLIST", EMPTY_PANEL);
        panel.setRightTag("AB", 2);
        // width=18: header=16, tag=2, suffix=3 → padding = 18-16-2-3 = -3 → clamped to 0
        panel.onLayoutUpdated(new LayoutConstraints(18, 1, false, false));
        ScreenBuffer buf = new ScreenBuffer();
        // Should not throw
        assertDoesNotThrow(() -> panel.render(buf));
    }
}
