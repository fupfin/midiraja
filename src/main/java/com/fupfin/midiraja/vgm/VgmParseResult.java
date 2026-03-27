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
 * @param sn76489Clock SN76489 clock in Hz, 0 if chip absent
 * @param ym2612Clock YM2612 clock in Hz, 0 if chip absent
 * @param events chip events in chronological order
 * @param gd3Title track title from GD3 tag, null if absent
 */
public record VgmParseResult(
        int vgmVersion,
        long sn76489Clock,
        long ym2612Clock,
        List<VgmEvent> events,
        @Nullable String gd3Title) {}
