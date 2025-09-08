/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;
import com.midiraja.io.TerminalIO;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DashboardUI implements PlaybackUI
{
    private final NowPlayingPanel nowPlayingPanel = new NowPlayingPanel();
    private final ChannelActivityPanel rawChannelPanel = new ChannelActivityPanel();
    private final ControlsPanel controlsPanel = new ControlsPanel();
    private final PlaylistPanel rawPlaylistPanel = new PlaylistPanel();
    
    private final TitledPanel titledNowPlayingPanel = new TitledPanel("NOW PLAYING", nowPlayingPanel);
    private final TitledPanel channelPanel = new TitledPanel("MIDI CHANNELS", rawChannelPanel);
    private final TitledPanel titledPlaylistPanel = new TitledPanel("PLAYLIST", rawPlaylistPanel);

    private final DashboardLayoutManager layoutManager = new DashboardLayoutManager();

    private List<String> extractExtraMetadata(Sequence seq) {
        List<String> meta = new ArrayList<>();
        // Extract copyright and generic text (ignoring lyrics which are transient, but text could be cool)
        for (Track track : seq.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage msg = track.get(i).getMessage();
                if (msg instanceof javax.sound.midi.MetaMessage m) {
                    if (m.getType() == 0x01 || m.getType() == 0x02) { // Text or Copyright
                        String text = new String(m.getData(), StandardCharsets.US_ASCII).trim();
                        // Ignore garbage binary text
                        if (text.length() > 0 && text.chars().allMatch(c -> c >= 32 && c < 127)) {
                            // Deduplicate
                            if (!meta.contains(text)) meta.add(text);
                        }
                    }
                }
            }
        }
        return meta;
    }

    @Override
    public void runRenderLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        if (!term.isInteractive()) return;

        engine.addPlaybackEventListener(nowPlayingPanel);
        engine.addPlaybackEventListener(rawChannelPanel);
        engine.addPlaybackEventListener(controlsPanel);

        rawPlaylistPanel.updateContext(engine.getContext());
        rawChannelPanel.updatePrograms(engine.getChannelPrograms());
        
        // Let's grab extra metadata for the current track right away
        nowPlayingPanel.setExtraMetadata(extractExtraMetadata(engine.getSequence()));

        int lastWidth = -1;
        int lastHeight = -1;

        try {
            while (engine.isPlaying()) {
                int termWidth = term.getWidth();
                int termHeight = term.getHeight();

                if (termWidth != lastWidth || termHeight != lastHeight) {
                    recalculateLayout(termWidth, termHeight, engine.getContext().files().size());
                    lastWidth = termWidth;
                    lastHeight = termHeight;
                }

                nowPlayingPanel.updateState(engine.getCurrentMicroseconds(), engine.getTotalMicroseconds(), 
                    engine.getCurrentBpm(), engine.getCurrentSpeed(), engine.getVolumeScale(), 
                    engine.getCurrentTranspose(), engine.isPaused(), engine.getContext());

                StringBuilder sb = new StringBuilder();
                sb.append("\033[H");

                String banner = String.format(" Midiraja v%s - Terminal Lover's MIDI Player", com.midiraja.Version.VERSION);
                int bannerPadding = Math.max(0, termWidth - banner.length());
                sb.append("\033[7m").append(banner).append(" ".repeat(bannerPadding)).append("\033[0m\n");

                titledNowPlayingPanel.render(sb);

                Map<DashboardLayoutManager.PanelId, LayoutConstraints> layout = 
                    layoutManager.calculateLayout(termWidth, termHeight, engine.getContext().files().size());
                
                LayoutConstraints chanC = Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.CHANNELS));
                LayoutConstraints playC = Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.PLAYLIST));

                if (chanC.isHorizontal()) {
                    channelPanel.render(sb);
                    if (playC.height() > 0) {
                        titledPlaylistPanel.render(sb);
                    }
                } else {
                    StringBuilder chanSb = new StringBuilder();
                    channelPanel.render(chanSb);
                    String[] chanLines = chanSb.toString().split("\n");

                    StringBuilder playSb = new StringBuilder();
                    if (engine.getContext().files().size() > 1) {
                        titledPlaylistPanel.render(playSb);
                    }
                    String[] playLines = playSb.toString().split("\n");

                    for (int i = 0; i < chanC.height(); i++) {
                        String left = i < chanLines.length ? chanLines[i] : "";
                        if (left.length() > chanC.width()) left = left.substring(0, chanC.width());
                        else left = left + " ".repeat(chanC.width() - left.length());
                        
                        String right = i < playLines.length ? playLines[i] : "";
                        sb.append(left).append(right).append("\n");
                    }
                }

                // Separator above controls
                sb.append(" ").append("≡".repeat(termWidth - 2)).append(" \n");
                controlsPanel.render(sb);
                sb.append("=".repeat(termWidth));

                String finalStr = sb.toString().replace("\n", "\033[K\n");
                term.print(finalStr + "\033[J");

                Thread.sleep(50);
            }
        } catch (InterruptedException _) {}
    }

    private void recalculateLayout(int width, int height, int listSize) {
        Map<DashboardLayoutManager.PanelId, LayoutConstraints> layout = layoutManager.calculateLayout(width, height, listSize);
        titledNowPlayingPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.METADATA)));
        channelPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.CHANNELS)));
        titledPlaylistPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.PLAYLIST)));
        controlsPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.CONTROLS)));
    }

    @Override
    public void runInputLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        try {
            while (engine.isPlaying()) {
                var key = term.readKey();
                switch (key) {
                    case PAUSE -> engine.togglePause();
                    case VOLUME_UP -> engine.adjustVolume(0.05);
                    case VOLUME_DOWN -> engine.adjustVolume(-0.05);
                    case NEXT_TRACK -> engine.requestStop(PlaybackEngine.PlaybackStatus.NEXT);
                    case PREV_TRACK -> engine.requestStop(PlaybackEngine.PlaybackStatus.PREVIOUS);
                    case TRANSPOSE_UP -> engine.adjustTranspose(1);
                    case TRANSPOSE_DOWN -> engine.adjustTranspose(-1);
                    case SPEED_UP -> engine.adjustSpeed(0.1);
                    case SPEED_DOWN -> engine.adjustSpeed(-0.1);
                    case SEEK_FORWARD -> engine.seekRelative(10_000_000);
                    case SEEK_BACKWARD -> engine.seekRelative(-10_000_000);
                    case QUIT -> engine.requestStop(PlaybackEngine.PlaybackStatus.QUIT_ALL);
                    default -> {}
                }
            }
        } catch (IOException _) {
            engine.requestStop(PlaybackEngine.PlaybackStatus.QUIT_ALL);
        }
    }
}
