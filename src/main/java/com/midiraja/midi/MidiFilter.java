package com.midiraja.midi;

/**
 * A base class for a MIDI processing node that passes data to the next node in the chain.
 */
public abstract class MidiFilter implements MidiSink {
    protected final MidiSink next;

    public MidiFilter(MidiSink next) {
        this.next = next;
    }
}
