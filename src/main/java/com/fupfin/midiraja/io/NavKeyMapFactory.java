/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

/**
 * Factory for JLine {@link KeyMap}s with the shared navigation bindings used across all
 * interactive terminal screens.
 *
 * <p>The standard set binds: UP arrow, DOWN arrow, Enter (SELECT), ESC and Ctrl+C (QUIT).
 * Callers add extra bindings after the factory call via {@code km.bind(...)}.
 *
 * <p>ISIG must be disabled before the map is used so that {@code \x03} is delivered as a
 * character rather than generating SIGINT.
 */
public final class NavKeyMapFactory
{
    private NavKeyMapFactory()
    {}

    /**
     * Builds a {@link KeyMap} with standard UP, DOWN, SELECT, and QUIT bindings.
     *
     * @param terminal     live terminal used to resolve terminfo capability sequences
     * @param upAction     bound to the UP arrow key
     * @param downAction   bound to the DOWN arrow key
     * @param selectAction bound to Enter (\r and \n)
     * @param quitAction   bound to ESC, Ctrl+C (\003), "q", and "Q"
     */
    public static <T> KeyMap<T> buildNavKeyMap(Terminal terminal,
            T upAction, T downAction, T selectAction, T quitAction)
    {
        var km = new KeyMap<T>();
        km.setAmbiguousTimeout(100);
        bindArrow(km, terminal, InfoCmp.Capability.key_up,   upAction,   "A");
        bindArrow(km, terminal, InfoCmp.Capability.key_down, downAction, "B");
        km.bind(selectAction, "\r", "\n");
        // ESC alone → quit; disambiguated from ESC-sequences by the 100ms ambiguous timeout
        km.bind(quitAction, KeyMap.esc());
        // Ctrl+C (ETX = \x03) → quit. ISIG must be disabled so this arrives as a character
        // instead of generating SIGINT.
        km.bind(quitAction, "\003");
        km.bind(quitAction, "q", "Q");
        return km;
    }

    /**
     * Binds an arrow key using the terminfo capability string plus explicit ANSI CSI and SS3
     * fallback sequences.
     */
    static <T> void bindArrow(KeyMap<T> km, Terminal terminal,
            InfoCmp.Capability cap, T action, String letter)
    {
        String capSeq = KeyMap.key(terminal, cap);
        if (capSeq != null && !capSeq.isEmpty())
            km.bind(action, capSeq);
        km.bind(action, "\033[" + letter, "\033O" + letter);
    }
}
