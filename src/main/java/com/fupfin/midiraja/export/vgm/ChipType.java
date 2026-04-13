/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * Supported VGM chip types.
 *
 * <p>
 * Multiple chips can be combined in a list to produce multi-chip VGM files.
 * Duplicate entries (e.g., two {@code AY8910} entries) indicate dual-chip configurations.
 */
public enum ChipType
{
    /** AY-3-8910 PSG, 3 tone + noise channels, clock 1,789,772 Hz. */
    AY8910,

    /** YM2413 (OPLL) FM, 9 melodic channels (or 6 melodic + rhythm), clock 3,579,545 Hz. */
    YM2413,

    /** K051649 (SCC) wavetable, 5 channels (ch 3&4 share waveram), clock 3,579,545 Hz. */
    SCC,

    /**
     * K052539 (SCC-I / SCC+) wavetable, 5 fully independent channels, clock 3,579,545 Hz.
     *
     * <p>
     * Differs from {@link #SCC} only in waveform isolation: channels 3 and 4 each have their own
     * 32-byte waveram. Activated via bit 31 of the K051649 clock field at VGM header offset 0x9C.
     */
    SCCI,

    /** YMF262 (OPL3) FM, 18 channels, clock 14,318,180 Hz. */
    OPL3,

    /** SN76489 PSG, 3 tone + noise channels, clock 3,579,545 Hz. */
    SN76489,

    /**
     * YM2612 (OPN2) FM, 6 melodic channels, clock 7,670,454 Hz.
     *
     * <p>
     * Converted via libOPNMIDI's VGMFileDumper backend — not the streaming {@link ChipHandler}
     * model. Use {@code Ym2612VgmExporter} directly.
     */
    YM2612
}
