/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.io;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Mock implementation of TerminalIO for unit testing the PlaybackEngine. Allows programmatic
 * injection of keystrokes.
 */
public class MockTerminalIO implements TerminalIO
{
    private final Queue<TerminalKey> keyQueue = new LinkedList<>();
    private final StringBuilder outputBuffer = new StringBuilder();

    public void injectKey(TerminalKey key)
    {
        keyQueue.add(key);
    }

    @Override
    public boolean isInteractive()
    {
        return false;
    }

    @Override
    public void init() throws IOException
    {}

    @Override
    public void close() throws IOException
    {}

    @Override
    public TerminalKey readKey() throws IOException
    {
        return keyQueue.isEmpty() ? TerminalKey.NONE : keyQueue.poll();
    }

    @Override
    public void print(String str)
    {
        outputBuffer.append(str);
    }

    @Override
    public void println(String str)
    {
        outputBuffer.append(str).append("\n");
    }

    public String getOutput()
    {
        return outputBuffer.toString();
    }

    @Override
    public int getWidth()
    {
        return 80;
    }

    @Override
    public int getHeight()
    {
        return 24;
    }
}
