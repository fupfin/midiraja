/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory and preset registry for {@link ChipHandler} lists.
 *
 * <p>
 * Named presets (e.g. {@code "msx"}) map to canonical {@link ChipType} lists.
 * {@link #parseChips(String)} converts a {@code "+"} or {@code ","}-separated spec such as
 * {@code "ay8910+ym2413"} into a {@code List<ChipType>} for use with {@link #create}.
 */
public final class ChipHandlers
{
    /** Named presets: system name → ordered chip list. */
    public static final Map<String, List<ChipType>> PRESETS = Map.of(
            "zxspectrum", List.of(ChipType.AY8910, ChipType.AY8910),
            "fmpac",      List.of(ChipType.YM2413),
            "msx",        List.of(ChipType.YM2413, ChipType.AY8910),
            "msx-scc",    List.of(ChipType.SCCI, ChipType.AY8910),
            "sb16",       List.of(ChipType.OPL3),
            "genesis",    List.of(ChipType.YM2612),
            "megadrive",  List.of(ChipType.YM2612)
    );

    private ChipHandlers()
    {
    }

    /**
     * Creates handler instances for the given {@link ChipSpec}.
     *
     * @param spec
     *            chip specification produced by {@link #parseChips(String)}
     * @return list of handlers in the same order
     */
    public static List<ChipHandler> create(ChipSpec spec)
    {
        return create(spec.chips());
    }

    /**
     * Creates handler instances for the given chip list.
     * Multiple occurrences of {@link ChipType#AY8910} produce handlers with ascending chip indices.
     *
     * @param chips
     *            ordered list of chip types (may contain duplicates for dual-chip setups)
     * @return list of handlers in the same order; FM handlers precede PSG/wavetable handlers
     */
    public static List<ChipHandler> create(List<ChipType> chips)
    {
        var handlers = new ArrayList<ChipHandler>();
        int ayIndex = 0;
        for (var chip : chips)
        {
            handlers.add(switch (chip)
            {
                case AY8910 -> new Ay8910Handler(ayIndex++);
                case YM2413 -> new Ym2413Handler();
                case SCC -> new SccHandler();
                case SCCI -> new SccHandler(true);
                case OPL3 -> new Opl3Handler();
                case SN76489 -> throw new UnsupportedOperationException(
                    "SN76489 handler not yet implemented");
                case YM2612 -> throw new UnsupportedOperationException(
                    "YM2612 requires Ym2612VgmExporter, not CompositeVgmExporter");
            });
        }
        return handlers;
    }

    /**
     * Parses a chip specification string into a {@link ChipSpec}.
     *
     * <p>
     * The separator determines the {@link RoutingMode}:
     * <ul>
     *   <li>{@code >} → {@link RoutingMode#SEQUENTIAL} (e.g. {@code "scc>ay8910"})
     *   <li>{@code +} or {@code ,} → {@link RoutingMode#CHANNEL} (e.g. {@code "scc+ay8910"})
     * </ul>
     *
     * @param spec
     *            e.g. {@code "ay8910+ym2413"}, {@code "scc>ay8910"}, or {@code "opl3"}
     * @return chip spec with ordered chip list and routing mode
     * @throws IllegalArgumentException
     *             if any token does not match a known chip name
     */
    public static ChipSpec parseChips(String spec)
    {
        RoutingMode mode = spec.contains(">") ? RoutingMode.SEQUENTIAL : RoutingMode.CHANNEL;
        String[] tokens = spec.split("[+,>]");
        var result = new ArrayList<ChipType>(tokens.length);
        for (String token : tokens)
        {
            String name = token.trim().toUpperCase();
            try
            {
                result.add(ChipType.valueOf(name));
            }
            catch (IllegalArgumentException e)
            {
                throw new IllegalArgumentException("Unknown chip: '" + token.trim() + "'");
            }
        }
        if (result.isEmpty())
            throw new IllegalArgumentException("Chip spec must not be empty");
        return new ChipSpec(result, mode);
    }
}
