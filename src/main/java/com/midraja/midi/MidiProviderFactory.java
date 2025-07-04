package com.midraja.midi;

import com.midraja.midi.os.LinuxProvider;
import com.midraja.midi.os.MacProvider;
import com.midraja.midi.os.WindowsProvider;

public class MidiProviderFactory {
    public static MidiOutProvider createProvider() {
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("mac")) {
            return new MacProvider();
        } else if (os.contains("win")) {
            return new WindowsProvider();
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return new LinuxProvider();
        } else {
            throw new UnsupportedOperationException("Unsupported OS for native MIDI: " + os);
        }
    }
}