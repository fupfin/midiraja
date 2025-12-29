/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("EmptyCatch") public class NativeAudioEngine implements AudioEngine
{
    private final Arena arena;
    private MemorySegment ctx = MemorySegment.NULL;

    private final MethodHandle midiraja_audio_init;
    private final MethodHandle midiraja_audio_push;
    private final MethodHandle midiraja_audio_get_queued_frames;
    private final MethodHandle midiraja_audio_get_device_latency_frames;
    private final MethodHandle midiraja_audio_flush;
    private final MethodHandle midiraja_audio_close;

    public NativeAudioEngine(String libPath) throws Exception
    {
        arena = Arena.ofShared();

        System.load(new java.io.File(libPath).getAbsolutePath());
        SymbolLookup lib = SymbolLookup.loaderLookup();
        Linker linker = Linker.nativeLinker();

        midiraja_audio_init = linker.downcallHandle(lib.find("midiraja_audio_init").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT));

        midiraja_audio_push = linker.downcallHandle(lib.find("midiraja_audio_push").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        midiraja_audio_get_queued_frames =
            linker.downcallHandle(lib.find("midiraja_audio_get_queued_frames").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        midiraja_audio_get_device_latency_frames = linker.downcallHandle(
            lib.find("midiraja_audio_get_device_latency_frames").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        midiraja_audio_flush = linker.downcallHandle(lib.find("midiraja_audio_flush").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        midiraja_audio_close = linker.downcallHandle(lib.find("midiraja_audio_close").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    @Override public void init(int sampleRate, int channels, int bufferFrames) throws Exception
    {
        try
        {
            ctx =
                (MemorySegment) midiraja_audio_init.invokeExact(sampleRate, channels, bufferFrames);
            if (ctx.equals(MemorySegment.NULL))
            {
                throw new Exception("Failed to initialize miniaudio engine.");
            }
        }
        catch (Throwable t)
        {
            throw new Exception("Error invoking midiraja_audio_init", t);
        }
    }

    private MemorySegment pushBuffer = MemorySegment.NULL;
    private int currentPushBufferSize = 0;

    @Override public int getQueuedFrames()
    {
        if (ctx.equals(MemorySegment.NULL))
            return 0;
        try
        {
            return (int) midiraja_audio_get_queued_frames.invokeExact(ctx);
        }
        catch (Throwable ignored)
        {
            return 0;
        }
    }

    @Override public int getDeviceLatencyFrames()
    {
        if (ctx.equals(MemorySegment.NULL))
            return 0;
        try
        {
            return (int) midiraja_audio_get_device_latency_frames.invokeExact(ctx);
        }
        catch (Throwable ignored)
        {
            return 0;
        }
    }

    @Override public void push(short[] pcmData)
    {
        if (ctx.equals(MemorySegment.NULL) || pcmData == null || pcmData.length == 0)
            return;

        try
        {
            int requiredBytes = pcmData.length * 2;
            if (currentPushBufferSize < requiredBytes)
            {
                pushBuffer = arena.allocate(requiredBytes);
                currentPushBufferSize = requiredBytes;
            }
            MemorySegment.copy(pcmData, 0, pushBuffer, ValueLayout.JAVA_SHORT, 0, pcmData.length);
            midiraja_audio_push.invokeExact(ctx, pushBuffer, pcmData.length);
        }
        catch (Throwable ignored)
        {
            // Intentionally ignored to prevent tearing down the audio thread on a single dropped
            // frame
        }
    }

    @Override public void flush()
    {
        if (ctx.equals(MemorySegment.NULL))
            return;
        try
        {
            midiraja_audio_flush.invokeExact(ctx);
        }
        catch (Throwable ignored)
        {
        }
    }

    @Override public void close()
    {
        if (!ctx.equals(MemorySegment.NULL))
        {
            try
            {
                midiraja_audio_close.invokeExact(ctx);
            }
            catch (Throwable ignored)
            {
            }
            ctx = MemorySegment.NULL;
        }
        if (arena.scope().isAlive())
        {
            arena.close();
        }
    }
}