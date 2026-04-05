package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class DetailedDspTest
{

    private static class DummyProcessor implements AudioProcessor
    {
        public boolean processCalled = false;
        public boolean processInterleavedCalled = false;
        public boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            processCalled = true;
        }

        @Override
        public void processInterleaved(short[] interleavedPcm, int frames, int channels)
        {
            processInterleavedCalled = true;
        }

        @Override
        public void reset()
        {
            resetCalled = true;
        }
    }

    @Test
    public void testCompactMacSimulatorFilter_Disabled()
    {
        DummyProcessor dummy = new DummyProcessor();
        CompactMacSimulatorFilter filter = new CompactMacSimulatorFilter(false, false, dummy);

        float[] left = new float[10];
        float[] right = new float[10];
        filter.process(left, right, 10);
        assertTrue(dummy.processCalled);

        short[] pcm = new short[20];
        filter.processInterleaved(pcm, 10, 2);
        assertTrue(dummy.processInterleavedCalled);
    }

    @Test
    public void testCompactMacSimulatorFilter_EnabledProcess()
    {
        DummyProcessor dummy = new DummyProcessor();
        CompactMacSimulatorFilter filter = new CompactMacSimulatorFilter(true, false, dummy);

        // Generate enough frames to trigger the currentTimeUs wrap around
        int frames = 44100 * 2; // 2 seconds
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            left[i] = (float) Math.sin(i * 0.1);
            right[i] = (float) Math.cos(i * 0.1);
        }

        filter.process(left, right, frames);
        assertTrue(dummy.processCalled);

        // check state has been modified
        assertNotEquals(0.0f, left[10]);

        filter.reset();
        assertTrue(dummy.resetCalled);
    }

    @Test
    public void testCompactMacSimulatorFilter_EnabledProcessInterleavedMono()
    {
        DummyProcessor dummy = new DummyProcessor();
        CompactMacSimulatorFilter filter = new CompactMacSimulatorFilter(true, false, dummy);

        int frames = 44100 * 2; // 2 seconds
        short[] pcm = new short[frames]; // Mono
        for (int i = 0; i < frames; i++)
        {
            pcm[i] = (short) (Math.sin(i * 0.1) * 32000);
        }

        filter.processInterleaved(pcm, frames, 1);
        assertTrue(dummy.processInterleavedCalled);
    }

    @Test
    public void testCompactMacSimulatorFilter_EnabledProcessInterleavedStereo()
    {
        DummyProcessor dummy = new DummyProcessor();
        CompactMacSimulatorFilter filter = new CompactMacSimulatorFilter(true, false, dummy);

        int frames = 44100 * 2; // 2 seconds
        short[] pcm = new short[frames * 2]; // Stereo
        for (int i = 0; i < frames * 2; i++)
        {
            pcm[i] = (short) (Math.sin(i * 0.1) * 32000);
        }

        filter.processInterleaved(pcm, frames, 2);
        assertTrue(dummy.processInterleavedCalled);
    }

    @Test
    public void testChorusFilter_Disabled()
    {
        DummyProcessor dummy = new DummyProcessor();
        ChorusFilter filter = new ChorusFilter(dummy, 50.0f);
        filter.setEnabled(false);

        float[] left = new float[10];
        float[] right = new float[10];
        filter.process(left, right, 10);
        assertTrue(dummy.processCalled);
    }

    @Test
    public void testChorusFilter_DisabledNullNext()
    {
        ChorusFilter filter = new ChorusFilter(null, 50.0f);
        filter.setEnabled(false);

        float[] left = new float[10];
        float[] right = new float[10];
        // Should not throw NPE
        filter.process(left, right, 10);
    }

    @Test
    public void testChorusFilter_EnabledNullNext()
    {
        ChorusFilter filter = new ChorusFilter(null, 50.0f);
        // Intensity tests
        filter.setIntensity(-10.0f); // below 0
        filter.setIntensity(150.0f); // above 100

        int frames = 44100 * 2; // enough to wrap lfoPhase
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            left[i] = (float) Math.sin(i * 0.1);
            right[i] = (float) Math.cos(i * 0.1);
        }

        filter.process(left, right, frames);
        // It should process without throwing an exception
    }

    @Test
    public void testChorusFilter_EnabledWithNext()
    {
        DummyProcessor dummy = new DummyProcessor();
        ChorusFilter filter = new ChorusFilter(dummy, 100.0f);

        int frames = 44100;
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            left[i] = 1.0f;
            right[i] = 1.0f;
        }

        filter.process(left, right, frames);
        assertTrue(dummy.processCalled);

        filter.reset();
        assertTrue(dummy.resetCalled);
    }
}
