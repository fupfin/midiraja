/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.mod;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Result of parsing a ProTracker MOD file.
 *
 * @param title
 *            song title, up to 20 characters
 * @param channelCount
 *            number of channels (4, 6, or 8)
 * @param instruments
 *            31 instrument slots (index 0 = instrument 1)
 * @param events
 *            chronological linearized events from all channels
 * @param formatTag
 *            4-byte format tag string, e.g. "M.K.", "8CHN"; null if unrecognized
 */
public record ModParseResult(
        String title,
        int channelCount,
        List<ModInstrument> instruments,
        List<ModEvent> events,
        @Nullable String formatTag)
{
}
