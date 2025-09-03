/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;
import com.midiraja.engine.PlaylistContext;
import com.midiraja.io.TerminalIO;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * A rich, full-screen Terminal User Interface (TUI) Dashboard.
 */
public class DashboardUI implements PlaybackUI
{
    private final MetadataPanel metadataPanel = new MetadataPanel();
    private final StatusPanel statusPanel = new StatusPanel();
    private final ChannelActivityPanel channelPanel = new ChannelActivityPanel();
    private final ControlsPanel controlsPanel = new ControlsPanel();
    private final DashboardLayoutManager layoutManager = new DashboardLayoutManager();

    @Override
    public void runRenderLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        if (!term.isInteractive()) return;

        // Wire up listeners
        engine.addPlaybackEventListener(metadataPanel);
        engine.addPlaybackEventListener(statusPanel);
        engine.addPlaybackEventListener(channelPanel);
        engine.addPlaybackEventListener(controlsPanel);

        metadataPanel.updateContext(engine.getContext());
        channelPanel.updatePrograms(engine.getChannelPrograms());

        int lastWidth = -1;
        int lastHeight = -1;

        try
        {
            while (engine.isPlaying())
            {
                int termWidth = term.getWidth();
                int termHeight = term.getHeight();

                if (termWidth != lastWidth || termHeight != lastHeight) {
                    recalculateLayout(termWidth, termHeight, engine.getContext().files().size());
                    lastWidth = termWidth;
                    lastHeight = termHeight;
                }

                // Push latest state that might change externally (non-event polled values)
                statusPanel.updateState(engine.getCurrentMicroseconds(), engine.getTotalMicroseconds(), 
                    engine.getCurrentBpm(), engine.getCurrentSpeed(), engine.getVolumeScale(), 
                    engine.getCurrentTranspose(), engine.getContext());

                StringBuilder sb = new StringBuilder();
                sb.append("\033[H");

                String doubleLine = "=".repeat(termWidth) + "\n";
                String singleLine = "-".repeat(termWidth) + "\n";

                sb.append(doubleLine);
                sb.append("  Midiraja v").append(com.midiraja.Version.VERSION).append(" - Terminal Lover's MIDI Player\n");
                sb.append(doubleLine);

                metadataPanel.render(sb);
                statusPanel.render(sb);
                sb.append(singleLine);

                // For the center content (Channels and Playlist), we still need to coordinate the 2-column or stacked view
                Map<DashboardLayoutManager.PanelId, LayoutConstraints> layout = 
                    layoutManager.calculateLayout(termWidth, termHeight, engine.getContext().files().size());
                
                LayoutConstraints chanC = Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.CHANNELS));
                LayoutConstraints playC = Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.PLAYLIST));

                if (chanC.isHorizontal()) {
                    if (chanC.showHeaders()) sb.append(" [MIDI CHANNELS ACTIVITY]\n");
                    channelPanel.render(sb);
                    if (playC.height() > 0) {
                        sb.append(singleLine);
                        if (playC.showHeaders()) sb.append(" [PLAYLIST]\n\n");
                        renderPlaylist(sb, engine, playC);
                    }
                } else {
                    if (chanC.showHeaders()) {
                        String leftHeader = " [MIDI CHANNELS ACTIVITY]";
                        String rightHeader = engine.getContext().files().size() > 1 ? " [PLAYLIST]" : "";
                        sb.append(String.format("%-" + chanC.width() + "s%s\n\n", leftHeader, rightHeader));
                    }

                    StringBuilder chanSb = new StringBuilder();
                    channelPanel.render(chanSb);
                    String[] chanLines = chanSb.toString().split("\n");

                    StringBuilder playSb = new StringBuilder();
                    if (engine.getContext().files().size() > 1) {
                        renderPlaylist(playSb, engine, playC);
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

                sb.append(singleLine);
                controlsPanel.render(sb);
                sb.append(doubleLine);

                String finalStr = sb.toString().replace("\n", "\033[K\n");
                term.print(finalStr + "\033[J");

                Thread.sleep(50);
            }
        }
        catch (InterruptedException _) {}
    }

    private void recalculateLayout(int width, int height, int listSize) {
        Map<DashboardLayoutManager.PanelId, LayoutConstraints> layout = layoutManager.calculateLayout(width, height, listSize);
        metadataPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.METADATA)));
        statusPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.STATUS)));
        channelPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.CHANNELS)));
        controlsPanel.onLayoutUpdated(Objects.requireNonNull(layout.get(DashboardLayoutManager.PanelId.CONTROLS)));
    }

    private void renderPlaylist(StringBuilder sb, PlaybackEngine engine, LayoutConstraints constraints)
    {
        if (constraints.height() <= 0) return;
        PlaylistContext context = engine.getContext();
        int listSize = context.files().size();
        int idx = context.currentIndex();

        int maxItems = constraints.height();
        int half = maxItems / 2;
        int startIdx = Math.max(0, idx - half);
        int endIdx = Math.min(listSize - 1, startIdx + maxItems - 1);
        startIdx = Math.max(0, endIdx - maxItems + 1);

        for (int i = startIdx; i <= endIdx; i++) {
            String marker = (i == idx) ? " >" : "  ";
            String name = context.files().get(i).getName();
            String status = (i == idx) ? "  (Playing)" : "";
            
            if (name.length() > constraints.width() - status.length() - 8) {
                name = name.substring(0, Math.max(0, constraints.width() - status.length() - 11)) + "...";
            }
            sb.append(String.format(" %s %d. %s%s\n", marker, i + 1, name, status));
        }
    }

    @Override
    public void runInputLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        try {
            while (engine.isPlaying()) {
                var key = term.readKey();
                LineUI.handleCommonInput(engine, key);
            }
        } catch (IOException _) {
            engine.requestStop(PlaybackEngine.PlaybackStatus.QUIT_ALL);
        }
    }
}
