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
        this(enabled, new OneBitAcousticSimulator(44100, oneBitMode != null ? oneBitMode : "pwm"), next);
    }

    OneBitAcousticSimulatorFilter(boolean enabled, OneBitAcousticSimulator simulator, AudioProcessor next)
    {
        this.enabled = enabled;
        this.simulator = simulator;
        this.next = next;
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
