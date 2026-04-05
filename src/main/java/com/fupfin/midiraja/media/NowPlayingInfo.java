/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.media;

/**
 * Snapshot of the currently playing track for OS Now Playing panel display.
 * {@code artist} may be an empty string; native implementations omit the field
 * entirely (rather than showing a blank label) when it is empty.
 */
public record NowPlayingInfo(
        String title,
        String artist,
        long durationMicros,
        long positionMicros,
        boolean isPlaying)
{
    public NowPlayingInfo
    {
        java.util.Objects.requireNonNull(title, "title");
        java.util.Objects.requireNonNull(artist, "artist");
    }
}
