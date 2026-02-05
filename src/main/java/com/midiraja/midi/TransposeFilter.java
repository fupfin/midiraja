package com.midiraja.midi;

/**
 * Applies a pitch transposition to MIDI Note On and Note Off messages.
 * Drum channel (10) is ignored.
 */
public class TransposeFilter extends MidiFilter {
    private volatile int semitones = 0;

    public TransposeFilter(MidiProcessor next) {
        super(next);
    }
    
    public TransposeFilter(MidiProcessor next, int initialSemitones) {
        super(next);
        this.semitones = initialSemitones;
    }

    public int getSemitones() { return semitones; }
    public void setSemitones(int semitones) { this.semitones = semitones; }
    public void adjust(int delta) { this.semitones += delta; }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (data == null || data.length < 2) {
            next.sendMessage(data);
            return;
        }

        int status = data[0] & 0xFF;
        if (status < 0xF0) {
            int cmd = status & 0xF0;
            int ch = status & 0x0F;

            // Transpose Note On (0x90) and Note Off (0x80), but skip channel 10 (drums, index 9)
            if (ch != 9 && (cmd == 0x90 || cmd == 0x80) && semitones != 0) {
                byte[] out = data.clone();
                int note = (out[1] & 0xFF) + semitones;
                out[1] = (byte) Math.max(0, Math.min(127, note));
                next.sendMessage(out);
                return;
            }
        }
        next.sendMessage(data);
    }
}
