package com.midiraja.midi;

/**
 * The base interface for any node that can process or consume a raw MIDI message.
 */
public interface MidiProcessor {
    /**
     * Processes, modifies, or consumes a raw MIDI message.
     */
    void sendMessage(byte[] data) throws Exception;
}
