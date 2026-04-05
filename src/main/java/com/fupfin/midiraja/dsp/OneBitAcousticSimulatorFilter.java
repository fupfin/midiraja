package com.fupfin.midiraja.dsp;

/**
 * Global DSP Filter version of the 1-bit PC Speaker acoustic simulator.
 */
public class OneBitAcousticSimulatorFilter implements AudioProcessor
{
    private final boolean enabled;
    private final OneBitAcousticSimulator simulator;
    private final AudioProcessor next;

    public OneBitAcousticSimulatorFilter(boolean enabled, String oneBitMode, AudioProcessor next)
    {
        this.enabled = enabled;
        this.next = next;
        int sampleRate = 44100; // Assume global sample rate
        this.simulator = new OneBitAcousticSimulator(sampleRate,
                oneBitMode != null ? oneBitMode : "pwm");
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        if (!enabled)
            return;
        simulator.process(left, right, frames);
        next.process(left, right, frames);
    }

    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels)
    {
        next.processInterleaved(interleavedPcm, frames, channels);
    }

    @Override
    public void reset()
    {
        next.reset();
        simulator.reset();
    }
}
