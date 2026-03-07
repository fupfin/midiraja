/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import picocli.CommandLine.Option;

/**
 * Shared FM-synthesizer options mixed into OplCommand and OpnCommand.
 */
public class FmSynthOptions
{
    @Option(names = {"-e", "--emulator"}, defaultValue = "0",
            description = "Emulator backend ID. See the command footer for available IDs.")
    public int emulator = 0;

    @Option(names = {"-c", "--chips"}, defaultValue = "4",
            description = "Number of chips to emulate (default: 4). More chips = more polyphony.")
    public int chips = 4;


}
