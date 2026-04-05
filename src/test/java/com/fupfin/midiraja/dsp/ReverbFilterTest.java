package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ReverbFilterTest
{

    /** Sink that captures the last processed buffer. */
    private static class CaptureSink implements AudioProcessor
    {
        float[] left, right;

        @Override
        public void process(float[] l, float[] r, int frames)
        {
            left = l.clone();
            right = r.clone();
        }

        @Override
        public void processInterleaved(short[] pcm, int frames, int ch)
        {
        }

        @Override
        public void reset()
        {
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static float[] fill(int n, float v)
    {
        float[] a = new float[n];
        Arrays.fill(a, v);
        return a;
    }

    private static double rms(float[] buf)
    {
        double sum = 0;
        for (float v : buf)
            sum += (double) v * v;
        return Math.sqrt(sum / buf.length);
    }

    private static double maxAbs(float[] buf)
    {
        double m = 0;
        for (float v : buf)
            m = Math.max(m, Math.abs(v));
        return m;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void disabled_passes_signal_unchanged()
    {
        var sink = new CaptureSink();
        var filter = new ReverbFilter(sink, ReverbFilter.Preset.ROOM);
        filter.setEnabled(false);

        float[] left = fill(256, 0.5f);
        float[] right = fill(256, -0.3f);
        filter.process(left, right, 256);

        assertArrayEquals(fill(256, 0.5f), sink.left, 0.0f);
        assertArrayEquals(fill(256, -0.3f), sink.right, 0.0f);
    }

    @Test
    void enabled_produces_output()
    {
        var sink = new CaptureSink();
        var filter = new ReverbFilter(sink, ReverbFilter.Preset.ROOM);

        filter.process(fill(256, 0.5f), fill(256, 0.5f), 256);

        assertTrue(rms(sink.left) > 0, "Enabled reverb should produce non-zero output");
        assertTrue(rms(sink.right) > 0, "Enabled reverb should produce non-zero output");
    }

    @Test
    void reverb_adds_wet_component()
    {
        // Process through reverb and compare RMS to a plain passthrough
        var reverbSink = new CaptureSink();
        var bypassSink = new CaptureSink();
        var reverbFilter = new ReverbFilter(reverbSink, ReverbFilter.Preset.ROOM);
        var bypassFilter = new ReverbFilter(bypassSink, ReverbFilter.Preset.ROOM);
        bypassFilter.setEnabled(false);

        float[] signal = fill(1024, 0.5f);
        reverbFilter.process(signal.clone(), signal.clone(), 1024);
        bypassFilter.process(signal.clone(), signal.clone(), 1024);

        // Reverb blends wet + dry, so the output should differ from pure passthrough
        assertFalse(Arrays.equals(reverbSink.left, bypassSink.left),
                "Reverb-enabled output should differ from passthrough");
    }

    @Test
    void disabled_then_enabled_activates_reverb()
    {
        var sink = new CaptureSink();
        var filter = new ReverbFilter(sink, ReverbFilter.Preset.HALL);

        filter.setEnabled(false);
        float[] signal = fill(256, 0.5f);
        filter.process(signal.clone(), signal.clone(), 256);
        assertArrayEquals(fill(256, 0.5f), sink.left, 0.0f);

        filter.setEnabled(true);
        filter.process(signal.clone(), signal.clone(), 256);
        assertFalse(Arrays.equals(fill(256, 0.5f), sink.left),
                "Re-enabled reverb should modify the signal");
    }

    @Test
    void reverb_tail_persists_after_silence()
    {
        // Comb buffers hold up to ~1640 samples; feed a burst then silence over 4096 frames each.
        var sink = new CaptureSink();
        var filter = new ReverbFilter(sink, ReverbFilter.Preset.ROOM);

        // Burst: fill comb and allpass delay lines
        filter.process(fill(4096, 0.8f), fill(4096, 0.8f), 4096);

        // Silence: reverb tail should still be non-zero
        float[] silenceL = new float[4096];
        float[] silenceR = new float[4096];
        filter.process(silenceL, silenceR, 4096);

        // The output after silence should contain reverb tail energy
        assertTrue(maxAbs(sink.left) > 1e-5f,
                "Reverb tail should persist after silence, got max=" + maxAbs(sink.left));
    }

    @Test
    void stereo_spread_left_right_differ()
    {
        var sink = new CaptureSink();
        var filter = new ReverbFilter(sink, ReverbFilter.Preset.PLATE);

        // Feed identical L/R signal — comb delays differ by 23 samples per channel
        float[] signal = fill(2048, 0.5f);
        filter.process(signal.clone(), signal.clone(), 2048);

        assertFalse(Arrays.equals(sink.left, sink.right),
                "Left and right outputs should differ due to different comb delays");
    }

    @Test
    void full_wet_still_passes_dry()
    {
        // scaleDry = max(0.1, 1.0 - scaleWet*0.6); even at max wet, dry >= 0.1
        // CAVE has preset.wet=0.45; with levelScale=1.0, scaleWet=min(1.0,0.45)=0.45 → scaleDry=0.73
        // Use a custom high-wet-looking preset: wet=1.0 with levelScale=1.0 → scaleWet=1.0, scaleDry=0.4
        var sink = new CaptureSink();
        // ROOM preset: wet=0.25, scaleDry = max(0.1, 1.0 - 0.25*0.6) = max(0.1, 0.85) = 0.85
        var filter = new ReverbFilter(sink, ReverbFilter.Preset.ROOM, 4.0f);
        // With levelScale=4.0: scaleWet = min(1.0, 0.25*4.0) = min(1.0, 1.0) = 1.0
        // scaleDry = max(0.1, 1.0 - 1.0*0.6) = max(0.1, 0.4) = 0.4 (non-zero)

        float[] signal = fill(256, 0.5f);
        filter.process(signal.clone(), signal.clone(), 256);

        // Output must be non-zero because dry component is always at least 0.1
        assertTrue(maxAbs(sink.left) > 0, "Dry component ensures output is non-zero");
    }

    @Test
    void all_presets_produce_output()
    {
        for (var preset : ReverbFilter.Preset.values())
        {
            var sink = new CaptureSink();
            var filter = new ReverbFilter(sink, preset);
            assertDoesNotThrow(() -> filter.process(fill(256, 0.5f), fill(256, 0.5f), 256),
                    "Preset " + preset + " threw exception");
            assertTrue(rms(sink.left) > 0, "Preset " + preset + " should produce non-zero output");
        }
    }

    @Test
    void setPreset_changes_reverb_character()
    {
        var sinkRoom = new CaptureSink();
        var sinkCave = new CaptureSink();

        // Process identical input with ROOM, then switch to CAVE and process again (fresh filter)
        var filterRoom = new ReverbFilter(sinkRoom, ReverbFilter.Preset.ROOM);
        var filterCave = new ReverbFilter(sinkCave, ReverbFilter.Preset.CAVE);

        float[] signal = fill(1024, 0.5f);
        filterRoom.process(signal.clone(), signal.clone(), 1024);
        filterCave.process(signal.clone(), signal.clone(), 1024);

        // CAVE has much higher feedback (roomSize=0.98 vs 0.5) → outputs should differ
        assertFalse(Arrays.equals(sinkRoom.left, sinkCave.left),
                "ROOM and CAVE presets should produce different reverb outputs");
    }

    @Test
    void levelScale_scales_wet_mix()
    {
        var sinkLow = new CaptureSink();
        var sinkHigh = new CaptureSink();
        float[] signal = fill(512, 0.5f);

        // levelScale=0.1: scaleWet = min(1.0, HALL.wet*0.1) = min(1.0, 0.35*0.1) = 0.035
        var filterLow = new ReverbFilter(sinkLow, ReverbFilter.Preset.HALL, 0.1f);
        // levelScale=1.0: scaleWet = min(1.0, HALL.wet*1.0) = min(1.0, 0.35) = 0.35
        var filterHigh = new ReverbFilter(sinkHigh, ReverbFilter.Preset.HALL, 1.0f);

        filterLow.process(signal.clone(), signal.clone(), 512);
        filterHigh.process(signal.clone(), signal.clone(), 512);

        // Higher levelScale → more wet contribution → RMS differs
        assertFalse(Arrays.equals(sinkLow.left, sinkHigh.left),
                "Different levelScale values should produce different wet mixes");
    }

    @Test
    void null_next_does_not_throw()
    {
        var filter = new ReverbFilter(null, ReverbFilter.Preset.ROOM);
        assertDoesNotThrow(() -> filter.process(fill(256, 0.5f), fill(256, 0.5f), 256));
    }

    @Test
    void next_processor_is_called()
    {
        var sink = new CaptureSink();
        var filter = new ReverbFilter(sink, ReverbFilter.Preset.SPRING);

        float[] signal = fill(128, 0.4f);
        filter.process(signal.clone(), signal.clone(), 128);

        assertNotNull(sink.left, "next processor should have been called with left channel");
        assertNotNull(sink.right, "next processor should have been called with right channel");
        assertEquals(128, sink.left.length);
    }
}
