package com.midiraja.midi;

/**
 * A marker interface indicating that this node is the terminal end of a MIDI processing pipeline.
 * It consumes MIDI events but does not pass them on to any further processors.
 */
public interface MidiSink extends MidiProcessor {
}
