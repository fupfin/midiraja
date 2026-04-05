/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import picocli.CommandLine.Option;

public class UiModeOptions
{
    @Option(names = { "-1", "--classic" }, description = "Classic CLI mode (static line logging, best for pipes).")
    public boolean classicMode;

    @Option(names = { "-2", "--mini" }, description = "Mini TUI mode (single-line interactive status).")
    public boolean miniMode;

    @Option(names = { "-3", "--full" }, description = "Full TUI dashboard (default if terminal is large enough).")
    public boolean fullMode;
}
