package com.fupfin.midiraja.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.LayoutListener.LayoutConstraints;

class NowPlayingPanelTest
{
    private NowPlayingPanel panel;
    private PlaylistContext ctx;

    @BeforeEach
    void setUp()
    {
        panel = new NowPlayingPanel();
        ctx = new PlaylistContext(
                List.of(new File("track.mid")), 0,
                new MidiPort(0, "Test Port"), "Test Song", false, false);
        panel.updateState(30_000_000L, 120_000_000L, 120f, 1.0, 1.0, 0, true, false, ctx);
    }

    @Test
    void height4_renders4Lines()
    {
        panel.onLayoutUpdated(new LayoutConstraints(80, 4, false, false));
        String out = render(panel);
        String[] lines = out.split("\n");
        assertEquals(4, countNonEmpty(lines));
        assertTrue(lines[0].contains("Title:"));
        assertTrue(lines[1].contains("Time:"));
        assertTrue(lines[2].contains("Port:"));
        assertTrue(lines[3].contains("Vol:"));
        assertTrue(lines[3].contains("Tempo:"));
        assertTrue(lines[3].contains("Trans:"));
    }

    @Test
    void height5_withCopyright_renders5Lines()
    {
        panel.setCopyright("© 1988 Falcom");
        panel.onLayoutUpdated(new LayoutConstraints(80, 5, false, false));
        String out = render(panel);
        String[] lines = out.split("\n");
        assertEquals(5, countNonEmpty(lines));
        // New layout: Title, Time, Port, Settings, Copyright (copyright always last)
        assertTrue(lines[4].contains("1988 Falcom"));
    }

    @Test
    void height5_withoutCopyright_noCopyrightLineShown()
    {
        panel.onLayoutUpdated(new LayoutConstraints(80, 5, false, false));
        String out = render(panel);
        assertFalse(out.contains("Copyright:"));
        assertEquals(4, countNonEmpty(out.split("\n")));
    }

    @Test
    void settingsLine_formatsCorrectly()
    {
        panel.onLayoutUpdated(new LayoutConstraints(80, 4, false, false));
        String out = render(panel);
        assertTrue(out.contains("Vol:"));
        assertTrue(out.contains("BPM"));
        assertTrue(out.contains("Trans:"));
    }

    private String render(NowPlayingPanel p)
    {
        var buf = new ScreenBuffer(1024);
        p.render(buf);
        return buf.toString();
    }

    private int countNonEmpty(String[] lines)
    {
        int count = 0;
        for (String l : lines)
        {
            if (!l.isBlank())
            {
                count++;
            }
        }
        return count;
    }
}
