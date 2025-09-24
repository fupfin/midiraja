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


    private String truncateAnsi(String str, int maxWidth) {
        StringBuilder result = new StringBuilder();
        int visibleCount = 0;
        boolean inAnsi = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\033') {
                inAnsi = true;
            }
            
            result.append(c);
            
            if (inAnsi) {
                if (c == 'm' || c == 'K' || c == 'J' || c == 'H' || c == 'A' || c == 'l' || c == 'h') {
                    // Primitive check to end ANSI sequences, 'm' is color, others are cursor/screen
                    inAnsi = false; 
                }
            } else if (c != '\r' && c != '\n') {
                visibleCount++;
            }
            
            if (visibleCount >= maxWidth) {
                result.append(Theme.COLOR_RESET);
                break;
            }
        }
        return result.toString();
    }

    @Override
    public void runRenderLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        if (term.isInteractive()) {
            term.print(Theme.TERM_HIDE_CURSOR); // Hide cursor
        }
        
        com.midiraja.engine.PlaylistContext context = engine.getContext();
        String fileName = context.files().get(context.currentIndex()).getName();
        String title = context.sequenceTitle() != null ? context.sequenceTitle() : "";
        
        // Count how many lines we print so we know how much to jump up to clear on next track
        int staticLinesPrinted = 0;

        term.println(String.format("\033[7m Midiraja v%s - Terminal Lover's MIDI Player \033[0m", com.midiraja.Version.VERSION));
        staticLinesPrinted++;
        
        int listSize = context.files().size();
        int idx = context.currentIndex();
        String indexStr = listSize > 1 ? String.format(" [%d/%d]", idx + 1, listSize) : "";
        
        term.println("\033[38;5;215mPlaying" + indexStr + ":\033[0m " + fileName + "  [Port: " + context.targetPort().name() + "]");
        staticLinesPrinted++;
        
        if (!title.isEmpty()) {
            term.println("  Title: " + title);
            staticLinesPrinted++;
        }
        
        term.println("\033[38;5;244m  Controls: [Spc]Pause [▲ ▼]Track [◀ ▶]Seek [+-]Vol [<>]Speed [/\']Trans [Q]Quit\033[0m");
        staticLinesPrinted++;
        term.println(""); // Blank line
        staticLinesPrinted++;
        
        String[] blocks = {" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", Theme.CHAR_BLOCK_FULL};
        
        try {
            while (engine.isPlaying()) {
                // Apply classic decay
                engine.decayChannelLevels(0.15);
                double[] levels = engine.getChannelLevels();

                ScreenBuffer buffer = new ScreenBuffer();
                buffer.append("\r\033[38;5;215mVol:[\033[0m");
                for (int i = 0; i < 16; i++) {
                    int levelIndex = (int) Math.round(levels[i] * 8);
                    levelIndex = Math.max(0, Math.min(8, levelIndex));
                    // Highlight bars
                    buffer.append(Theme.COLOR_HIGHLIGHT).append(blocks[levelIndex]).append(Theme.COLOR_RESET);
                }
                buffer.append("\033[38;5;215m]\033[0m ");
                
                long totalMicros = engine.getTotalMicroseconds();
                long currentMicros = engine.getCurrentMicroseconds();
                boolean incHrs = (totalMicros / 1000000) >= 3600;
                
                String timeStr = formatTime(currentMicros, incHrs) + "/" + formatTime(totalMicros, incHrs);
                if (engine.isPaused()) {
                    timeStr = "\033[1;33m[PAUSED]\033[0m " + timeStr;
                }
                
                double effectiveBpm = engine.getCurrentBpm() * engine.getCurrentSpeed();
                buffer.append(String.format(" %s | Spd: %.1fx(BPM: %5.1f) | Tr: %+d | Vol: %3d%% ", 
                    timeStr, engine.getCurrentSpeed(), effectiveBpm, engine.getCurrentTranspose(), (int)(engine.getVolumeScale() * 100)));
                
                // Clear to end of line to prevent ghosting
                buffer.append(Theme.TERM_CLEAR_TO_EOL);
                
                String rawLine = buffer.toString();
                int termWidth = term.getWidth();
                if (termWidth > 0) {
                    rawLine = truncateAnsi(rawLine, termWidth - 1);
                }
                
                term.print(rawLine);
                Thread.sleep(33); // ~30 FPS as in the original
            }
            
            // Playback loop finished (either natural next track or user quit)
            if (term.isInteractive()) {
                // Erase the line UI completely by moving cursor up and clearing
                // Move up 'staticLinesPrinted' times, then clear to end of screen
                term.print("\r");
                for (int i = 0; i < staticLinesPrinted; i++) {
                    term.print(Theme.TERM_CURSOR_UP);
                }
                term.print(Theme.TERM_CLEAR_TO_END); // Clear from cursor to end of screen
                term.print(Theme.TERM_SHOW_CURSOR); // Show cursor
            } else {
                term.println("");
            }
        } catch (InterruptedException _) {
            if (term.isInteractive()) term.print(Theme.TERM_SHOW_CURSOR);
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
            case PAUSE -> engine.togglePause();
            case QUIT -> engine.requestStop(PlaybackStatus.QUIT_ALL);
            default -> {}
        }
    }
}