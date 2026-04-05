/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

/** Mutating control surface — consumed by the input loop. */
public interface PlaybackCommands
{
    boolean isPlaying();

    void requestStop(PlaybackEngine.PlaybackStatus status);

    void adjustVolume(double delta);

    void adjustSpeed(double delta);

    void adjustTranspose(int delta);

    void seekRelative(long microsecondsDelta);

    void togglePause();

    void toggleLoop();

    void toggleShuffle();

    void fireBookmark();

    void firePlayOrderChanged(PlaylistContext ctx);
}
