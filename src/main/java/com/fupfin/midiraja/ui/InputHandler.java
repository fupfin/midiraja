/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.io.TerminalIO.TerminalKey;

public class InputHandler
{
    private InputHandler()
    {
    }

    public static void handleCommonInput(PlaybackCommands engine, TerminalKey key)
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
            case SEEK_FORWARD -> engine.seekRelative(10_000_000); // +10 seconds
            case SEEK_BACKWARD -> engine.seekRelative(-10_000_000); // -10 seconds
            case PAUSE -> engine.togglePause();
            case BOOKMARK -> engine.fireBookmark();
            case TOGGLE_LOOP -> engine.toggleLoop();
            case TOGGLE_SHUFFLE -> engine.toggleShuffle();
            case QUIT -> engine.requestStop(PlaybackStatus.QUIT_ALL);
            case RESUME_SESSION -> engine.requestStop(PlaybackStatus.RESUME_SESSION);
            default ->
                {
                }
        }
    }

    public static void handleMiniInput(PlaybackCommands engine, TerminalKey key)
    {
        switch (key)
        {
            case TOGGLE_LOOP, TOGGLE_SHUFFLE ->
                {
                } // not available in mini mode
            default -> handleCommonInput(engine, key);
        }
    }
}
