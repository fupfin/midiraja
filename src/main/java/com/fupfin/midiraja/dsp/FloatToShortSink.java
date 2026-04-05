package com.fupfin.midiraja.dsp;

import static java.lang.Math.*;

import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.midi.AudioEngine;

/**
 * A terminal sink that converts float[] (-1.0 to 1.0) into interleaved short[] PCM and pushes it to
 * the native AudioEngine.
 */
public class FloatToShortSink implements AudioSink
{
    private static final Logger log = Logger.getLogger(FloatToShortSink.class.getName());
    private static final int FLUSH_THRESHOLD = 500; // flush after ~500ms of failed pushes

    private final @Nullable AudioEngine engine;
    private final int outputChannels;
    private short @Nullable [] pcmBuffer = null;

    public FloatToShortSink(@Nullable AudioEngine engine)
    {
        this(engine, 2); // Default to stereo
    }

    public FloatToShortSink(@Nullable AudioEngine engine, int outputChannels)
    {
        this.engine = engine;
        this.outputChannels = outputChannels;
    }

    @Override
    public void processInterleaved(short[] interleavedPcm, int frames, int channels)
    {
        if (engine != null)
        {
            int totalSamples = frames * channels;
            int offset = 0;
            int stuckCount = 0;
            while (offset < totalSamples)
            {
                int written = engine.push(interleavedPcm, offset, totalSamples - offset);
                if (written > 0)
                {
                    offset += written;
                    stuckCount = 0;
                }
                else if (written < 0)
                {
                    break; // C-layer error, abort frame to save thread
                }
                else
                {
                    stuckCount++;
                    if (stuckCount >= FLUSH_THRESHOLD)
                    {
                        log.warning("FloatToShortSink: ring buffer stuck, flushing to recover");
                        engine.flush();
                        stuckCount = 0;
                    }
                    LockSupport.parkNanos(1_000_000); // 1ms
                }
            }
        }
    }

    @Override
    public void process(float[] left, float[] right, int frames)
    {
        if (engine == null)
            return;

        int needed = frames * outputChannels;
        if (pcmBuffer == null || pcmBuffer.length < needed)
        {
            pcmBuffer = new short[needed];
        }

        if (outputChannels == 1)
        {
            for (int i = 0; i < frames; i++)
            {
                float avg = (left[i] + right[i]) * 0.5f;
                pcmBuffer[i] = (short) (max(-1.0f, min(1.0f, avg)) * 32767.0f);
            }
        }
        else
        {
            for (int i = 0; i < frames; i++)
            {
                float clampL = max(-1.0f, min(1.0f, left[i]));
                float clampR = max(-1.0f, min(1.0f, right[i]));
                pcmBuffer[i * 2] = (short) (clampL * 32767.0f);
                pcmBuffer[i * 2 + 1] = (short) (clampR * 32767.0f);
            }
        }

        int totalSamples = frames * outputChannels;
        int offset = 0;
        int stuckCount = 0;
        while (offset < totalSamples)
        {
            int written = engine.push(pcmBuffer, offset, totalSamples - offset);
            if (written > 0)
            {
                offset += written;
                stuckCount = 0;
            }
            else if (written < 0)
            {
                break; // C-layer error, abort frame
            }
            else
            {
                stuckCount++;
                if (stuckCount >= FLUSH_THRESHOLD)
                {
                    log.warning("FloatToShortSink: ring buffer stuck, flushing to recover");
                    engine.flush();
                    stuckCount = 0;
                }
                LockSupport.parkNanos(1_000_000); // 1ms
            }
        }
    }

    @Override
    public void reset()
    {
        if (engine != null)
        {
            engine.flush();
        }
    }
}
