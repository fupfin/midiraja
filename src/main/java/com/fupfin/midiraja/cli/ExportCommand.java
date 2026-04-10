/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "export", mixinStandardHelpOptions = true, description = "Export a music file to MIDI or VGM format.", subcommands = {
        ExportMidiCommand.class, ExportVgmCommand.class,
        CommandLine.HelpCommand.class })
public class ExportCommand implements Runnable
{
    @Override
    public void run()
    {
        new CommandLine(this).usage(System.out);
    }
}
