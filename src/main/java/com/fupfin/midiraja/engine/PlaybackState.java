/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import javax.sound.midi.Sequence;

import com.fupfin.midiraja.ui.PlaybackEventListener;

/** Read-only view of engine state — consumed by the render loop. */
public interface PlaybackState
{
    PlaylistContext getContext();

    Sequence getSequence();

    long getCurrentMicroseconds();

    long getTotalMicroseconds();

    int[] getChannelPrograms();

    float getCurrentBpm();

    double getCurrentSpeed();

    int getCurrentTranspose();

    double getVolumeScale();

    boolean isPlaying();

    boolean isPaused();

    boolean isLoopEnabled();

    boolean isShuffleEnabled();

    boolean isBookmarked();

    String getFilterDescription();

    String getPortSuffix();

    /**
     * Returns {@code true} when the engine provides spectrum data instead of MIDI channel activity.
     * The default implementation returns {@code false}.
     */
    default boolean isSpectrumMode()
    {
        return false;
    }

    /**
     * Returns {@code true} when the engine supports real-time transpose adjustment.
     * The default implementation returns {@code true}; engines that play pre-recorded register
     * streams (e.g. libvgm) should return {@code false}.
     */
    default boolean isTransposeSupported()
    {
        return true;
    }

    void addPlaybackEventListener(PlaybackEventListener listener);
}
