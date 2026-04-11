/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import com.fupfin.midiraja.engine.PlaylistContext;

/**
 * Listener interface for receiving real-time playback events from the PlaybackEngine.
 */
public interface PlaybackEventListener
{
    /**
     * Called when the playback state changes (volume, speed, transpose, etc.).
     */
    void onPlaybackStateChanged();

    /**
     * Called periodically during playback to update the current position.
     *
     * @param currentMicroseconds
     *            The current playback position in microseconds.
     */
    void onTick(long currentMicroseconds);

    /**
     * Called when the tempo of the MIDI sequence changes.
     *
     * @param bpm
     *            The new tempo in beats per minute.
     */
    void onTempoChanged(float bpm);

    /**
     * Called when a MIDI Note On event occurs on a specific channel.
     *
     * @param channel
     *            The MIDI channel (0-15).
     * @param velocity
     *            The velocity of the note (0-127).
     */
    void onChannelActivity(int channel, int velocity);

    /**
     * Called each render cycle when spectrum analysis data is available.
     *
     * @param levels
     *            16-element array in interleaved order [L0,R0,L1,R1,…,L7,R7]: even indices are
     *            left-channel band levels, odd indices are right-channel band levels. Each value is
     *            in [0, 1]. The array is a snapshot; do not retain a reference to it.
     */
    default void onSpectrumUpdate(float[] levels)
    {
    }

    /**
     * Called when the bookmark state of the current session changes.
     *
     * @param bookmarked
     *            {@code true} if the session was just bookmarked, {@code false} if removed.
     */
    default void onBookmarkChanged(boolean bookmarked)
    {
    }

    /**
     * Called when the playlist play order changes (e.g. shuffle toggled mid-song).
     */
    default void onPlayOrderChanged(PlaylistContext ctx)
    {
    }
}
