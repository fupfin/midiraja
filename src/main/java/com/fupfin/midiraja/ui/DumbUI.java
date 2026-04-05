/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import static com.fupfin.midiraja.ui.UIUtils.formatTime;

import com.fupfin.midiraja.Version;
import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackState;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.TerminalIO;

/**
 * A non-interactive UI implementation. No input polling and minimal, static output. Used for
 * headless CI, script piping, or simple batch playback.
 */
public class DumbUI implements PlaybackUI
{
    private final boolean quiet;
    private boolean headerPrinted = false;

    public DumbUI()
    {
        this(false);
    }

    public DumbUI(boolean quiet)
    {
        this.quiet = quiet;
    }

    @Override
    public void runRenderLoop(PlaybackState engine)
    {
        var term = TerminalIO.CONTEXT.get();
        PlaylistContext context = engine.getContext();

        int listSize = context.files().size();
        int idx = context.currentIndex();
        String portName = context.targetPort().name();

        if (!quiet)
        {
            // 1. 첫 줄 프로그램 소개 (한 번만 출력)
            if (!headerPrinted)
            {
                term.println(
                        String.format("\033[7m MIDIraja v%s - " + Logo.TAGLINE + "\033[0m",
                                Version.VERSION));

                // 2. 재생 목록 요약
                if (listSize == 1)
                {
                    String title = context.sequenceTitle();
                    String fileName = context.files().get(idx).getName();
                    String displayTitle = title != null && !title.isEmpty()
                            ? title + " (" + fileName + ")"
                            : fileName;

                    term.println(String.format("Playing: %s to port '%s'", displayTitle, portName));
                }
                else
                {
                    term.println(String.format("Playing %d files to port '%s'", listSize, portName));
                }
                headerPrinted = true; // 이후 곡 전환 시에는 생략
            }

            // 3. 현재 재생 중인 곡 정보 출력
            String title = context.sequenceTitle();
            String fileName = context.files().get(idx).getName();
            String displayTitle = title != null && !title.isEmpty() ? title + " (" + fileName + ")" : fileName;

            long totalMicros = engine.getTotalMicroseconds();
            String lengthStr = formatTime(totalMicros, (totalMicros / 1000000) >= 3600);

            if (listSize > 1)
            {
                term.println(String.format("  [%d/%d] %s - %s", (idx + 1), listSize, displayTitle,
                        lengthStr));
            }
            else
            {
                term.println(String.format("  Length: %s", lengthStr));
            }
        }

        try
        {
            while (engine.isPlaying())
            {
                Thread.sleep(1000); // Sleep and wait for engine to finish
            }
        }
        catch (InterruptedException _)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void runInputLoop(PlaybackCommands engine)
    {
        // No input handling in dumb mode
        try
        {
            while (engine.isPlaying())
            {
                Thread.sleep(1000);
            }
        }
        catch (InterruptedException _)
        {
            Thread.currentThread().interrupt();
        }
    }
}
