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

    /** YM2612 (OPN2) FM, 6 melodic channels (4-operator), clock 7,670,454 Hz. */
    YM2612,

    /** YM3812 (OPL2 / AdLib) FM, 9 channels (2-operator), clock 3,579,545 Hz. */
    YM3812,

    /** YM2608 (OPNA) FM, 6 melodic channels (4-operator) + SSG, clock 7,987,200 Hz. */
    YM2608,

    /** YM2151 (OPM) FM, 8 channels (4-operator), clock 4,000,000 Hz. */
    YM2151,

    /** YM2610 (OPNB) FM, 6 melodic channels (4-operator) + ADPCM-A rhythm, clock 8,000,000 Hz. */
    YM2610,

    /** YM2203 (OPN) FM, 3 FM channels (4-operator) + AY-3-8910 SSG, clock 3,993,600 Hz. */
    YM2203,

    /**
     * YM2610B (OPNB extended) FM, 6 FM channels + SSG + ADPCM-A, clock 8,000,000 Hz.
     *
     * <p>
     * Same VGM commands as {@link #YM2610} (0x58/0x59), differentiated by bit 31 of the clock
     * field in the VGM header at offset 0x4C.
     */
    YM2610B,

    /**
     * Game Boy DMG (LR35902) APU — 2 pulse channels, 1 wave channel, 1 noise channel,
     * clock 4,194,304 Hz. VGM header offset 0x80 (requires v1.70 header).
     */
    DMG,

    /**
     * HuC6280 (PC Engine PSG) — 6 wavetable channels, 5-bit unsigned wave RAM (32 samples/ch),
     * clock 3,579,545 Hz. VGM header offset 0xA4 (requires v1.70 header).
     */
    HUC6280,

    /**
     * NES APU (RP2A03) — 2 pulse channels, 1 triangle channel, 1 noise channel,
     * clock 1,789,773 Hz. VGM header offset 0x84 (requires v1.70 header).
     */
    NES_APU,

    /**
     * OKI MSM6258 ADPCM — 1-channel 4-bit streaming ADPCM, 8 MHz clock / 512 divider = 15,625 Hz.
     * Used for percussion on the Sharp X68000. No internal ROM; data is streamed via the VGM
     * DAC stream mechanism (PCM data block type 0x04). VGM header offset 0x90 (clock),
     * 0x94 (flags: 0x02 = /512 divider, 10-bit output). Requires v1.70 header.
     */
    MSM6258
}
