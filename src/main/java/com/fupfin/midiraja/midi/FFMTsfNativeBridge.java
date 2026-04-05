/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static java.lang.System.err;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FFM implementation of {@link TsfNativeBridge} backed by TinySoundFont (libtsf).
 *
 * <p>
 * TinySoundFont is NOT thread-safe. All native calls must be made from the render thread. The
 * render thread in {@link TsfSynthProvider} drains the event queue before each
 * {@link #generate} call.
 *
 * <p>
 * <b>Velocity conversion:</b> MIDI velocity (0–127) is converted to TSF's float range (0.0–1.0)
 * before calling {@code tsf_channel_note_on}.
 */
@SuppressWarnings({ "EmptyCatch", "UnusedVariable" })
public class FFMTsfNativeBridge extends AbstractFFMBridge implements TsfNativeBridge
{
    // TSF-specific FunctionDescriptors (not in AbstractFFMBridge)
    private static final FunctionDescriptor DESC_TSF_SET_OUTPUT = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT);
    private static final FunctionDescriptor DESC_TSF_RENDER_SHORT = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT);
    private static final FunctionDescriptor DESC_TSF_CHANNEL_NOTE_ON = FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT);
    private static final FunctionDescriptor DESC_TSF_CHANNEL_INT_INT_INT_RETURN_INT = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final FunctionDescriptor DESC_TSF_CHANNEL_INT_INT_RETURN_INT = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT);
    // tsf_channel_note_off takes (int channel, int key) — use JAVA_INT, not JAVA_BYTE like ADL
    private static final FunctionDescriptor DESC_TSF_NOTE_OFF = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT);
    // tsf_load_memory: (const void* buffer, int size) → tsf*
    private static final FunctionDescriptor DESC_TSF_LOAD_MEMORY = FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

    /**
     * Returns all {@link FunctionDescriptor}s used for FFM downcall handles in this class.
     *
     * <p>
     * Used by {@code NativeMetadataConsistencyTest} to verify every descriptor is registered in
     * {@code reachability-metadata.json} before a native image build.
     */
    static List<FunctionDescriptor> allDowncallDescriptors()
    {
        return List.of(
                // tsf_load_filename: (const char*) → tsf*
                DESC_ERROR_INFO,
                // tsf_load_memory: (const void* buffer, int size) → tsf*
                DESC_TSF_LOAD_MEMORY,
                // tsf_close, tsf_reset, tsf_note_off_all: (tsf*) → void
                DESC_VOID_PTR,
                // tsf_set_output: (tsf*, int mode, int samplerate, float gain) → void
                DESC_TSF_SET_OUTPUT,
                // tsf_render_short: (tsf*, short* buffer, int samples, int flag_mixing) → void
                DESC_TSF_RENDER_SHORT,
                // tsf_channel_note_on: (tsf*, int ch, int key, float vel) → int
                DESC_TSF_CHANNEL_NOTE_ON,
                // tsf_channel_note_off: (tsf*, int ch, int key) → void
                DESC_TSF_NOTE_OFF,
                // tsf_channel_midi_control: (tsf*, int ch, int ctrl, int val) → int
                DESC_TSF_CHANNEL_INT_INT_INT_RETURN_INT,
                // tsf_channel_set_presetnumber: (tsf*, int ch, int preset, int drums) → int
                DESC_TSF_CHANNEL_INT_INT_INT_RETURN_INT,
                // tsf_channel_set_pitchwheel: (tsf*, int ch, int pitch) → int
                DESC_TSF_CHANNEL_INT_INT_RETURN_INT);
    }

    private MemorySegment device = MemorySegment.NULL;

    // Cached native render buffer to avoid per-frame allocation
    private MemorySegment renderBuffer = MemorySegment.NULL;
    private int currentRenderBufferShorts = 0;

    // FFM Method Handles
    private final MethodHandle tsf_load_filename;
    private final MethodHandle tsf_load_memory;
    private final MethodHandle tsf_close;
    private final MethodHandle tsf_set_output;
    private final MethodHandle tsf_reset;
    private final MethodHandle tsf_note_off_all;
    private final MethodHandle tsf_render_short;
    private final MethodHandle tsf_channel_note_on;
    private final MethodHandle tsf_channel_note_off;
    private final MethodHandle tsf_channel_midi_control;
    private final MethodHandle tsf_channel_set_presetnumber;
    private final MethodHandle tsf_channel_set_pitchwheel;

    public FFMTsfNativeBridge() throws Exception
    {
        this(Arena.ofShared());
    }

    private FFMTsfNativeBridge(Arena arena) throws Exception
    {
        super(arena, tryLoadLibrary(arena, "tsf", "libtsf.dylib", "libtsf.so", "libtsf.dll"));

        // tsf* tsf_load_filename(const char* filename)
        tsf_load_filename = downcall("tsf_load_filename", DESC_ERROR_INFO);

        // tsf* tsf_load_memory(const void* buffer, int size)
        tsf_load_memory = downcall("tsf_load_memory", DESC_TSF_LOAD_MEMORY);

        // void tsf_close(tsf* f)
        tsf_close = downcall("tsf_close", DESC_VOID_PTR);

        // void tsf_set_output(tsf* f, enum TSFOutputMode outputmode, int samplerate, float gain)
        tsf_set_output = downcall("tsf_set_output", DESC_TSF_SET_OUTPUT);

        // void tsf_reset(tsf* f)
        tsf_reset = downcall("tsf_reset", DESC_VOID_PTR);

        // void tsf_note_off_all(tsf* f)
        tsf_note_off_all = downcall("tsf_note_off_all", DESC_VOID_PTR);

        // void tsf_render_short(tsf* f, short* buffer, int samples, int flag_mixing)
        tsf_render_short = downcall("tsf_render_short", DESC_TSF_RENDER_SHORT);

        // int tsf_channel_note_on(tsf* f, int channel, int key, float vel)
        tsf_channel_note_on = downcall("tsf_channel_note_on", DESC_TSF_CHANNEL_NOTE_ON);

        // void tsf_channel_note_off(tsf* f, int channel, int key)
        tsf_channel_note_off = downcall("tsf_channel_note_off", DESC_TSF_NOTE_OFF);

        // int tsf_channel_midi_control(tsf* f, int channel, int controller, int control_value)
        tsf_channel_midi_control = downcall("tsf_channel_midi_control", DESC_TSF_CHANNEL_INT_INT_INT_RETURN_INT);

        // int tsf_channel_set_presetnumber(tsf* f, int channel, int preset_number, int flag_drums)
        tsf_channel_set_presetnumber = downcall("tsf_channel_set_presetnumber",
                DESC_TSF_CHANNEL_INT_INT_INT_RETURN_INT);

        // int tsf_channel_set_pitchwheel(tsf* f, int channel, int pitch_wheel)
        tsf_channel_set_pitchwheel = downcall("tsf_channel_set_pitchwheel", DESC_TSF_CHANNEL_INT_INT_RETURN_INT);
    }

    @Override
    public void loadSoundfontFile(String path, int sampleRate) throws Exception
    {
        // Read file bytes in Java (handles Unicode/non-ASCII paths on all platforms).
        // Passes in-memory buffer to tsf_load_memory, bypassing tsf_load_filename's use of
        // C fopen() which uses the ANSI code page on Windows and fails with non-ASCII paths.
        byte[] sfBytes = Files.readAllBytes(Path.of(path));
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment buf = temp.allocateFrom(ValueLayout.JAVA_BYTE, sfBytes);
            device = (MemorySegment) tsf_load_memory.invokeExact(buf, sfBytes.length);
        }
        catch (Exception e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            throw e;
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new IllegalStateException("Error loading SoundFont: " + path, t);
        }

        if (device.equals(MemorySegment.NULL))
        {
            throw new IllegalStateException("tsf_load_memory returned NULL: " + path);
        }

        // TSF_STEREO_INTERLEAVED = 0
        // −9 dB gain targets the project-wide −9 dBFS peak level (≈ 0.355 linear / 11 637 short).
        // TSF renders at full scale (0 dBFS) by default; this brings it in line with the other
        // bundled synths (PSG, Beep, GUS).
        try
        {
            tsf_set_output.invokeExact(device, 0, sampleRate, -9.0f);
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new IllegalStateException("Error setting TSF output parameters", t);
        }
    }

    @Override
    public void init(int sampleRate)
    {
        // init is a no-op for TSF; soundfont loading + output setup happen in loadSoundfontFile
    }

    @Override
    public void reset()
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            tsf_reset.invokeExact(device);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void panic()
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            tsf_note_off_all.invokeExact(device);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void noteOn(int channel, int note, int velocity)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            // TSF takes float velocity in range 0.0–1.0
            float vel = velocity / 127.0f;
            int ignored = (int) tsf_channel_note_on.invokeExact(device, channel, note, vel);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void noteOff(int channel, int note)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            tsf_channel_note_off.invokeExact(device, channel, note);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void controlChange(int channel, int type, int value)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) tsf_channel_midi_control.invokeExact(device, channel, type, value);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void patchChange(int channel, int patch)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            // isDrums = 1 for channel 9 (0-indexed), 0 otherwise
            int isDrums = (channel == 9) ? 1 : 0;
            int ignored = (int) tsf_channel_set_presetnumber.invokeExact(device, channel, patch, isDrums);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void pitchBend(int channel, int pitch)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        // pitch is 14-bit unsigned (0–16383), TSF tsf_channel_set_pitchwheel takes same range
        try
        {
            int ignored = (int) tsf_channel_set_pitchwheel.invokeExact(device, channel, pitch);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void systemExclusive(byte[] data)
    {
        // TSF does not expose a SysEx API; silently ignored
    }

    @Override
    public void generate(short[] buffer, int stereoFrames)
    {
        if (device.equals(MemorySegment.NULL) || buffer == null || buffer.length == 0)
            return;

        int requiredBytes = stereoFrames * 2 * Short.BYTES;
        if (currentRenderBufferShorts < stereoFrames * 2)
        {
            try
            {
                renderBuffer = arena.allocate(requiredBytes);
                currentRenderBufferShorts = stereoFrames * 2;
            }
            catch (Throwable e)
            {
                err.println("[NativeBridge Error] " + e.getMessage());
                return;
            }
        }

        try
        {
            // tsf_render_short(tsf*, short* buf, int samples, int flag_mixing)
            // samples = number of frames; flag_mixing = 0 means overwrite
            tsf_render_short.invokeExact(device, renderBuffer, stereoFrames, 0);
            MemorySegment.copy(renderBuffer, ValueLayout.JAVA_SHORT, 0, buffer, 0,
                    stereoFrames * 2);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void close()
    {
        if (!device.equals(MemorySegment.NULL))
        {
            try
            {
                tsf_close.invokeExact(device);
            }
            catch (Throwable e)
            {
                err.println("[NativeBridge Error] " + e.getMessage());
            }
            device = MemorySegment.NULL;
        }
        try
        {
            super.close();
        }
        catch (Exception e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }
}
