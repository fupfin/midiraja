package com.midiraja.midi;

import javax.sound.midi.SysexMessage;

/**
 * Filters out hardware-specific System Exclusive (SysEx) messages.
 */
public class SysexFilter extends MidiFilter {
    private volatile boolean ignoreSysex;

    public SysexFilter(MidiProcessor next, boolean initialIgnoreSysex) {
        super(next);
        this.ignoreSysex = initialIgnoreSysex;
    }

    public boolean isIgnoreSysex() { return ignoreSysex; }
    public void setIgnoreSysex(boolean ignoreSysex) { this.ignoreSysex = ignoreSysex; }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            next.sendMessage(data);
            return;
        }

        int status = data[0] & 0xFF;
        if (ignoreSysex && status == 0xF0) {
            // Drop the message
            return;
        }

        next.sendMessage(data);
    }
}
