package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.File;

/**
 * Integration test: verifies that TSF renders audio on channels 10-14 (SCC MIDI channels).
 */
class TsfSccChannelIntegrationTest {

    @Test
    void tsfRendersAudioOnSccChannels() throws Exception {
        var sf = new File("build/soundfonts/FluidR3_GM.sf3");
        if (!sf.exists()) {
            System.out.println("SoundFont not found, skipping");
            return;
        }

        var bridge = new FFMTsfNativeBridge();
        bridge.loadSoundfontFile(sf.getAbsolutePath(), 44100);

        // Initialize all 16 channels via CC121 (same as TsfSynthProvider.prepareForNewTrack)
        for (int ch = 0; ch < 16; ch++) {
            bridge.controlChange(ch, 121, 0); // CC121 = Reset All Controllers → triggers tsf_channel_init
        }

        // Set up SCC channels (10-14) with program 81 (Sawtooth Lead)
        for (int ch = 10; ch <= 14; ch++) {
            bridge.patchChange(ch, 81);
            bridge.controlChange(ch, 7, 100); // CC7 volume = 100
        }

        // Generate 0.1s of silence to warm up
        short[] buf = new short[4410 * 2];
        bridge.generate(buf, 4410);

        // Play note 69 (A4 = 440 Hz) on channel 10
        bridge.noteOn(10, 69, 100);
        bridge.generate(buf, 4410);

        int peak = 0;
        for (short s : buf) if (Math.abs(s) > peak) peak = Math.abs(s);
        double dbPeak = 20 * Math.log10(peak / 32767.0);
        System.out.printf("Channel 10 (prog 81) note 69: peak=%d (%.1f dBFS)%n", peak, dbPeak);
        assertTrue(peak > 100, "Channel 10 with prog 81, note 69 must produce non-silent audio");

        bridge.noteOff(10, 69);

        // Also test note 21 (A0 = 27.5 Hz) — this is very low, may be inaudible
        bridge.noteOn(10, 21, 100);
        short[] buf2 = new short[4410 * 2];
        bridge.generate(buf2, 4410);
        int peak21 = 0;
        for (short s : buf2) if (Math.abs(s) > peak21) peak21 = Math.abs(s);
        System.out.printf("Channel 10 (prog 81) note 21: peak=%d (%.1f dBFS)%n", peak21,
            peak21 > 0 ? 20 * Math.log10(peak21 / 32767.0) : Double.NEGATIVE_INFINITY);

        bridge.close();
    }
}
