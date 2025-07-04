package com.midraja.midi;

public record MidiPort(int index, String name) {
    @Override
    public String toString() {
        return String.format("[%d] %s", index, name);
    }
}