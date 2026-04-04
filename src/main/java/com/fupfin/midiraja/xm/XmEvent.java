/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.xm;

/**
 * A single note-cell event extracted from an XM pattern.
 *
 * @param microsecond  absolute playback position in microseconds
 * @param channel      XM channel index (0-based)
 * @param note         MIDI note number (0–127), {@code -1} for no note, {@code -2} for key-off
 * @param instrument   1-based instrument index (0 = no change)
 * @param volume       volume column value (0–64 = set volume), {@code -1} if column is empty
 * @param effectCmd    effect command byte (0x00–0x1F), or 0 for none
 * @param effectParam  effect parameter byte
 */
public record XmEvent(long microsecond, int channel, int note, int instrument,
                      int volume, int effectCmd, int effectParam) {}
