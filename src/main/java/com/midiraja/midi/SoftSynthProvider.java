/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * A provider that pipes raw MIDI bytes to a Software Synthesizer (e.g., fluidsynth, timidity)
 * running as a child process. This provides a robust way to integrate powerful synths 
 * without complex FFM/C-library dependencies or GraalVM AOT audio conflicts.
 */
public class SoftSynthProvider implements MidiOutProvider {
    private final String command;
    @Nullable private Process process;
    @Nullable private OutputStream synthInput;

    public SoftSynthProvider(String command) {
        this.command = command;
    }

    @Override
    public List<MidiPort> getOutputPorts() {
        // Extract just the executable name (e.g. "fluidsynth") for the UI
        int spaceIndex = command.indexOf(' ');
        String exeName = spaceIndex > 0 ? command.substring(0, spaceIndex) : command;
        return List.of(new MidiPort(0, "SoftSynth: " + exeName));
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        ProcessBuilder pb;
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }

        // Redirect stderr to stdout so we only have one stream to consume
        pb.redirectErrorStream(true);
        
        Process p = pb.start();
        this.process = p;
        this.synthInput = p.getOutputStream();

        // Start a daemon thread to consume the soft synth's text output.
        // This is CRITICAL: if we don't consume it, the OS pipe buffer will fill up,
        // blocking the synth process forever. It also prevents the synth's text
        // from printing to the terminal and corrupting our JLine TUI.
        Thread outputConsumer = new Thread(() -> {
            try (InputStream is = p.getInputStream()) {
                byte[] buffer = new byte[1024];
                while (is.read(buffer) != -1) {
                    // Just discarding for now.
                    // Future enhancement: parse and display this in the TUI's log panel.
                }
            } catch (Exception ignored) {}
        });
        outputConsumer.setDaemon(true);
        outputConsumer.setName("SoftSynthOutputConsumer");
        outputConsumer.start();
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (synthInput != null) {
            synthInput.write(data);
            synthInput.flush();
        }
    }

    @Override
    public void closePort() {
        if (synthInput != null) {
            try { synthInput.close(); } catch (Exception ignored) {}
        }
        if (process != null) {
            process.destroy(); // Gracefully ask it to stop
            try {
                // Give it a tiny bit of time to shut down gracefully
                if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly(); // Kill it if it hangs
                }
            } catch (InterruptedException ignored) {}
        }
    }
}
