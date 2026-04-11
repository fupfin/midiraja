/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * Voice allocation strategy for {@link CompositeVgmExporter}.
 *
 * <p>
 * Selected via the {@code --chips} separator:
 * <ul>
 *   <li>{@code chip1+chip2} → {@link #CHANNEL}: MIDI channels are round-robin distributed
 *       across handlers; PSG-preferred programs (GM 112-127) still prefer AY8910.
 *   <li>{@code chip1>chip2} → {@link #SEQUENTIAL}: handler 0 is filled first; PSG-preferred
 *       programs prefer AY8910 regardless of order.
 * </ul>
 */
public enum RoutingMode
{
    /** MIDI channels are round-robin assigned to handlers ({@code +} separator). */
    CHANNEL,

    /** Handlers are filled in order; handler 0 is preferred ({@code >} separator). */
    SEQUENTIAL
}
