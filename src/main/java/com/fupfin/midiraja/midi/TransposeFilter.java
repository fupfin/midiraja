package com.fupfin.midiraja.midi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies a pitch transposition to MIDI Note On and Note Off messages. Drum channel (10) is
 * ignored.
 */
public class TransposeFilter extends MidiFilter
{
    private final AtomicInteger semitones;

    public TransposeFilter(MidiProcessor next)
    {
        super(next);
        this.semitones = new AtomicInteger(0);
    }

    public TransposeFilter(MidiProcessor next, int initialSemitones)
    {
        super(next);
        this.semitones = new AtomicInteger(initialSemitones);
    }

    public int getSemitones()
    {
        return semitones.get();
    }

    public void setSemitones(int semitones)
    {
        this.semitones.set(semitones);
    }

    public void adjust(int delta)
    {
        this.semitones.addAndGet(delta);
    }

    @Override
    public void sendMessage(byte[] data) throws Exception
    {
        if (data == null || data.length < 2)
        {
            next.sendMessage(data);
            return;
        }

        int status = data[0] & 0xFF;
        if (status < 0xF0)
        {
            int cmd = status & 0xF0;
            int ch = status & 0x0F;

            // Transpose Note On (0x90) and Note Off (0x80), but skip channel 10 (drums, index 9)
            int s = semitones.get();
            if (ch != 9 && (cmd == 0x90 || cmd == 0x80) && s != 0)
            {
                byte[] out = data.clone();
                int note = (out[1] & 0xFF) + s;
                out[1] = (byte) Math.max(0, Math.min(127, note));
                next.sendMessage(out);
                return;
            }
        }
        next.sendMessage(data);
    }
}
