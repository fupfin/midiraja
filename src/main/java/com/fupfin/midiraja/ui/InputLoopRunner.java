/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import java.io.IOException;
import java.util.function.BiConsumer;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.io.TerminalIO.TerminalKey;

public class InputLoopRunner
{
    private InputLoopRunner()
    {
    }

    /**
     * Standard polling loop for terminal input. Blocks and reads keys until the engine stops
     * playing, passing each key to the provided handler.
     */
    public static void run(PlaybackCommands engine,
            BiConsumer<PlaybackCommands, TerminalKey> keyHandler)
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
