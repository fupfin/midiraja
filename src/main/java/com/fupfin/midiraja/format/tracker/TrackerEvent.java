/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.tracker;

/**
 * A single note-cell event shared across tracker formats (S3M, XM, IT).
 *
 * @param microsecond
 *            absolute playback position in microseconds
 * @param channel
 *            format channel index (0-based)
 * @param note
 *            MIDI note number (0–127), {@code -1} for no note, {@code -2} for key-off/cut
 * @param instrument
 *            1-based instrument index (0 = no change)
 * @param volume
 *            volume column (0–64 = set volume), {@code -1} if column is empty
 * @param effectCmd
 *            effect command byte (format-specific), or 0 for none
 * @param effectParam
 *            effect parameter byte
 */
public record TrackerEvent(long microsecond, int channel, int note, int instrument,
        int volume, int effectCmd, int effectParam)
{
}
