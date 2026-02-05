package com.midiraja.midi;

/**
 * Applies a global volume scaling to MIDI Control Change 7 (Channel Volume) messages.
 */
public class VolumeFilter extends MidiFilter {
    private volatile double volumeScale;

    public VolumeFilter(MidiProcessor next, double initialVolumeScale) {
        super(next);
        this.volumeScale = Math.max(0.0, Math.min(1.0, initialVolumeScale));
    }

    public double getVolumeScale() { return volumeScale; }

    public void adjust(double delta) {
        this.volumeScale = Math.max(0.0, Math.min(1.0, this.volumeScale + delta));
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (data == null || data.length < 3) {
            next.sendMessage(data);
            return;
        }

        int status = data[0] & 0xFF;
        if (status < 0xF0) {
            int cmd = status & 0xF0;
            // CC 7 is Channel Volume
            if (cmd == 0xB0 && data[1] == 7 && volumeScale != 1.0) {
                byte[] out = data.clone();
                int vol = (int)((out[2] & 0xFF) * volumeScale);
                out[2] = (byte) Math.max(0, Math.min(127, vol));
                next.sendMessage(out);
                return;
            }
        }
        next.sendMessage(data);
    }
}
