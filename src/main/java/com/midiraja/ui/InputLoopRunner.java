/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaybackEngine;
import com.midiraja.io.TerminalIO;
import com.midiraja.io.TerminalIO.TerminalKey;
import java.io.IOException;
import java.util.function.BiConsumer;

public class InputLoopRunner
{
    private InputLoopRunner() {}

    /**
     * Standard polling loop for terminal input.
     * Blocks and reads keys until the engine stops playing, passing each key to the provided handler.
     */
    public static void run(PlaybackEngine engine, BiConsumer<PlaybackEngine, TerminalKey> keyHandler)
    {
        var term = TerminalIO.CONTEXT.get();
        try
        {
            while (engine.isPlaying())
            {
                var key = term.readKey();
                keyHandler.accept(engine, key);
            }
        }
        catch (IOException _)
        {
            engine.requestStop(PlaybackEngine.PlaybackStatus.QUIT_ALL);
        }
    }
}
