package com.fupfin.midiraja.midi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("EmptyCatch") public class NativeAudioEngine extends AbstractFFMBridge implements AudioEngine
{
    private MemorySegment ctx = MemorySegment.NULL;
    private final MethodHandle midiraja_audio_init;
    private final MethodHandle midiraja_audio_get_device_latency_frames;
    private final MethodHandle midiraja_audio_close;

    // --- Block-based Thread-Safe Queue ---
    // Instead of a brittle math-heavy ring buffer, we use a simple circular array of blocks.
    // This is safe because blocks are either completely FULL (ready for C) or EMPTY (ready for Java).
    private static final int NUM_BLOCKS = 16;
    private short[][] blocks = new short[0][0];
    private int blockSizeSamples;
    
    // volatile ensures memory visibility without locks
    private volatile int writeBlockIndex = 0;
    private volatile int writeOffset = 0; // offset within the current block
    
    private volatile int readBlockIndex = 0;
    private volatile int readOffset = 0;
    
    private volatile int blocksInQueue = 0;

    // Keep a reference to prevent garbage collection of the upcall stub
    private @Nullable MemorySegment upcallStub;

    public NativeAudioEngine(String libPath) throws Exception
    {
        this(Arena.ofShared(), libPath);
    }

    private NativeAudioEngine(Arena arena, String libPath) throws Exception
    {
        super(arena, loadLib(libPath));

        midiraja_audio_init = downcall("midiraja_audio_init",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        midiraja_audio_get_device_latency_frames = downcall("midiraja_audio_get_device_latency_frames", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        midiraja_audio_close = downcall("midiraja_audio_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static SymbolLookup loadLib(String libPath) {
        System.load(new java.io.File(libPath).getAbsolutePath());
        return SymbolLookup.loaderLookup();
    }

    /**
     * The callback method invoked by C (miniaudio thread).
     * CAUTION: This runs on an OS real-time thread. No locks!
     */
    public int onAudioRequest(MemorySegment userData, MemorySegment pOutputRaw, int numSamplesRequested) {
        MemorySegment pOutput = pOutputRaw.reinterpret(numSamplesRequested * 2L);
        if (blocks.length == 0 || blocksInQueue == 0) return 0;

        int samplesFulfilled = 0;
        
        while (samplesFulfilled < numSamplesRequested && blocksInQueue > 0) {
            short[] currentReadBlock = blocks[readBlockIndex];
            int availableInBlock = blockSizeSamples - readOffset;
            int needed = numSamplesRequested - samplesFulfilled;
            
            int toCopy = Math.min(availableInBlock, needed);
            toCopy -= (toCopy % 2); // strictly align to frame pairs
            
            if (toCopy <= 0) break; // Not enough for a full frame
            
            // Fast native memory copy
            MemorySegment.copy(currentReadBlock, readOffset, pOutput, ValueLayout.JAVA_SHORT, samplesFulfilled, toCopy);
            
            samplesFulfilled += toCopy;
            readOffset += toCopy;
            
            if (readOffset >= blockSizeSamples) {
                // We exhausted this block. Move to next.
                readOffset = 0;
                readBlockIndex = (readBlockIndex + 1) % NUM_BLOCKS;
                blocksInQueue--; // Safe: Only the reader decrements, Writer only increments
            }
        }
        
        return samplesFulfilled;
    }

    @Override public void init(int sampleRate, int channels, int bufferFrames) throws Exception
    {
        try
        {
            // We slice the total requested capacity into chunks (e.g. 512 samples per block)
            // This is drastically more robust than a single monolithic byte array math.
            blockSizeSamples = 512 * channels; 
            blocks = new short[NUM_BLOCKS][blockSizeSamples];
            writeBlockIndex = 0;
            writeOffset = 0;
            readBlockIndex = 0;
            readOffset = 0;
            blocksInQueue = 0;

            MethodHandle handle = MethodHandles.lookup().findVirtual(
                NativeAudioEngine.class, 
                "onAudioRequest", 
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, int.class)
            ).bindTo(this);
            
            upcallStub = Linker.nativeLinker().upcallStub(
                handle,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                arena
            );

            ctx = (MemorySegment) midiraja_audio_init.invokeExact(sampleRate, channels, bufferFrames, upcallStub, MemorySegment.NULL);

            if (ctx.equals(MemorySegment.NULL))
            {
                throw new Exception("Failed to initialize miniaudio engine.");
            }
        }
        catch (Throwable t) {
            System.err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error invoking midiraja_audio_init", t);
        }
    }

    @Override public int getQueuedFrames()
    {
        return (blocksInQueue * blockSizeSamples) / 2;
    }

    @Override public int getDeviceLatencyFrames()
    {
        if (ctx.equals(MemorySegment.NULL)) return 0;
        try
        {
            return (int) midiraja_audio_get_device_latency_frames.invokeExact(ctx);
        }
        catch (Throwable ignored) {
            return 0;
        }
    }

    @Override public int push(short[] pcmData) {
        if (pcmData == null) return 0;
        return push(pcmData, 0, pcmData.length);
    }

    @Override public int push(short[] pcmData, int offset, int length)
    {
        if (blocks.length == 0 || length <= 0) return 0;
        
        // Cannot write if we are completely full
        if (blocksInQueue >= NUM_BLOCKS) return 0;

        int samplesWritten = 0;
        
        while (samplesWritten < length && blocksInQueue < NUM_BLOCKS) {
            short[] currentWriteBlock = blocks[writeBlockIndex];
            int spaceInBlock = blockSizeSamples - writeOffset;
            int remainingToPush = length - samplesWritten;
            
            int toCopy = Math.min(spaceInBlock, remainingToPush);
            toCopy -= (toCopy % 2); // align to frames
            
            if (toCopy <= 0) break;
            
            System.arraycopy(pcmData, offset + samplesWritten, currentWriteBlock, writeOffset, toCopy);
            
            samplesWritten += toCopy;
            writeOffset += toCopy;
            
            if (writeOffset >= blockSizeSamples) {
                // Block is full, seal it and move to next
                writeOffset = 0;
                writeBlockIndex = (writeBlockIndex + 1) % NUM_BLOCKS;
                blocksInQueue++; // Safe: Only writer increments, reader only decrements
            }
        }
        
        return samplesWritten;
    }
    
    @Override public int getBufferCapacityFrames() {
        return (NUM_BLOCKS * blockSizeSamples) / 2;
    }

    @Override public void flush()
    {
        blocksInQueue = 0;
        writeBlockIndex = 0;
        writeOffset = 0;
        readBlockIndex = 0;
        readOffset = 0;
    }

    @Override public void close()
    {
        if (!ctx.equals(MemorySegment.NULL))
        {
            try
            {
                midiraja_audio_close.invokeExact(ctx);
            }
            catch (Throwable ignored) {}
            ctx = MemorySegment.NULL;
        }
        blocks = new short[0][0];
        try { super.close(); } catch(Exception ignored) {}
    }
}