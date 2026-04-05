/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

/**
 * A single note/effect event from a linearized ProTracker MOD channel.
 *
 * @param microsecond absolute time position in microseconds from the start of the song
 * @param channel     0-based MOD channel index
 * @param period      Amiga period value (0 = no note trigger this row)
 * @param instrument  instrument number 1–31, or 0 if no instrument change
 * @param effectCmd   effect command nibble 0x0–0xF
 * @param effectParam effect parameter byte 0x00–0xFF
 */
public record ModEvent(
        long microsecond,
        int channel,
        int period,
        int instrument,
        int effectCmd,
        int effectParam) {}
