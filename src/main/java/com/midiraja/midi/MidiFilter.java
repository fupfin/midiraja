package com.midiraja.midi;

/**
 * A base class for a MIDI processing node that passes data to the next processor in the chain.
 */
public abstract class MidiFilter implements MidiProcessor {
    protected final MidiProcessor next;

    public MidiFilter(MidiProcessor next) {
        this.next = next;
    }
}
