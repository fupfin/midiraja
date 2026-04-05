/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import java.util.Optional;
import java.util.function.Consumer;

import com.fupfin.midiraja.ui.PlaybackUI;

/**
 * Full playback control interface. Extends {@link PlaybackState} (render loop) and
 * {@link PlaybackCommands} (input loop), and adds lifecycle and configuration methods used by
 * {@code PlaylistPlayer}.
 */
public interface PlaybackEngine extends PlaybackState, PlaybackCommands
{
    enum PlaybackStatus
    {
        FINISHED, NEXT, PREVIOUS, QUIT_ALL, RESUME_SESSION
    }

    /** Starts playback. Blocks until the track finishes or is interrupted. */
    PlaybackStatus start(PlaybackUI ui) throws Exception;

    // Pre-start configuration
    void setHoldAtEnd(boolean hold);

    void setIgnoreSysex(boolean ignoreSysex);

    void setInitialResetType(Optional<String> resetType);

    void setInitiallyPaused();

    void setFilterDescription(String desc);

    void setPortSuffix(String suffix);

    void setBookmarked(boolean bookmarked);

    void setBookmarkCallback(Consumer<Boolean> callback);

    void setShuffleCallback(Consumer<Boolean> callback);
}
