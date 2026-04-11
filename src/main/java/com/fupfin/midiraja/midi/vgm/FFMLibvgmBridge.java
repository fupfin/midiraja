/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.vgm;

import static java.lang.System.err;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.List;

import com.fupfin.midiraja.midi.AbstractFFMBridge;
import com.fupfin.midiraja.midi.MidiNativeBridge;

/**
 * FFM bridge for libvgm's vgm_bridge C wrapper.
 *
 * <p>
 * Wraps the six-function C API:
 * <ul>
 * <li>{@code vgm_create(int sampleRate) → void*}</li>
 * <li>{@code vgm_open_file(void* ctx, const char* path) → int}</li>
 * <li>{@code vgm_open_data(void* ctx, const uint8_t* data, size_t len) → int}</li>
 * <li>{@code vgm_render(void* ctx, int frames, short* buf) → int} (interleaved stereo)</li>
 * <li>{@code vgm_is_done(void* ctx) → int}</li>
 * <li>{@code vgm_close(void* ctx) → void}</li>
 * </ul>
 *
 * <p>
 * MIDI event methods are no-ops; synthesis is driven entirely by {@link #generate}.
 */
@SuppressWarnings({ "EmptyCatch", "UnusedVariable" })
public class FFMLibvgmBridge extends AbstractFFMBridge implements MidiNativeBridge
{
    // vgm_create(int sampleRate) → void*
    private static final FunctionDescriptor DESC_VGM_CREATE = FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

    // vgm_is_done(void* ctx) → int
    private static final FunctionDescriptor DESC_VGM_IS_DONE = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    private MemorySegment device = MemorySegment.NULL;

    /**
     * Returns all {@link FunctionDescriptor}s used in this class.
     * Used by {@code NativeMetadataConsistencyTest}.
     */
    public static List<FunctionDescriptor> allDowncallDescriptors()
    {
        return List.of(
                DESC_VGM_CREATE,    // vgm_create(int) → void*
                DESC_PTR_STR,       // vgm_open_file(void*, const char*) → int
                DESC_PTR_PTR_LONG,  // vgm_open_data(void*, const uint8_t*, size_t) → int
                DESC_GENERATE,      // vgm_render(void*, int, short*) → int
                DESC_VGM_IS_DONE,   // vgm_is_done(void*) → int
                DESC_VOID_PTR);     // vgm_close(void*) → void
    }

    /**
     * Probes whether the libvgm native library is available.
     * Never throws; returns a {@link LibProbeResult} with {@code found=false} when missing.
     */
    public static LibProbeResult probe()
    {
        return probeLibrary("libvgm",
                "libmidiraja_vgm.dylib", "libmidiraja_vgm.so", "libmidiraja_vgm.dll");
    }

    // FFM method handles
    private final MethodHandle vgm_create;
    private final MethodHandle vgm_open_file;
    private final MethodHandle vgm_open_data;
    private final MethodHandle vgm_render;
    private final MethodHandle vgm_is_done;
    private final MethodHandle vgm_close;

    public FFMLibvgmBridge() throws Exception
    {
        this(Arena.ofShared());
    }

    private FFMLibvgmBridge(Arena arena) throws Exception
    {
        super(arena, tryLoadLibrary(arena, "libvgm",
                "libmidiraja_vgm.dylib", "libmidiraja_vgm.so", "libmidiraja_vgm.dll"));

        vgm_create = downcall("vgm_create", DESC_VGM_CREATE);
        vgm_open_file = downcall("vgm_open_file", DESC_PTR_STR);
        vgm_open_data = downcall("vgm_open_data", DESC_PTR_PTR_LONG);
        vgm_render = downcall("vgm_render", DESC_GENERATE);
        vgm_is_done = downcall("vgm_is_done", DESC_VGM_IS_DONE);
        vgm_close = downcall("vgm_close", DESC_VOID_PTR);
    }

    // ── MidiNativeBridge: init creates the native context ─────────────────────

    @Override
    public void init(int sampleRate) throws Exception
    {
        try
        {
            device = (MemorySegment) vgm_create.invokeExact(sampleRate);
            if (device.equals(MemorySegment.NULL))
                throw new IllegalStateException("vgm_create returned NULL");
        }
        catch (Exception e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new IllegalStateException("Error initialising libvgm", t);
        }
    }

    // ── File / data loading ───────────────────────────────────────────────────

    public void openFile(String path) throws Exception
    {
        if (device.equals(MemorySegment.NULL))
            throw new IllegalStateException("Call init() before openFile()");
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment pathSeg = temp.allocateFrom(path);
            int rc = (int) vgm_open_file.invokeExact(device, pathSeg);
            if (rc != 0)
                throw new IllegalArgumentException("vgm_open_file failed (rc=" + rc + "): " + path);
        }
        catch (Exception e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new IllegalStateException("Error opening VGM file: " + path, t);
        }
    }

    public void openData(byte[] data) throws Exception
    {
        if (device.equals(MemorySegment.NULL))
            throw new IllegalStateException("Call init() before openData()");
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment seg = temp.allocateFrom(ValueLayout.JAVA_BYTE, data);
            int rc = (int) vgm_open_data.invokeExact(device, seg, (long) data.length);
            if (rc != 0)
                throw new IllegalStateException("vgm_open_data failed (rc=" + rc + ")");
        }
        catch (Exception e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new IllegalStateException("Error loading VGM data", t);
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    /** Renders {@code stereoFrames} interleaved stereo frames into {@code buffer}. */
    @Override
    public void generate(short[] buffer, int stereoFrames)
    {
        generateInto(vgm_render, device, buffer);
    }

    public boolean isDone()
    {
        if (device.equals(MemorySegment.NULL))
            return true;
        try
        {
            return (int) vgm_is_done.invokeExact(device) != 0;
        }
        catch (Throwable t)
        {
            err.println("[FFMLibvgmBridge] isDone error: " + t.getMessage());
            return true;
        }
    }

    // ── MidiNativeBridge: MIDI events are no-ops for VGM playback ─────────────

    @Override
    public void reset()
    {
    }

    @Override
    public void panic()
    {
    }

    @Override
    public void noteOn(int channel, int note, int velocity)
    {
    }

    @Override
    public void noteOff(int channel, int note)
    {
    }

    @Override
    public void controlChange(int channel, int type, int value)
    {
    }

    @Override
    public void patchChange(int channel, int patch)
    {
    }

    @Override
    public void pitchBend(int channel, int pitch)
    {
    }

    @Override
    public void systemExclusive(byte[] data)
    {
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close()
    {
        if (!device.equals(MemorySegment.NULL))
        {
            try
            {
                vgm_close.invokeExact(device);
            }
            catch (Throwable t)
            {
                err.println("[FFMLibvgmBridge] close error: " + t.getMessage());
            }
            device = MemorySegment.NULL;
        }
        try
        {
            super.close();
        }
        catch (Exception e)
        {
            err.println("[FFMLibvgmBridge] arena close error: " + e.getMessage());
        }
    }
}
