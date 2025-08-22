/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.io;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JLineTerminalIOTest
{
    @Test
    void testDefaultDimensionsWhenUninitialized()
    {
        JLineTerminalIO io = new JLineTerminalIO();
        assertEquals(80, io.getWidth(), "Uninitialized width should default to 80");
        assertEquals(24, io.getHeight(), "Uninitialized height should default to 24");
    }

    @Test
    void testDimensionsAfterInit() throws IOException
    {
        // Skip terminal initialization if running in a headless CI environment
        if (System.getenv("CI") != null) return;
        
        JLineTerminalIO io = new JLineTerminalIO();
        try {
            io.init();
            // A real terminal will likely return sizes > 0
            // Even if a dumb terminal returns 0, JLine handles terminal size querying
            // The exact size depends on the host executing the test, so we just test it doesn't crash
            io.getWidth();
            io.getHeight();
        } finally {
            io.close();
        }
    }
}
