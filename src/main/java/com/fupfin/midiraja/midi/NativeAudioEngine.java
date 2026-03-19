package com.fupfin.midiraja.midi;


import java.io.File;
import java.lang.foreign.*;
import java.lang.invoke.*;
import org.jspecify.annotations.Nullable;


@SuppressWarnings("EmptyCatch")
public class NativeAudioEngine extends AbstractFFMBridge implements AudioEngine
{
    private static final java.util.logging.Logger log =
            java.util.logging.Logger.getLogger(NativeAudioEngine.class.getName());
    private @Nullable WavFileWriter wavWriter = null;
    private MemorySegment ctx = MemorySegment.NULL;
    private final MethodHandle midiraja_audio_init;
    private final MethodHandle midiraja_audio_push;
    private final MethodHandle midiraja_audio_get_queued_frames;
    private final MethodHandle midiraja_audio_flush;
    private final MethodHandle midiraja_audio_get_device_latency_frames;
    private final MethodHandle midiraja_audio_close;

    // Ring buffer capacity (mirrors the C constant AUDIO_RING_BUFFER_FRAMES)
    private static final int RING_BUFFER_FRAMES = 16384;
    // Max frames per single push call – must fit in the pre-allocated push buffer
    private static final int MAX_PUSH_FRAMES = 4096;

    // Pre-allocated native buffer for push(); reused across calls to avoid per-push allocation.
    // Exclusively used by the render thread, so no synchronisation is needed.
    private @Nullable MemorySegment pushBuffer = null;
    private int channels = 2;

    public NativeAudioEngine(String libPath) throws Exception
    {
        this(Arena.ofShared(), libPath);
    }

    private NativeAudioEngine(Arena arena, String libPath) throws Exception
    {
        super(arena, loadLib(arena, libPath));

        midiraja_audio_init = downcall("midiraja_audio_init",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        midiraja_audio_push = downcall("midiraja_audio_push",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        midiraja_audio_get_queued_frames = downcall("midiraja_audio_get_queued_frames",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        midiraja_audio_flush = downcall("midiraja_audio_flush",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        midiraja_audio_get_device_latency_frames =
                downcall("midiraja_audio_get_device_latency_frames",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        midiraja_audio_close =
                downcall("midiraja_audio_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    public static java.util.List<FunctionDescriptor> allDowncallDescriptors()
    {
        return java.util.List.of(
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static SymbolLookup loadLib(Arena arena, String libPath)
    {
        if (libPath.isEmpty())
            // No dylib found: fall back to the process symbol table (covers static linking).
            return Linker.nativeLinker().defaultLookup();
        if (new File(libPath).isAbsolute())
            return SymbolLookup.libraryLookup(new File(libPath).toPath(), arena);
        // Name-only path (e.g. "libmidiraja_audio.dylib"): resolved via rpath at runtime.
        return SymbolLookup.libraryLookup(libPath, arena);
    }

    @Override
    public void enableDump(String filename)
    {
        if (wavWriter != null)
        {
            try
            {
                wavWriter.close();
            }
            catch (Exception ignored)
            {
                /* Safe to ignore: optional listener */
            }
        }
        try
        {
            wavWriter = new WavFileWriter(filename, 44100, 2);
            log.fine("Dumping audio to " + filename);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void init(int sampleRate, int channels, int bufferFrames) throws Exception
    {
        if (wavWriter != null)
        {
            try
            {
                wavWriter.close();
            }
            catch (Exception ignored)
            {
                /* Safe to ignore: optional listener */
            }
            try
            {
                wavWriter = new WavFileWriter("dump.wav", sampleRate, channels);
            }
            catch (Exception ignored)
            {
                /* Safe to ignore: optional listener */
            }
        }

        this.channels = channels;
        pushBuffer = arena.allocate((long) MAX_PUSH_FRAMES * channels * Short.BYTES);

        try
        {
            ctx = (MemorySegment) midiraja_audio_init.invokeExact(sampleRate, channels,
                    bufferFrames);

            if (ctx.equals(MemorySegment.NULL))
            {
                throw new Exception("Failed to initialize miniaudio engine.");
            }
        }
        catch (Throwable t)
        {
            log.warning("NativeBridge error: " + t.getMessage());
            throw new Exception("Error invoking midiraja_audio_init", t);
        }
    }

    @Override
    public int getQueuedFrames()
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

    @Override
    public int getDeviceLatencyFrames()
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

    @Override
    public int push(short[] pcmData)
    {
        if (pcmData == null)
            return 0;
        return push(pcmData, 0, pcmData.length);
    }

    @Override
    public int push(short[] pcmData, int offset, int length)
    {
        if (wavWriter != null)
        {
            try
            {
                short[] dumpBuf = new short[length];
                System.arraycopy(pcmData, offset, dumpBuf, 0, length);
                wavWriter.write(dumpBuf);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        if (ctx.equals(MemorySegment.NULL) || pushBuffer == null || length <= 0)
            return 0;

        int frameCount = length / channels;
        if (frameCount <= 0)
            return 0;
        if (frameCount > MAX_PUSH_FRAMES)
            frameCount = MAX_PUSH_FRAMES;

        int samples = frameCount * channels;
        MemorySegment.copy(pcmData, offset, pushBuffer, ValueLayout.JAVA_SHORT, 0, samples);

        try
        {
            int pushedFrames = (int) midiraja_audio_push.invokeExact(ctx, pushBuffer, frameCount);
            return pushedFrames * channels;
        }
        catch (Throwable ignored)
        {
            return 0;
        }
    }

    @Override
    public int getBufferCapacityFrames()
    {
        return RING_BUFFER_FRAMES;
    }

    @Override
    public void flush()
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

    @Override
    public void close()
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
        pushBuffer = null;
        try
        {
            super.close();
        }
        catch (Exception ignored)
        {
        }
    }
}
