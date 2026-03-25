/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.media;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaylistContext;

@EnabledOnOs(OS.MAC)
class MacOSMediaSessionTest
{
    @Test void lifecycle_startUpdateClose_noException()
    {
        try (var session = new MacOSMediaSession()) {
            assertDoesNotThrow(() -> session.start(noopCommands()));
            assertDoesNotThrow(() -> session.drainAndUpdate(
                new NowPlayingInfo("Test", "", 60_000_000L, 5_000_000L, true)));
        }
    }

    @Test void drainAndUpdate_beforeStart_isNoOp()
    {
        try (var session = new MacOSMediaSession()) {
            assertDoesNotThrow(() -> session.drainAndUpdate(
                new NowPlayingInfo("T", "", 0, 0, false)));
        }
    }

    @Test void close_beforeStart_isNoOp()
    {
        assertDoesNotThrow(() -> new MacOSMediaSession().close());
    }

    private static PlaybackCommands noopCommands()
    {
        return new PlaybackCommands() {
            @Override public boolean isPlaying() { return false; }
            @Override public void requestStop(PlaybackStatus s) {}
            @Override public void adjustVolume(double d) {}
            @Override public void adjustSpeed(double d) {}
            @Override public void adjustTranspose(int d) {}
            @Override public void seekRelative(long d) {}
            @Override public void togglePause() {}
            @Override public void toggleLoop() {}
            @Override public void toggleShuffle() {}
            @Override public void fireBookmark() {}
            @Override public void firePlayOrderChanged(PlaylistContext c) {}
        };
    }
}
