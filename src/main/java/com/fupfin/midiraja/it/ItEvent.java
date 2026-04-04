/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.it;

/**
 * A single note-cell event extracted from an IT pattern.
 *
 * @param microsecond  absolute playback position in microseconds
 * @param channel      IT channel index (0-based)
 * @param note         MIDI note number (0–119 = C0–B9), {@code -1} for no note,
 *                     {@code -2} for note cut / note fade
 * @param instrument   1-based instrument index (0 = no change)
 * @param volume       volume column (0–64 = set volume), {@code -1} if column is empty
 * @param effectCmd    effect command (1=A … 26=Z), or 0 for none
 * @param effectParam  effect parameter byte
 */
public record ItEvent(long microsecond, int channel, int note, int instrument,
                      int volume, int effectCmd, int effectParam) {}
