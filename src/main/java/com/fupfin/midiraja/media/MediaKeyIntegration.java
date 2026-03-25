/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.media;

import com.fupfin.midiraja.engine.PlaybackCommands;

/**
 * Abstraction for OS media key integration (play/pause, next, previous, seek).
 *
 * <p>Call sequence: {@code start()} → any number of {@code drainAndUpdate()} → {@code close()}.
 * All methods are safe to call out of order or multiple times.
 */
public interface MediaKeyIntegration extends AutoCloseable
{
    /** Begin routing media key events to {@code commands}. */
    void start(PlaybackCommands commands);

    /**
     * Drains any pending media key commands to the current {@code PlaybackCommands},
     * then updates the OS Now Playing panel with {@code info}.
     * Safe to call before {@code start()} or after {@code close()} — no-op in both cases.
     * Safe to call from any thread.
     */
    void drainAndUpdate(NowPlayingInfo info);

    /**
     * Stops media key handling and releases OS resources.
     * Safe to call before {@code start()}.
     */
    @Override
    void close();

    /**
     * Returns a platform-appropriate implementation, or {@link NoOpMediaIntegration}
     * if the current platform is unsupported or the native library is unavailable.
     */
    static MediaKeyIntegration create()
    {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac"))
        {
            try
            {
                return new MacOSMediaSession();
            }
            catch (Throwable e)
            {
                System.err.println("Warning: media key integration unavailable: " + e.getMessage());
            }
        }
        return NoOpMediaIntegration.INSTANCE;
    }
}
