/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import com.fupfin.midiraja.Version;
import com.fupfin.midiraja.engine.PlaybackEngine;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.ui.LayoutListener.LayoutConstraints;
import java.nio.charset.StandardCharsets;
import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;

import java.util.*;
import javax.sound.midi.*;

public class DashboardUI implements PlaybackUI
{
    private final NowPlayingPanel nowPlayingPanel = new NowPlayingPanel();
    private final ChannelActivityPanel rawChannelPanel = new ChannelActivityPanel();
    private final ControlsPanel controlsPanel = new ControlsPanel();
    private final PlaylistPanel rawPlaylistPanel = new PlaylistPanel();

    private final TitledPanel titledNowPlayingPanel =
            new TitledPanel("NOW PLAYING", nowPlayingPanel);
    private final TitledPanel channelPanel = new TitledPanel("MIDI CHANNELS", rawChannelPanel);
    private final TitledPanel titledPlaylistPanel = new TitledPanel("PLAYLIST", rawPlaylistPanel);

    private final DashboardLayoutManager layoutManager = new DashboardLayoutManager();

    private @org.jspecify.annotations.Nullable String extractCopyright(Sequence seq)
    {
        for (Track track : seq.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                MidiMessage msg = track.get(i).getMessage();
                if (msg instanceof MetaMessage m && m.getType() == 0x02)
                {
                    String text = new String(m.getData(), StandardCharsets.US_ASCII).trim();
                    if (!text.isEmpty() && text.chars().allMatch(c -> (c >= 32 && c < 127)
                            || (c >= 160 && c <= 255)))
                        return text;
                }
            }
        }
        return null;
    }

    static String playlistTag(boolean loopActive, boolean shuffleActive)
    {
        String loopIcon    = (loopActive    ? Theme.COLOR_HIGHLIGHT : Theme.COLOR_DIM_FG) + Theme.ICON_LOOP    + Theme.COLOR_RESET;
        String shuffleIcon = (shuffleActive ? Theme.COLOR_HIGHLIGHT : Theme.COLOR_DIM_FG) + Theme.ICON_SHUFFLE + Theme.COLOR_RESET;
        return "(" + loopIcon + shuffleIcon + ")";
    }

    @Override
    public void runRenderLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        if (!term.isInteractive()) return;

        engine.addPlaybackEventListener(nowPlayingPanel);
        engine.addPlaybackEventListener(rawChannelPanel);
        engine.addPlaybackEventListener(controlsPanel);
        engine.addPlaybackEventListener(rawPlaylistPanel);

        rawPlaylistPanel.updateContext(engine.getContext());
        rawChannelPanel.updatePrograms(engine.getChannelPrograms());

        nowPlayingPanel.setCopyright(extractCopyright(engine.getSequence()));
        nowPlayingPanel.setFilterInfo(engine.getFilterDescription());
        nowPlayingPanel.setPortSuffix(engine.getPortSuffix());

        int lastWidth = -1;
        int lastHeight = -1;

        term.print(Theme.TERM_HIDE_CURSOR + Theme.TERM_AUTOWRAP_OFF);
        try
        {
            while (engine.isPlaying())
            {
                int termWidth = term.getWidth();
                int termHeight = term.getHeight();

                if (termWidth != lastWidth || termHeight != lastHeight)
                {
                    recalculateLayout(termWidth, termHeight, engine.getContext().files().size());
                    lastWidth = termWidth;
                    lastHeight = termHeight;
                }

                nowPlayingPanel.updateState(engine.getCurrentMicroseconds(),
                        engine.getTotalMicroseconds(), engine.getCurrentBpm(),
                        engine.getCurrentSpeed(), engine.getVolumeScale(),
                        engine.getCurrentTranspose(), engine.isPaused(), engine.getContext());

                ScreenBuffer buffer = new ScreenBuffer(4096);
                buffer.append(Theme.TERM_CURSOR_HOME);

                String banner = String.format(" MIDIraja v%s - " + Logo.TAGLINE,
                        Version.VERSION);
                String savedTag = engine.isBookmarked() ? "[Saved] " : "";
                int bannerPadding = max(0, termWidth - banner.length() - savedTag.length());
                buffer.append(Theme.FORMAT_INVERT).append(banner)
                        .append(" ".repeat(bannerPadding)).append(savedTag)
                        .append("\033[0m\n");

                String tag = playlistTag(engine.isLoopEnabled(), engine.isShuffleEnabled());
                titledPlaylistPanel.setRightTag(tag, UIUtils.visibleWidth(tag));
                titledNowPlayingPanel.render(buffer);

                Map<DashboardLayoutManager.PanelId, LayoutConstraints> layout = layoutManager
                        .calculateLayout(termWidth, termHeight, engine.getContext().files().size());

                LayoutConstraints chanC =
                        requireNonNull(layout.get(DashboardLayoutManager.PanelId.CHANNELS));
                LayoutConstraints playC =
                        requireNonNull(layout.get(DashboardLayoutManager.PanelId.PLAYLIST));

                if (chanC.isHorizontal())
                {
                    channelPanel.render(buffer);
                    if (playC.height() > 0)
                    {
                        titledPlaylistPanel.render(buffer);
                    }
                }
                else
                {
                    ScreenBuffer chanSb = new ScreenBuffer();
                    channelPanel.render(chanSb);
                    String[] chanLines = chanSb.toString().split("\n", -1);

                    ScreenBuffer playSb = new ScreenBuffer();
                    if (engine.getContext().files().size() > 1)
                    {
                        titledPlaylistPanel.render(playSb);
                    }
                    String[] playLines = playSb.toString().split("\n", -1);

                    for (int i = 0; i < chanC.height(); i++)
                    {
                        String left = i < chanLines.length ? chanLines[i] : "";
                        String right = i < playLines.length ? playLines[i] : "";
                        buffer.append(left).append(right).append("\n");
                    }
                }

                // Controls Panel (The bottom border of Channels/Playlist acts as the separator
                // above)
                controlsPanel.render(buffer);
                // No trailing newline on the bottom border to prevent scrolling
                buffer.append("=".repeat(termWidth));

                String finalStr = buffer.toString().replace("\n", "\033[K\n");
                // [J clears from the current cursor position to the end of the screen.
                // Since we are using termHeight - 1, this will cleanly erase any previous artifacts
                // at the bottom.
                term.print(finalStr + Theme.TERM_CLEAR_TO_END);

                Thread.sleep(50);
            }
        }
        catch (InterruptedException ignored)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            term.print(Theme.TERM_SHOW_CURSOR + Theme.TERM_AUTOWRAP_ON);
        }
    }

    private void recalculateLayout(int width, int height, int listSize)
    {
        Map<DashboardLayoutManager.PanelId, LayoutConstraints> layout =
                layoutManager.calculateLayout(width, height, listSize);
        titledNowPlayingPanel.onLayoutUpdated(
                requireNonNull(layout.get(DashboardLayoutManager.PanelId.METADATA)));
        channelPanel.onLayoutUpdated(
                requireNonNull(layout.get(DashboardLayoutManager.PanelId.CHANNELS)));
        titledPlaylistPanel.onLayoutUpdated(
                requireNonNull(layout.get(DashboardLayoutManager.PanelId.PLAYLIST)));
        controlsPanel.onLayoutUpdated(
                requireNonNull(layout.get(DashboardLayoutManager.PanelId.CONTROLS)));
    }

    @Override
    public void runInputLoop(PlaybackEngine engine)
    {
        InputLoopRunner.run(engine, InputHandler::handleCommonInput);
    }

    @Override
    public void suspend(com.fupfin.midiraja.io.TerminalIO term, boolean altScreen)
    {
        // Exit alt screen so the shell is visible while the process is stopped.
        if (altScreen) term.print(Theme.TERM_ALT_SCREEN_DISABLE);
        term.print(Theme.TERM_SHOW_CURSOR + Theme.TERM_AUTOWRAP_ON);
    }

    @Override
    public void resume(com.fupfin.midiraja.io.TerminalIO term, boolean altScreen)
    {
        // Re-enter alt screen; the render loop will redraw on the next tick.
        if (altScreen) term.print(Theme.TERM_ALT_SCREEN_ENABLE);
        term.print(Theme.TERM_HIDE_CURSOR + Theme.TERM_AUTOWRAP_OFF);
    }
}
