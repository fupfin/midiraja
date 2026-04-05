/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.media;

import com.fupfin.midiraja.engine.PlaybackCommands;

/** No-op fallback used when media key integration is unavailable. */
public final class NoOpMediaIntegration implements MediaKeyIntegration
{
    public static final NoOpMediaIntegration INSTANCE = new NoOpMediaIntegration();

    private NoOpMediaIntegration()
    {
    }

    @Override
    public void start(PlaybackCommands commands)
    {
    }

    @Override
    public void drainAndUpdate(NowPlayingInfo info)
    {
    }

    @Override
    public void close()
    {
    }
}
