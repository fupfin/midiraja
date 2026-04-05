package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EqFilterTest
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

    /** Generates a single-frequency sine wave at 44100 Hz sample rate. */
    private static float[] sine(float freqHz, int frames)
    {
        float[] buf = new float[frames];
        for (int i = 0; i < frames; i++)
            buf[i] = (float) Math.sin(2.0 * Math.PI * freqHz * i / 44100.0);
        return buf;
    }

    /** RMS of the entire buffer. */
    private static double rms(float[] buf)
    {
        double sum = 0;
        for (float v : buf)
            sum += (double) v * v;
        return Math.sqrt(sum / buf.length);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Feeds freqHz sine through the given filter (with a warm-up pass) and
     * returns the output RMS. The filter's next must already be a CaptureSink.
     */
    private static double steadyStateRms(EqFilter filter, CaptureSink sink, float freqHz)
    {
        int frames = 4096;
        float[] warmL = sine(freqHz, frames);
        float[] warmR = sine(freqHz, frames);
        filter.process(warmL, warmR, frames); // warm-up: discard

        float[] measL = sine(freqHz, frames);
        float[] measR = sine(freqHz, frames);
        filter.process(measL, measR, frames); // steady-state: captured by sink

        return rms(sink.left);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void neutral_params_do_not_attenuate()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setParams(50, 50, 50);

        double inputRms = rms(sine(1000, 4096));
        double outputRms = steadyStateRms(filter, sink, 1000);

        // Within 5% of input RMS
        assertEquals(inputRms, outputRms, inputRms * 0.05,
                "Neutral EQ should not attenuate: inputRms=" + inputRms + " outputRms=" + outputRms);
    }

    @Test
    void bassBoost_amplifies_low_frequency()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setParams(100, 50, 50);

        double inputRms = rms(sine(100, 4096));
        double outputRms = steadyStateRms(filter, sink, 100);

        assertTrue(outputRms > inputRms,
                "Bass boost should amplify 100 Hz sine: inputRms=" + inputRms + " outputRms=" + outputRms);
    }

    @Test
    void bassCut_attenuates_low_frequency()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setParams(0, 50, 50); // 0% → -60 dB (near mute)

        double inputRms = rms(sine(100, 4096));
        double outputRms = steadyStateRms(filter, sink, 100);

        // Low-shelf at -60 dB still leaks a little; require output < 5% of input
        assertTrue(outputRms < inputRms * 0.05,
                "Bass cut (0%) should strongly attenuate 100 Hz: inputRms=" + inputRms + " outputRms=" + outputRms);
    }

    @Test
    void trebleBoost_amplifies_high_frequency()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setParams(50, 50, 100);

        double inputRms = rms(sine(8000, 4096));
        double outputRms = steadyStateRms(filter, sink, 8000);

        assertTrue(outputRms > inputRms,
                "Treble boost should amplify 8 kHz sine: inputRms=" + inputRms + " outputRms=" + outputRms);
    }

    @Test
    void trebleCut_attenuates_high_frequency()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setParams(50, 50, 0); // 0% → -60 dB treble cut

        double inputRms = rms(sine(8000, 4096));
        double outputRms = steadyStateRms(filter, sink, 8000);

        assertTrue(outputRms < inputRms * 0.1,
                "Treble cut (0%) should strongly attenuate 8 kHz: inputRms=" + inputRms + " outputRms=" + outputRms);
    }

    @Test
    void lpf_attenuates_above_cutoff()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setLpf(1000);

        double inputRms = rms(sine(5000, 4096));
        double outputRms = steadyStateRms(filter, sink, 5000);

        assertTrue(outputRms < inputRms * 0.5,
                "LPF at 1 kHz should attenuate 5 kHz by more than half: inputRms=" + inputRms + " outputRms="
                        + outputRms);
    }

    @Test
    void lpf_passes_below_cutoff()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setLpf(1000);

        double inputRms = rms(sine(100, 4096));
        double outputRms = steadyStateRms(filter, sink, 100);

        // Allow up to 20% deviation (IIR transients + mild shelf roll-off)
        assertTrue(outputRms > inputRms * 0.8,
                "LPF at 1 kHz should pass 100 Hz with minimal loss: inputRms=" + inputRms + " outputRms=" + outputRms);
    }

    @Test
    void hpf_attenuates_below_cutoff()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setHpf(5000);

        double inputRms = rms(sine(100, 4096));
        double outputRms = steadyStateRms(filter, sink, 100);

        assertTrue(outputRms < inputRms * 0.5,
                "HPF at 5 kHz should attenuate 100 Hz by more than half: inputRms=" + inputRms + " outputRms="
                        + outputRms);
    }

    @Test
    void lpf_disabled_above_20kHz()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setLpf(25000); // >= 20000 → LPF disabled

        double inputRms = rms(sine(15000, 4096));
        double outputRms = steadyStateRms(filter, sink, 15000);

        // LPF disabled: output should be close to input (within 20%)
        assertTrue(outputRms > inputRms * 0.8,
                "LPF should be disabled at 25 kHz cutoff — 15 kHz should pass through: inputRms=" + inputRms
                        + " outputRms=" + outputRms);
    }

    @Test
    void hpf_disabled_below_20Hz()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setHpf(10); // <= 20 → HPF disabled

        double inputRms = rms(sine(1000, 4096));
        double outputRms = steadyStateRms(filter, sink, 1000);

        // HPF disabled: output should be close to input (within 5%)
        assertEquals(inputRms, outputRms, inputRms * 0.05,
                "HPF should be disabled at 10 Hz cutoff — 1 kHz should pass unchanged");
    }

    @Test
    void next_is_null_does_not_throw()
    {
        var filter = new EqFilter(null);
        filter.setParams(50, 50, 50);
        float[] left = sine(440, 256);
        float[] right = sine(440, 256);
        assertDoesNotThrow(() -> filter.process(left, right, 256),
                "process() with null next should not throw");
    }

    @Test
    void stereo_processed_independently()
    {
        var sink = new CaptureSink();
        var filter = new EqFilter(sink);
        filter.setParams(50, 50, 50);

        int frames = 4096;
        float[] leftIn = sine(440, frames); // 440 Hz on left
        float[] rightIn = sine(1760, frames); // 1760 Hz on right (4× higher)

        filter.process(leftIn, rightIn, frames);

        // Both channels must be non-zero
        assertTrue(rms(sink.left) > 0.1, "Left channel output should be non-zero");
        assertTrue(rms(sink.right) > 0.1, "Right channel output should be non-zero");

        // The waveforms themselves must be different — 440 Hz and 1760 Hz are distinct
        // signals, so their sample values cannot all be equal. Check a few sample points.
        boolean anyDifference = false;
        for (int i = 100; i < 200; i++)
        {
            if (Math.abs(sink.left[i] - sink.right[i]) > 0.01f)
            {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference,
                "Left (440 Hz) and right (1760 Hz) waveforms should differ sample-by-sample");
    }
}
