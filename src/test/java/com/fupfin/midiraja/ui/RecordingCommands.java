/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */
package com.fupfin.midiraja.ui;

import java.util.ArrayList;
import java.util.List;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine;
import com.fupfin.midiraja.engine.PlaylistContext;

/** Test helper: records PlaybackCommands calls for assertion in tests. */
class RecordingCommands implements PlaybackCommands
{
    final List<String> calls = new ArrayList<>();

    /** After this many isPlaying() calls return true, subsequent calls return false. */
    public void stopAfter(int calls)
    {
        this.stopAfterCount = calls;
    }
    private int stopAfterCount = Integer.MAX_VALUE;
    private int isPlayingCallCount = 0;

    @Override
    public boolean isPlaying()
    {
        return ++isPlayingCallCount <= stopAfterCount;
    }

    @Override
    public void requestStop(PlaybackEngine.PlaybackStatus s)
    {
        calls.add("requestStop:" + s);
    }

    @Override
    public void adjustVolume(double d)
    {
        calls.add("adjustVolume:" + d);
    }

    @Override
    public void adjustSpeed(double d)
    {
        calls.add("adjustSpeed:" + d);
    }

    @Override
    public void adjustTranspose(int d)
    {
        calls.add("adjustTranspose:" + d);
    }

    @Override
    public void seekRelative(long us)
    {
        calls.add("seekRelative:" + us);
    }

    @Override
    public void togglePause()
    {
        calls.add("togglePause");
    }

    @Override
    public void toggleLoop()
    {
        calls.add("toggleLoop");
    }

    @Override
    public void toggleShuffle()
    {
        calls.add("toggleShuffle");
    }

    @Override
    public void fireBookmark()
    {
        calls.add("fireBookmark");
    }

    @Override
    public void firePlayOrderChanged(PlaylistContext ctx)
    {
        calls.add("firePlayOrderChanged");
    }
}
