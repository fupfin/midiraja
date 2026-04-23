/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.nio.file.Path;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Thread-local FM bank override used by VGM conversion commands.
 *
 * <p>
 * Supported specs:
 * <ul>
 *   <li>Single path by extension: {@code .wopl} (OPL), {@code .wopn} (OPN), {@code .bin} (OPM)
 *   <li>Prefixed path: {@code opl:/path/to/bank.wopl}, {@code opn:/path/to/bank.wopn},
 *       {@code opm:/path/to/opm_gm.bin}
 *   <li>Multiple specs separated by comma:
 *       {@code opm:/x/opm.bin,opn:/x/gm.wopn,opl:/x/fatman.wopl}
 * </ul>
 */
public final class FmBankOverride
{
    private static final String OPL = "opl";
    private static final String OPN = "opn";
    private static final String OPM = "opm";

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private FmBankOverride()
    {
    }

    /**
     * Applies a bank override spec for the current thread.
     * Empty/blank input clears the override.
     */
    public static void apply(String spec)
    {
        State state = STATE.get();
        state.clear();
        if (spec == null || spec.isBlank())
            return;

        for (String token : spec.split(","))
        {
            String t = token.trim();
            if (t.isEmpty())
                continue;

            int colon = t.indexOf(':');
            if (colon > 0)
            {
                String prefix = t.substring(0, colon).toLowerCase();
                String value = t.substring(colon + 1).trim();
                if (value.isEmpty())
                    throw new IllegalArgumentException("Bank path is empty in token: " + t);
                if (prefix.equals(OPL) || prefix.equals(OPN) || prefix.equals(OPM))
                {
                    assignByType(state, prefix, value);
                    continue;
                }
            }
            assignByExtension(state, t);
        }
    }

    /** Clears overrides for the current thread. */
    public static void clear()
    {
        STATE.get().clear();
    }

    public static Optional<Path> oplBankPath()
    {
        return Optional.ofNullable(STATE.get().oplPath);
    }

    public static Optional<Path> opnBankPath()
    {
        return Optional.ofNullable(STATE.get().opnPath);
    }

    public static Optional<Path> opmBankPath()
    {
        return Optional.ofNullable(STATE.get().opmPath);
    }

    private static void assignByType(State state, String type, String value)
    {
        Path path = Path.of(value).toAbsolutePath().normalize();
        switch (type)
        {
            case OPL -> state.oplPath = path;
            case OPN -> state.opnPath = path;
            case OPM -> state.opmPath = path;
            default -> throw new IllegalArgumentException("Unsupported bank type: " + type);
        }
    }

    private static void assignByExtension(State state, String value)
    {
        String lower = value.toLowerCase();
        if (lower.endsWith(".wopl"))
        {
            assignByType(state, OPL, value);
            return;
        }
        if (lower.endsWith(".wopn"))
        {
            assignByType(state, OPN, value);
            return;
        }
        if (lower.endsWith(".bin"))
        {
            assignByType(state, OPM, value);
            return;
        }
        throw new IllegalArgumentException(
                "Cannot infer bank type from '" + value
                        + "'. Use extension (.wopl/.wopn/.bin) or prefix (opl:/ opn:/ opm:/).");
    }

    private static final class State
    {
        private @Nullable Path oplPath;
        private @Nullable Path opnPath;
        private @Nullable Path opmPath;

        private void clear()
        {
            oplPath = null;
            opnPath = null;
            opmPath = null;
        }
    }
}
