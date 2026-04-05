/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaylistContext;

class PlaybackCommandsProxyTest
{
    @Test
    void nullTarget_isPlayingReturnsFalse()
    {
        var ref = new AtomicReference<PlaybackCommands>(null);
        var proxy = new PlaybackCommandsProxy(ref);
        assertFalse(proxy.isPlaying());
    }

    @Test
    void nullTarget_allMutatingMethodsDropSilently()
    {
        var ref = new AtomicReference<PlaybackCommands>(null);
        var proxy = new PlaybackCommandsProxy(ref);
        assertDoesNotThrow(() -> callAllMutatingMethods(proxy));
    }

    @Test
    void delegates_togglePause_whenTargetSet()
    {
        var called = new boolean[] { false };
        PlaybackCommands fake = fakeCmds(() -> called[0] = true);
        var ref = new AtomicReference<>(fake);
        var proxy = new PlaybackCommandsProxy(ref);
        proxy.togglePause();
        assertTrue(called[0]);
    }

    @Test
    void nullAfterSet_dropsCallsSilently()
    {
        var ref = new AtomicReference<PlaybackCommands>();
        var proxy = new PlaybackCommandsProxy(ref);
        ref.set(null);
        assertDoesNotThrow(() -> proxy.seekRelative(10_000_000L));
    }

    @Test
    void delegates_seekRelative_withCorrectDelta()
    {
        var captured = new long[] { Long.MIN_VALUE };
        PlaybackCommands fake = new PlaybackCommands()
        {
            @Override
            public boolean isPlaying()
            {
                return false;
            }

            @Override
            public void togglePause()
            {
            }

            @Override
            public void requestStop(PlaybackStatus s)
            {
            }

            @Override
            public void adjustVolume(double d)
            {
            }

            @Override
            public void adjustSpeed(double d)
            {
            }

            @Override
            public void adjustTranspose(int d)
            {
            }

            @Override
            public void seekRelative(long d)
            {
                captured[0] = d;
            }

            @Override
            public void toggleLoop()
            {
            }

            @Override
            public void toggleShuffle()
            {
            }

            @Override
            public void fireBookmark()
            {
            }

            @Override
            public void firePlayOrderChanged(PlaylistContext c)
            {
            }
        };
        var ref = new AtomicReference<>(fake);
        new PlaybackCommandsProxy(ref).seekRelative(10_000_000L);
        assertEquals(10_000_000L, captured[0]);
    }

    private static void callAllMutatingMethods(PlaybackCommandsProxy proxy)
    {
        proxy.requestStop(PlaybackStatus.FINISHED);
        proxy.adjustVolume(10);
        proxy.adjustSpeed(0.1);
        proxy.adjustTranspose(1);
        proxy.seekRelative(1_000_000L);
        proxy.togglePause();
        proxy.toggleLoop();
        proxy.toggleShuffle();
        proxy.fireBookmark();
        proxy.firePlayOrderChanged(null);
    }

    private static PlaybackCommands fakeCmds(Runnable onTogglePause)
    {
        return new PlaybackCommands()
        {
            @Override
            public boolean isPlaying()
            {
                return true;
            }

            @Override
            public void togglePause()
            {
                onTogglePause.run();
            }

            @Override
            public void requestStop(PlaybackStatus s)
            {
            }

            @Override
            public void adjustVolume(double d)
            {
            }

            @Override
            public void adjustSpeed(double d)
            {
            }

            @Override
            public void adjustTranspose(int d)
            {
            }

            @Override
            public void seekRelative(long d)
            {
            }

            @Override
            public void toggleLoop()
            {
            }

            @Override
            public void toggleShuffle()
            {
            }

            @Override
            public void fireBookmark()
            {
            }

            @Override
            public void firePlayOrderChanged(PlaylistContext c)
            {
            }
        };
    }
}
