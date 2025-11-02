/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.cli;

import com.midiraja.MidirajaCommand;
import com.midiraja.midi.MidiProviderFactory;

import org.jspecify.annotations.Nullable;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 * Lists all available MIDI output ports for the native OS MIDI provider.
 */
@Command(name = "ports", mixinStandardHelpOptions = true, description = "List available MIDI output ports.")
public class ListPortsCommand implements Runnable {

    @ParentCommand
    @Nullable
    private MidirajaCommand parent;

    @Override
    public void run() {
        var p = java.util.Objects.requireNonNull(parent);
        var provider = MidiProviderFactory.createProvider();
        var ports = provider.getOutputPorts();
        p.getOut().println("Available MIDI Output Devices:");
        for (var port : ports) {
            p.getOut().println("[" + port.index() + "] " + port.name());
        }
    }
}
