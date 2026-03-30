/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Result of parsing a VGM file.
 *
 * @param vgmVersion VGM format version (e.g. 0x150)
 * @param ym2413Clock YM2413 (OPLL) clock in Hz, 0 if chip absent
 * @param sn76489Clock SN76489 clock in Hz, 0 if chip absent
 * @param ym2612Clock YM2612 clock in Hz, 0 if chip absent
 * @param ym2151Clock YM2151 (OPM) clock in Hz, 0 if chip absent
 * @param ym2203Clock YM2203 (OPN) clock in Hz, 0 if chip absent
 * @param ym2608Clock YM2608 (OPNA) clock in Hz, 0 if chip absent
 * @param ym2610Clock YM2610 (OPNB) clock in Hz, 0 if chip absent
 * @param gameBoyDmgClock Game Boy DMG clock in Hz, 0 if chip absent
 * @param huC6280Clock HuC6280 (PC Engine) clock in Hz, 0 if chip absent
 * @param ay8910Clock AY-3-8910 / YM2149F clock in Hz, 0 if chip absent
 * @param sccClock K051649 (Konami SCC) clock in Hz; falls back to {@code ay8910Clock × 2} when
 *     the header field is zero. The K051649 runs at the full MSX cartridge bus clock (= CPU clock),
 *     while the AY8910 has an internal /2 prescaler — so SCC clock = 2 × AY8910 clock.
 * @param events chip events in chronological order
 * @param gd3Title track title from GD3 tag, null if absent
 */
public record VgmParseResult(
        int vgmVersion,
        long ym2413Clock,
        long sn76489Clock,
        long ym2612Clock,
        long ym2151Clock,
        long ym2203Clock,
        long ym2608Clock,
        long ym2610Clock,
        long gameBoyDmgClock,
        long huC6280Clock,
        long ay8910Clock,
        long sccClock,
        List<VgmEvent> events,
        @Nullable String gd3Title) {}
