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

/**
 * A rich, full-screen Terminal User Interface (TUI) Dashboard.
 * Implements a dynamic layout manager to orchestrate modular Panels.
 */
public class DashboardUI implements PlaybackUI
{
    private final MetadataPanel metadataPanel = new MetadataPanel();
    private final StatusPanel statusPanel = new StatusPanel();
    private final ChannelActivityPanel channelPanel = new ChannelActivityPanel();
    private final ControlsPanel controlsPanel = new ControlsPanel();

    @Override
    public void runRenderLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        if (!term.isInteractive())
        {
            term.println("Playing (Interactive UI disabled)...");
            return;
        }

        try
        {
            while (engine.isPlaying())
            {
                engine.decayChannelLevels(0.05);

                int termWidth = term.getWidth();
                int termHeight = term.getHeight();

                StringBuilder sb = new StringBuilder();
                sb.append("\033[H"); // Cursor Home

                String doubleLine = "=".repeat(termWidth) + "
";
                String singleLine = "-".repeat(termWidth) + "
";

                // Layout Negotiation Priority Algorithm
                int contentHeight = termHeight - 4; // Subtract 4 for the horizontal separator lines
                int hMetadata = 3;
                int hStatus = 5;
                int hControls = 3;
                int hChannels = 16;
                boolean useHorizontalChannels = false;

                // Step 1: Assume 16-line vertical channels and max other panels. Calculate remainder for Playlist.
                int usedHeight = hMetadata + hStatus + hControls + hChannels;
                int hPlaylist = contentHeight - usedHeight;

                if (hPlaylist < 0)
                {
                    // Step 2: Try falling back to horizontal channels
                    hChannels = 4;
                    usedHeight = hMetadata + hStatus + hControls + hChannels;
                    hPlaylist = contentHeight - usedHeight;
                    useHorizontalChannels = true;

                    if (hPlaylist < 0)
                    {
                        // Squeeze Status and Controls
                        hStatus = statusPanel.calculateHeight(contentHeight - hMetadata - hChannels - 1);
                        hControls = controlsPanel.calculateHeight(contentHeight - hMetadata - hChannels - hStatus - 1);
                        usedHeight = hMetadata + hStatus + hControls + hChannels;
                        hPlaylist = contentHeight - usedHeight;

                        if (hPlaylist < 0)
                        {
                            // Step 3: Absolute minimum clamp for everything
                            hMetadata = 1;
                            hStatus = 1;
                            hControls = 1;
                            hChannels = 4;
                            hPlaylist = 0; // Hide playlist entirely
                        }
                    }
                }

                // --- RENDERING PHASE ---

                // Top Header
                sb.append(doubleLine);
                sb.append("  Midiraja v").append(com.midiraja.Version.VERSION).append(" - Java 25 Native MIDI Player
");
                sb.append(doubleLine);

                // Meta & Status
                metadataPanel.render(sb, termWidth, hMetadata, engine);
                statusPanel.render(sb, termWidth, hStatus, engine);
                sb.append(singleLine);

                // Center Content (Channels and Playlist)
                if (useHorizontalChannels)
                {
                    // Stack them vertically if channels are horizontal
                    sb.append(" [MIDI CHANNELS ACTIVITY]
");
                    channelPanel.render(sb, termWidth, hChannels, engine);
                    if (hPlaylist > 0)
                    {
                        sb.append(singleLine);
                        renderPlaylist(sb, engine, termWidth, hPlaylist);
                    }
                }
                else
                {
                    // Two-Column Layout (Left: Channels, Right: Playlist)
                    int leftColWidth = Math.max(35, termWidth / 2);
                    int rightColWidth = termWidth - leftColWidth;

                    String leftHeader = " [MIDI CHANNELS ACTIVITY]";
                    String rightHeader = " [PLAYLIST]";
                    sb.append(String.format("%-" + leftColWidth + "s%s

", leftHeader, rightHeader));

                    StringBuilder channelSb = new StringBuilder();
                    channelPanel.render(channelSb, leftColWidth, hChannels, engine);
                    String[] channelLines = channelSb.toString().split("
");

                    StringBuilder playlistSb = new StringBuilder();
                    renderPlaylist(playlistSb, engine, rightColWidth, hChannels); // Use same height as channels
                    String[] playlistLines = playlistSb.toString().split("
");

                    for (int i = 0; i < 16; i++)
                    {
                        String leftStr = i < channelLines.length ? channelLines[i] : "";
                        if (leftStr.length() > leftColWidth) leftStr = leftStr.substring(0, leftColWidth);
                        else leftStr = leftStr + " ".repeat(leftColWidth - leftStr.length());
                        
                        String rightStr = i < playlistLines.length ? playlistLines[i] : "";
                        sb.append(leftStr).append(rightStr).append("
");
                    }
                }

                // Controls
                sb.append(singleLine);
                controlsPanel.render(sb, termWidth, hControls, engine);
                sb.append(doubleLine);

                // Pad with empty lines to clear any ghosting, then clear to end of screen
                String finalStr = sb.toString().replace("
", "\033[K
");
                term.print(finalStr + "\033[J");

                Thread.sleep(50); // 20 FPS
            }
        }
        catch (InterruptedException _)
        {
        }
    }

    private void renderPlaylist(StringBuilder sb, PlaybackEngine engine, int width, int height)
    {
        if (height <= 2) return; // Need space for at least 1 item and padding

        PlaylistContext context = engine.getContext();
        int listSize = context.files().size();
        int idx = context.currentIndex();

        int maxItems = height - 2; // Leave room for padding
        int half = maxItems / 2;
        int startIdx = Math.max(0, idx - half);
        int endIdx = Math.min(listSize - 1, startIdx + maxItems - 1);
        startIdx = Math.max(0, endIdx - maxItems + 1);

        for (int i = startIdx; i <= endIdx; i++)
        {
            String marker = (i == idx) ? " >" : "  ";
            String name = context.files().get(i).getName();
            String status = (i == idx) ? "  (Playing)" : "";
            
            // Re-use panel truncate utility via a dummy instantiation or static helper
            if (name.length() > width - status.length() - 8) {
                name = name.substring(0, Math.max(0, width - status.length() - 11)) + "...";
            }
            sb.append(String.format(" %s %d. %s%s
", marker, i + 1, name, status));
        }
    }

    @Override
    public void runInputLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        try
        {
            while (engine.isPlaying())
            {
                var key = term.readKey();
                LineUI.handleCommonInput(engine, key);
            }
        }
        catch (IOException | InterruptedException _)
        {
            engine.requestStop(PlaybackEngine.PlaybackStatus.QUIT_ALL);
        }
    }
}