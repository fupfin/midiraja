/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.util.List;

/**
 * Chip combination together with its voice-allocation strategy.
 *
 * <p>
 * Produced by {@link ChipHandlers#parseChips(String)} from a {@code --chips} spec string.
 */
public record ChipSpec(List<ChipType> chips, RoutingMode mode)
{
}
