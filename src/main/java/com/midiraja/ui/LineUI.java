/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;
import com.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.midiraja.io.TerminalIO;
import com.midiraja.io.TerminalIO.TerminalKey;
import java.io.IOException;

public class LineUI implements PlaybackUI
{
    private String formatTime(long microseconds, boolean includeHours) {
        long totalSeconds = microseconds / 1000000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (includeHours) return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void runRenderLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        if (term.isInteractive()) {
            term.print("\033[?25l"); // Hide cursor
        }
        
        com.midiraja.engine.PlaylistContext context = engine.getContext();
        String fileName = context.files().get(context.currentIndex()).getName();
        String title = context.sequenceTitle() != null ? context.sequenceTitle() : "";
        
        // Count how many lines we print so we know how much to jump up to clear on next track
        int staticLinesPrinted = 0;

        term.println(String.format("Midiraja v%s - Terminal Lover's MIDI Player", com.midiraja.Version.VERSION));
        staticLinesPrinted++;
        
        int listSize = context.files().size();
        int idx = context.currentIndex();
        String indexStr = listSize > 1 ? String.format(" [%d/%d]", idx + 1, listSize) : "";
        
        term.println("\033[1;36mPlaying" + indexStr + ":\033[0m " + fileName + "  [Port: " + context.targetPort().name() + "]");
        staticLinesPrinted++;
        
        if (!title.isEmpty()) {
            term.println("  Title: " + title);
            staticLinesPrinted++;
        }
        
        term.println("\033[38;5;244m  Controls: [Spc]Pause [n p]Skip [◀ ▶]Seek [+-]Speed [<>]Trans [▲ ▼]Vol [Q]Quit\033[0m");
        staticLinesPrinted++;
        term.println(""); // Blank line
        staticLinesPrinted++;
        
        String[] blocks = {" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█"};
        
        try {
            while (engine.isPlaying()) {
                // Apply classic decay
                engine.decayChannelLevels(0.15);
                double[] levels = engine.getChannelLevels();

                StringBuilder sb = new StringBuilder();
                sb.append("\r\033[1;36mVol:[\033[0m");
                for (int i = 0; i < 16; i++) {
                    int levelIndex = (int) Math.round(levels[i] * 8);
                    levelIndex = Math.max(0, Math.min(8, levelIndex));
                    // Solid Cyan bars
                    sb.append("\033[36m").append(blocks[levelIndex]).append("\033[0m");
                }
                sb.append("\033[1;36m]\033[0m ");
                
                long totalMicros = engine.getTotalMicroseconds();
                long currentMicros = engine.getCurrentMicroseconds();
                boolean incHrs = (totalMicros / 1000000) >= 3600;
                
                String timeStr = formatTime(currentMicros, incHrs) + " / " + formatTime(totalMicros, incHrs);
                
                sb.append(String.format(" %s (BPM: %5.1f, Vol: %3d%%) ", 
                    timeStr, engine.getCurrentBpm(), (int)(engine.getVolumeScale() * 100)));
                
                // Clear to end of line to prevent ghosting
                sb.append("\033[K");
                
                term.print(sb.toString());
                Thread.sleep(33); // ~30 FPS as in the original
            }
            
            // Playback loop finished (either natural next track or user quit)
            if (term.isInteractive()) {
                // Erase the line UI completely by moving cursor up and clearing
                // Move up 'staticLinesPrinted' times, then clear to end of screen
                term.print("\r");
                for (int i = 0; i < staticLinesPrinted; i++) {
                    term.print("\033[A");
                }
                term.print("\033[J"); // Clear from cursor to end of screen
                term.print("\033[?25h"); // Show cursor
            } else {
                term.println("");
            }
        } catch (InterruptedException _) {
            if (term.isInteractive()) term.print("\033[?25h");
        }
    }

    @Override
    public void runInputLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        try {
            while (engine.isPlaying()) {
                var key = term.readKey();
                handleCommonInput(engine, key);
            }
        } catch (IOException _) {
            engine.requestStop(PlaybackStatus.QUIT_ALL);
        }
    }

    public static void handleCommonInput(PlaybackEngine engine, TerminalKey key)
    {
        switch (key)
        {
            case VOLUME_UP -> engine.adjustVolume(0.05);
            case VOLUME_DOWN -> engine.adjustVolume(-0.05);
            case NEXT_TRACK -> engine.requestStop(PlaybackStatus.NEXT);
            case PREV_TRACK -> engine.requestStop(PlaybackStatus.PREVIOUS);
            case TRANSPOSE_UP -> engine.adjustTranspose(1);
            case TRANSPOSE_DOWN -> engine.adjustTranspose(-1);
            case SPEED_UP -> engine.adjustSpeed(0.1);
            case SPEED_DOWN -> engine.adjustSpeed(-0.1);
            case SEEK_FORWARD -> engine.seekRelative(10_000_000);  // +10 seconds
            case SEEK_BACKWARD -> engine.seekRelative(-10_000_000); // -10 seconds
            case QUIT -> engine.requestStop(PlaybackStatus.QUIT_ALL);
            default -> {}
        }
    }
}