package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DynamicsCompressorTest
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

    // ── gain reduction ────────────────────────────────────────────────────────

    @Test
    void signal_aboveThreshold_isAttenuated()
    {
        var sink = new CaptureSink();
        var comp = new DynamicsCompressor(DynamicsCompressor.Preset.GENTLE, sink);

        // Warm up: let envelope follower settle at 1.0 (GENTLE attack=50ms, 4096 >> 2205 samples)
        comp.process(fill(4096, 1.0f), fill(4096, 1.0f), 4096);

        // Now envelope is settled → compression active from sample 0 of this buffer
        // GENTLE: threshold=-18 dBFS, ratio=2:1, makeup=+3 dB → net gain ≈ -6 dB (0.5×)
        float[] left = fill(256, 1.0f);
        float[] right = fill(256, 1.0f);
        comp.process(left, right, 256);

        double peak = maxAbs(sink.left);
        assertTrue(peak < 0.95, "Expected attenuation of loud signal, got peak=" + peak);
    }

    @Test
    void signal_belowThreshold_passesUnchanged()
    {
        var sink = new CaptureSink();
        // GENTLE threshold = -18 dBFS ≈ 0.126. Feed a signal well below: 0.01 amplitude.
        var comp = new DynamicsCompressor(DynamicsCompressor.Preset.GENTLE, sink);

        float[] left = fill(1024, 0.01f);
        float[] right = fill(1024, 0.01f);
        comp.process(left, right, 1024);

        // Output should be louder than input (makeup gain active, no compression)
        double inputPeak = 0.01;
        double outputPeak = maxAbs(sink.left);
        assertTrue(outputPeak > inputPeak,
                "Expected makeup gain on quiet signal, got output=" + outputPeak);
    }

    @Test
    void soft_preset_doesNotAttenuateBelowThreshold()
    {
        var sink = new CaptureSink();
        // SOFT threshold = -3 dBFS ≈ 0.708. Feed 0.5 amplitude → below threshold.
        var comp = new DynamicsCompressor(DynamicsCompressor.Preset.SOFT, sink);

        // Warm up envelope follower
        comp.process(fill(4096, 0.0f), fill(4096, 0.0f), 4096);

        float[] left = fill(256, 0.5f);
        float[] right = fill(256, 0.5f);
        comp.process(left, right, 256);

        // SOFT has 0 dB makeup gain, so output ≈ input (within 0.5 dB)
        double ratio = maxAbs(sink.left) / 0.5;
        assertTrue(ratio > 0.94 && ratio < 1.06,
                "SOFT below-threshold gain should be ~1.0, got " + ratio);
    }

    // ── stereo linking ────────────────────────────────────────────────────────

    @Test
    void stereoLinked_bothChannelsReceiveSameGain()
    {
        var sink = new CaptureSink();
        var comp = new DynamicsCompressor(DynamicsCompressor.Preset.MODERATE, sink);

        // Warm up: left loud, right quiet — let stereo-linked envelope settle near 1.0
        comp.process(fill(4096, 1.0f), fill(4096, 0.01f), 4096);

        // Now envelope is settled → right channel also sees full compression from sample 0
        // MODERATE: threshold=-18 dBFS, ratio=4:1, makeup=+6 dB → net gain ≈ -7.5 dB (0.42×)
        // Without stereo linking: right would use its own envelope (~0.01 = -40 dBFS, no compression)
        // → right output ≈ 0.01 * makeup(6 dB) = 0.02
        // With stereo linking: right output ≈ 0.01 * 0.42 = 0.0042 — well below 0.02
        float[] left = fill(64, 1.0f);
        float[] right = fill(64, 0.01f);
        comp.process(left, right, 64);

        double rightPeak = maxAbs(sink.right);
        assertTrue(rightPeak < 0.01,
                "Expected stereo-linked gain reduction on quiet channel, got " + rightPeak);
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    void reset_clearsEnvelopeState()
    {
        var sink = new CaptureSink();
        var comp = new DynamicsCompressor(DynamicsCompressor.Preset.AGGRESSIVE, sink);

        // Drive loud to build up envelope
        comp.process(fill(4096, 1.0f), fill(4096, 1.0f), 4096);

        comp.reset();

        // After reset, feed a moderate signal. Gain should be close to makeup-only (no residual compression).
        float[] left = fill(64, 0.01f);
        float[] right = fill(64, 0.01f);
        comp.process(left, right, 64);

        // AGGRESSIVE makeup = 9 dB ≈ 2.82×. Output should be near 0.01 * 2.82 = 0.0282.
        double expected = 0.01 * Math.pow(10.0, 9.0 / 20.0);
        double actual = maxAbs(sink.left);
        assertEquals(expected, actual, expected * 0.1,
                "After reset, gain should be near makeup-only");
    }

    // ── all presets smoke test ────────────────────────────────────────────────

    @Test
    void allPresets_processWithoutException()
    {
        float[] buf = fill(4096, 0.8f);
        for (var preset : DynamicsCompressor.Preset.values())
        {
            var comp = new DynamicsCompressor(preset, new CaptureSink());
            assertDoesNotThrow(() -> comp.process(buf.clone(), buf.clone(), 4096),
                    "Preset " + preset + " threw exception");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static float[] fill(int n, float v)
    {
        float[] a = new float[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static double maxAbs(float[] a)
    {
        double m = 0;
        for (float v : a)
            m = Math.max(m, Math.abs(v));
        return m;
    }
}
