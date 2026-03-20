/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import com.fupfin.midiraja.engine.PlaybackEngine;
import com.fupfin.midiraja.io.TerminalIO;

/**
 * Interface representing a Face/UI implementation that can render the state of a PlaybackEngine and
 * handle user input.
 */
public interface PlaybackUI
{
    /**
     * Executes the rendering loop. This method should block until the engine stops playing.
     */
    void runRenderLoop(PlaybackEngine engine);

    /**
     * Executes the input polling loop. This method should block until the engine stops playing.
     */
    void runInputLoop(PlaybackEngine engine);

    /**
     * Called before the process suspends (SIGTSTP). The implementation should restore the terminal
     * to a clean state so the shell is usable after the process is stopped.
     *
     * @param term      the active terminal
     * @param altScreen whether an alternate screen buffer is currently active
     */
    default void suspend(TerminalIO term, boolean altScreen) {}

    /**
     * Called after the process resumes (SIGCONT). The implementation should re-enter the display
     * mode it was in before suspend.
     *
     * @param term      the active terminal
     * @param altScreen whether an alternate screen buffer should be re-entered
     */
    default void resume(TerminalIO term, boolean altScreen) {}
}
