/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.s3m;

/**
 * A single note-cell event extracted from a Scream Tracker 3 pattern.
 *
 * @param microsecond  absolute playback position in microseconds
 * @param channel      S3M channel index (0-based)
 * @param note         MIDI note number (0–127), or {@code -1} for no note, {@code -2} for key-off
 * @param instrument   1-based instrument index (0 = no change)
 * @param volume       per-cell volume (0–64), or {@code -1} if the volume column is empty
 * @param effectCmd    effect command (1=A … 26=Z), or 0 for none
 * @param effectParam  effect parameter byte
 */
public record S3mEvent(long microsecond, int channel, int note, int instrument,
                       int volume, int effectCmd, int effectParam) {}
