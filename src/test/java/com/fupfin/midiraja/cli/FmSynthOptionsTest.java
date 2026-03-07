/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Tests for FmSynthOptions default values and picocli binding.
 */
class FmSynthOptionsTest
{
    @Command(name = "test-fm")
    static class FmTestCommand implements Runnable
    {
        @Mixin
        FmSynthOptions fmOptions = new FmSynthOptions();

        @Override
        public void run()
        {
        }
    }

    @Test
    void defaultEmulatorIsZero()
    {
        FmSynthOptions opts = new FmSynthOptions();

        assertEquals(0, opts.emulator, "Default emulator ID should be 0");
    }

    @Test
    void defaultChipsIsFour()
    {
        FmSynthOptions opts = new FmSynthOptions();

        assertEquals(4, opts.chips, "Default chip count should be 4");
    }

    @Test
    void picocliBindsEmulatorOption()
    {
        FmTestCommand cmd = new FmTestCommand();
        new CommandLine(cmd).execute("-e", "3");

        assertEquals(3, cmd.fmOptions.emulator,
                "-e option should set emulator to 3");
    }

    @Test
    void picocliBindsEmulatorLongOption()
    {
        FmTestCommand cmd = new FmTestCommand();
        new CommandLine(cmd).execute("--emulator", "5");

        assertEquals(5, cmd.fmOptions.emulator,
                "--emulator option should set emulator to 5");
    }

    @Test
    void picocliBindsChipsOption()
    {
        FmTestCommand cmd = new FmTestCommand();
        new CommandLine(cmd).execute("-c", "8");

        assertEquals(8, cmd.fmOptions.chips,
                "-c option should set chips to 8");
    }
}
