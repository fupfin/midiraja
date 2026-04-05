/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.mod;

/**
 * One of the 31 instrument sample slots in a ProTracker MOD file.
 *
 * @param name
 *            sample name, up to 22 characters (null bytes trimmed)
 * @param length
 *            sample length in words (multiply by 2 for byte count)
 * @param finetune
 *            fine-tune value −8 to +7 (stored as signed nibble)
 * @param volume
 *            default volume 0–64
 * @param loopStart
 *            loop start in words
 * @param loopLength
 *            loop length in words; 0 or 1 means no loop
 */
public record ModInstrument(
        String name,
        int length,
        int finetune,
        int volume,
        int loopStart,
        int loopLength)
{
}
