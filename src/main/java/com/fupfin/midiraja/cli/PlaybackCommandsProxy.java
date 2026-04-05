/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.util.concurrent.atomic.AtomicReference;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaylistContext;

/**
 * Delegates {@link PlaybackCommands} calls to whatever engine is currently active.
 * Calls are silently dropped when the reference is {@code null} (between tracks).
 * Thread-safe: the underlying {@link AtomicReference} allows safe updates from
 * the playlist loop while calls arrive from the media key dispatch thread.
 */
class PlaybackCommandsProxy implements PlaybackCommands
{
    private final AtomicReference<PlaybackCommands> target;

    PlaybackCommandsProxy(AtomicReference<PlaybackCommands> target)
    {
        this.target = target;
    }

    @Override
    public boolean isPlaying()
    {
        var t = target.get();
        return t != null && t.isPlaying();
    }

    @Override
    public void requestStop(PlaybackStatus status)
    {
        var t = target.get();
        if (t != null)
            t.requestStop(status);
    }

    @Override
    public void adjustVolume(double delta)
    {
        var t = target.get();
        if (t != null)
            t.adjustVolume(delta);
    }

    @Override
    public void adjustSpeed(double delta)
    {
        var t = target.get();
        if (t != null)
            t.adjustSpeed(delta);
    }

    @Override
    public void adjustTranspose(int delta)
    {
        var t = target.get();
        if (t != null)
            t.adjustTranspose(delta);
    }

    @Override
    public void seekRelative(long microsecondsDelta)
    {
        var t = target.get();
        if (t != null)
            t.seekRelative(microsecondsDelta);
    }

    @Override
    public void togglePause()
    {
        var t = target.get();
        if (t != null)
            t.togglePause();
    }

    @Override
    public void toggleLoop()
    {
        var t = target.get();
        if (t != null)
            t.toggleLoop();
    }

    @Override
    public void toggleShuffle()
    {
        var t = target.get();
        if (t != null)
            t.toggleShuffle();
    }

    @Override
    public void fireBookmark()
    {
        var t = target.get();
        if (t != null)
            t.fireBookmark();
    }

    @Override
    public void firePlayOrderChanged(PlaylistContext ctx)
    {
        var t = target.get();
        if (t != null)
            t.firePlayOrderChanged(ctx);
    }
}
