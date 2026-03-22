/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import static org.junit.jupiter.api.Assertions.*;

import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

class NavKeyMapFactoryTest
{
    @Test
    void ctrlC_isBoundToQuit() throws Exception
    {
        try (Terminal dumb = TerminalBuilder.builder().dumb(true).build())
        {
            var km = NavKeyMapFactory.buildNavKeyMap(dumb, "UP", "DOWN", "SELECT", "QUIT");
            assertEquals("QUIT", km.getBound("\003"),
                    "Ctrl+C (\\x03) must be bound to QUIT — ISIG must be disabled at the call site");
        }
    }

    @Test
    void esc_isBoundToQuit() throws Exception
    {
        try (Terminal dumb = TerminalBuilder.builder().dumb(true).build())
        {
            var km = NavKeyMapFactory.buildNavKeyMap(dumb, "UP", "DOWN", "SELECT", "QUIT");
            assertEquals("QUIT", km.getBound(KeyMap.esc()));
        }
    }

    @Test
    void q_isBoundToQuit() throws Exception
    {
        try (Terminal dumb = TerminalBuilder.builder().dumb(true).build())
        {
            var km = NavKeyMapFactory.buildNavKeyMap(dumb, "UP", "DOWN", "SELECT", "QUIT");
            assertEquals("QUIT", km.getBound("q"));
            assertEquals("QUIT", km.getBound("Q"));
        }
    }

    @Test
    void enter_isBoundToSelect() throws Exception
    {
        try (Terminal dumb = TerminalBuilder.builder().dumb(true).build())
        {
            var km = NavKeyMapFactory.buildNavKeyMap(dumb, "UP", "DOWN", "SELECT", "QUIT");
            assertEquals("SELECT", km.getBound("\r"));
            assertEquals("SELECT", km.getBound("\n"));
        }
    }

    @Test
    void ansiArrowUp_isBoundToUp() throws Exception
    {
        try (Terminal dumb = TerminalBuilder.builder().dumb(true).build())
        {
            var km = NavKeyMapFactory.buildNavKeyMap(dumb, "UP", "DOWN", "SELECT", "QUIT");
            assertEquals("UP", km.getBound("\033[A"));
            assertEquals("UP", km.getBound("\033OA"));
        }
    }

    @Test
    void ansiArrowDown_isBoundToDown() throws Exception
    {
        try (Terminal dumb = TerminalBuilder.builder().dumb(true).build())
        {
            var km = NavKeyMapFactory.buildNavKeyMap(dumb, "UP", "DOWN", "SELECT", "QUIT");
            assertEquals("DOWN", km.getBound("\033[B"));
            assertEquals("DOWN", km.getBound("\033OB"));
        }
    }

    @Test
    void extraBindingsCanBeAddedAfterBuild() throws Exception
    {
        try (Terminal dumb = TerminalBuilder.builder().dumb(true).build())
        {
            var km = NavKeyMapFactory.buildNavKeyMap(dumb, "UP", "DOWN", "SELECT", "QUIT");
            km.bind("DELETE", "d", "D");
            assertEquals("DELETE", km.getBound("d"));
            assertEquals("DELETE", km.getBound("D"));
            // Existing bindings must not be affected
            assertEquals("QUIT", km.getBound("q"));
        }
    }
}
