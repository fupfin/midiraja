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
    @Override
    public void runRenderLoop(PlaybackEngine engine)
    {
        var term = TerminalIO.CONTEXT.get();
        String[] blocks = {" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█"};
        
        try {
            while (engine.isPlaying()) {
                // Apply classic decay
                engine.decayChannelLevels(0.15);
                double[] levels = engine.getChannelLevels();

                StringBuilder sb = new StringBuilder();
                sb.append("\rVol:[");
                for (int i = 0; i < 16; i++) {
                    int levelIndex = (int) Math.round(levels[i] * 8);
                    levelIndex = Math.max(0, Math.min(8, levelIndex));
                    sb.append(blocks[levelIndex]);
                }
                sb.append("] ");
                
                long totalMicros = engine.getTotalMicroseconds();
                long currentMicros = engine.getCurrentMicroseconds();
                double pct = totalMicros > 0 ? (double) currentMicros / totalMicros : 0;
                
                sb.append(String.format("%3d%% (BPM: %5.1f, Vol: %3d%%) ", 
                    (int)(pct * 100), engine.getCurrentBpm(), (int)(engine.getVolumeScale() * 100)));
                
                // Clear to end of line to prevent ghosting
                sb.append("\033[K");
                
                term.print(sb.toString());
                Thread.sleep(33); // ~30 FPS as in the original
            }
            term.println(""); // Move to next line when done
        } catch (InterruptedException _) {}
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
            case QUIT -> engine.requestStop(PlaybackStatus.QUIT_ALL);
            default -> {}
        }
    }
}