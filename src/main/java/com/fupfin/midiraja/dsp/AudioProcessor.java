package com.fupfin.midiraja.dsp;

/**
 * The base interface for any node that can process or consume a block of audio frames. The internal
 * processing format is always non-interleaved stereo float arrays.
 */
public interface AudioProcessor
{
    /**
     * Processes or consumes a block of audio frames.
     *
     * @param left
     *            Array of left channel samples (-1.0 to 1.0)
     * @param right
     *            Array of right channel samples (-1.0 to 1.0)
     * @param frames
     *            The number of valid frames in the arrays to process
     */
    void process(float[] left, float[] right, int frames);

    /**
     * Entry point for raw interleaved PCM from native synthesizers.
     *
     * @param interleavedPcm
     *            Array of short PCM samples.
     * @param frames
     *            The number of valid frames.
     * @param channels
     *            Number of channels in the interleaved input (1 for mono, 2 for stereo).
     */
    default void processInterleaved(short[] interleavedPcm, int frames, int channels)
    {
        throw new UnsupportedOperationException("This processor does not support interleaved PCM.");
    }

    /**
     * Resets the internal state (e.g. clearing delay buffers).
     */
    default void reset()
    {
    }
}
