/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Playback options set by {@code #MIDRA:} directives inside M3U playlists.
 * Only fields explicitly named in a directive are non-empty / true;
 * unset fields have empty Optionals or {@code false} booleans.
 *
 * <p>When multiple M3U files are parsed in one call, directives accumulate:
 * volume/speed use last-wins; boolean flags use OR (once {@code true}, stay {@code true}).
 */
record PlaylistDirectives(
        OptionalInt volume,
        OptionalDouble speed,
        boolean shuffle,
        boolean loop,
        boolean recursive)
{
    /** Sentinel: no M3U directives were found in this parse call. */
    static final PlaylistDirectives NONE =
            new PlaylistDirectives(OptionalInt.empty(), OptionalDouble.empty(),
                    false, false, false);

    /**
     * Applies directive overrides to {@code common}.
     *
     * <ul>
     *   <li>volume and speed: applied only when present; when present, the M3U value
     *       overrides whatever the CLI supplied.</li>
     *   <li>boolean flags: can only assert {@code true}; a CLI-supplied {@code true}
     *       is never cleared by an absent directive.</li>
     * </ul>
     */
    void applyTo(CommonOptions common)
    {
        volume.ifPresent(v -> common.volume = v);
        speed.ifPresent(s -> common.speed = s);
        if (shuffle)   common.shuffle   = true;
        if (loop)      common.loop      = true;
        if (recursive) common.recursive = true;
    }
}
