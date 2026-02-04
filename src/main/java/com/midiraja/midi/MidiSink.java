package com.midiraja.midi;

/**
 * A destination or processor for raw MIDI messages.
 */
public interface MidiSink {
    /**
     * Processes, modifies, or consumes a raw MIDI message.
     */
    void sendMessage(byte[] data) throws Exception;
}
