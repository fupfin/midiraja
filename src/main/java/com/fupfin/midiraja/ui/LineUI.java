/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import static com.fupfin.midiraja.ui.UIUtils.formatTime;
import static com.fupfin.midiraja.ui.UIUtils.truncateAnsi;
import static java.lang.Math.*;

import com.fupfin.midiraja.Version;
import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackState;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.TerminalIO;

public class LineUI implements PlaybackUI
{
    @Override
    public void runRenderLoop(PlaybackState engine)
    {
        double[] channelLevels = new double[16];
        engine.addPlaybackEventListener(new PlaybackEventListener()
        {
            @Override
            public void onPlaybackStateChanged()
            {
            }

            @Override
            public void onTick(long currentMicroseconds)
            {
            }

            @Override
            public void onTempoChanged(float bpm)
            {
            }

            @Override
            public void onChannelActivity(int channel, int velocity)
            {
                if (channel >= 0 && channel < 16)
                    channelLevels[channel] = Math.max(channelLevels[channel], velocity / 127.0);
            }
        });

        var term = TerminalIO.CONTEXT.get();
        if (term.isInteractive())
        {
            // Hide cursor and Disable Auto-Wrap (DECAWM) to absolutely prevent
            // terminal reflow from breaking the line during rapid window shrinks.
            term.print(Theme.TERM_HIDE_CURSOR + "\033[?7l");
        }

        PlaylistContext context = engine.getContext();
        String fileName = context.files().get(context.currentIndex()).getName();
        String title = context.sequenceTitle() != null ? context.sequenceTitle() : "";

        // Count how many lines we print so we know how much to jump up to clear on
        // next track
        int staticLinesPrinted = 0;

        term.println(String.format("\033[7m MIDIraja v%s - " + Logo.TAGLINE + "\033[0m",
                Version.VERSION));
        staticLinesPrinted++;

        int listSize = context.files().size();
        int idx = context.currentIndex();
        String indexStr = listSize > 1 ? String.format(" [%d/%d]", idx + 1, listSize) : "";

        term.println("\033[38;5;215mPlaying" + indexStr + ":\033[0m " + fileName + "  [Port: "
                + context.targetPort().name() + "]");
        staticLinesPrinted++;

        if (!title.isEmpty())
        {
            term.println("  Title: " + title);
            staticLinesPrinted++;
        }

        term.println("\033[38;5;244m  Controls: [Spc]Pause [▲ ▼]Track [◀ ▶]Seek "
                + "[+-]Vol [<>]Speed " + "[/\']Trans [Q]Quit\033[0m");
        staticLinesPrinted++;
        term.println(""); // Blank line
        staticLinesPrinted++;

        String[] blocks = { " ", " ", "▂", "▃", "▄", "▅", "▆", "▇", Theme.CHAR_BLOCK_FULL };

        try
        {
            while (engine.isPlaying())
            {
                // Apply classic decay
                for (int i = 0; i < 16; i++)
                    channelLevels[i] = Math.max(0, channelLevels[i] - 0.15);
                double[] levels = channelLevels;

                ScreenBuffer buffer = new ScreenBuffer();
                buffer.append("\r\033[38;5;215mVol:[\033[0m");
                for (int i = 0; i < 16; i++)
                {
                    int levelIndex = (int) round(levels[i] * 8);
                    levelIndex = max(0, min(8, levelIndex));
                    // Highlight bars
                    buffer.append(Theme.COLOR_HIGHLIGHT).append(blocks[levelIndex])
                            .append(Theme.COLOR_RESET);
                }
                buffer.append("\033[38;5;215m]\033[0m ");

                long totalMicros = engine.getTotalMicroseconds();
                long currentMicros = engine.getCurrentMicroseconds();
                boolean incHrs = (totalMicros / 1000000) >= 3600;

                String timeStr = formatTime(currentMicros, incHrs) + "/" + formatTime(totalMicros, incHrs);
                if (engine.isPaused())
                {
                    timeStr = "\033[1;33m[PAUSED]\033[0m " + timeStr;
                }

                double effectiveBpm = engine.getCurrentBpm() * engine.getCurrentSpeed();
                buffer.append(String.format(" %s | Spd: %.1fx(BPM: %5.1f) | Tr: %+d | Vol: %3d%% ",
                        timeStr, engine.getCurrentSpeed(), effectiveBpm,
                        engine.getCurrentTranspose(), (int) (engine.getVolumeScale() * 100)));

                String loopIcon = engine.isLoopEnabled()
                        ? Theme.COLOR_HIGHLIGHT + Theme.ICON_LOOP + Theme.COLOR_RESET
                        : Theme.COLOR_DIM_FG + Theme.ICON_LOOP + Theme.COLOR_RESET;
                String shuffleIcon = engine.isShuffleEnabled()
                        ? Theme.COLOR_HIGHLIGHT + Theme.ICON_SHUFFLE + Theme.COLOR_RESET
                        : Theme.COLOR_DIM_FG + Theme.ICON_SHUFFLE + Theme.COLOR_RESET;
                buffer.append(" ").append(loopIcon).append(shuffleIcon);

                String rawLine = buffer.toString();
                int termWidth = term.getWidth();
                if (termWidth > 0)
                {
                    // Safe truncation that leaves room for the cursor
                    rawLine = truncateAnsi(rawLine, max(10, termWidth - 2));
                }

                // Always clear to EOL *after* truncation so the clear command isn't
                // chopped off!
                term.print(rawLine + Theme.TERM_CLEAR_TO_EOL);
                Thread.sleep(33); // ~30 FPS as in the original
            }

            // Playback loop finished (either natural next track or user quit)
            if (term.isInteractive())
            {
                // Erase the line UI completely by moving cursor up and clearing
                // Move up 'staticLinesPrinted' times, then clear to end of screen
                term.print("\r");
                for (int i = 0; i < staticLinesPrinted; i++)
                {
                    term.print(Theme.TERM_CURSOR_UP);
                }
                term.print(Theme.TERM_CLEAR_TO_END); // Clear from cursor to end of screen
                term.print(Theme.TERM_SHOW_CURSOR + "\033[?7h"); // Show cursor, re-enable auto-wrap
            }
            else
            {
                term.println("");
            }
        }
        catch (InterruptedException _)
        {
            if (term.isInteractive())
                term.print(Theme.TERM_SHOW_CURSOR + "\033[?7h");
        }
    }

    @Override
    public void runInputLoop(PlaybackCommands engine)
    {
        InputLoopRunner.run(engine, InputHandler::handleMiniInput);
    }

    @Override
    public void suspend(com.fupfin.midiraja.io.TerminalIO term, boolean altScreen)
    {
        if (term.isInteractive())
            term.print(Theme.TERM_SHOW_CURSOR + Theme.TERM_AUTOWRAP_ON);
    }

    @Override
    public void resume(com.fupfin.midiraja.io.TerminalIO term, boolean altScreen)
    {
        if (term.isInteractive())
            term.print(Theme.TERM_HIDE_CURSOR + Theme.TERM_AUTOWRAP_OFF);
    }
}
