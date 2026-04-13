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
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * FFM implementation of {@link OpnMidiNativeBridge} backed by libOPNMIDI.
 *
 * <p>
 * libOPNMIDI provides OPN2 (YM2612, Sega Genesis) and OPNA (YM2608, PC-98) FM synthesis. Unlike
 * libADLMIDI, it has no embedded instrument banks.
 *
 * <p>
 * libOPNMIDI is NOT thread-safe. All native calls must be made from the render thread, except where
 * noted. The render thread drains the event queue in {@link OpnMidiSynthProvider} before calling
 * {@link #generate}, so MIDI dispatch happens single-threaded.
 *
 * <p>
 * <b>Note on JAVA_BYTE/JAVA_SHORT → jint widening:</b> In the C ABI, sub-int types (byte, short)
 * are widened to {@code int} when passed in registers. GraalVM's native image represents them as
 * {@code "jint"} in the leaf type.
 *
 * <p>
 * <b>Note on GraalVM scalarisation:</b> See {@link FFMMuntNativeBridge} for details; the same rules
 * apply here.
 */
@SuppressWarnings({ "EmptyCatch", "UnusedVariable" })
public class FFMOpnMidiNativeBridge extends AbstractFFMBridge implements OpnMidiNativeBridge
{
    private MemorySegment device = MemorySegment.NULL;

    /**
     * Returns all {@link FunctionDescriptor}s used for FFM downcall handles in this class.
     *
     * <p>
     * Used by {@code NativeMetadataConsistencyTest} to verify that every descriptor is registered
     * in {@code reachability-metadata.json} before a native image build.
     */
    static List<FunctionDescriptor> allDowncallDescriptors()
    {
        return List.of(
                // opn2_init: (long sample_rate) → OPN2_MIDIPlayer*
                DESC_INIT,
                // opn2_close: (ptr) → void
                // opn2_reset, opn2_panic share this descriptor
                DESC_VOID_PTR,
                // opn2_setNumChips, opn2_switchEmulator: (ptr, int) → int
                DESC_PTR_INT,
                // opn2_openBankFile: (ptr, const char*) → int
                DESC_PTR_STR,
                // opn2_openBankData: (ptr, const void* mem, unsigned long size) → int
                DESC_PTR_PTR_LONG,
                // opn2_generate: (ptr, int, short*) → int
                DESC_GENERATE,
                // opn2_rt_noteOn: (ptr, uint8_t channel, uint8_t note, uint8_t velocity) → int
                DESC_NOTE_ON,
                // opn2_rt_noteOff: (ptr, uint8_t channel, uint8_t note) → void
                // opn2_rt_patchChange shares this descriptor
                DESC_NOTE_OFF,
                // opn2_rt_controllerChange: (ptr, uint8_t channel, uint8_t type, uint8_t value) →
                // void
                DESC_CTRL_CHANGE,
                // opn2_rt_pitchBend: (ptr, uint8_t channel, uint16_t pitch) → void
                DESC_PITCH_BEND,
                // opn2_rt_systemExclusive: (ptr, const uint8_t* msg, size_t size) → int
                DESC_PTR_PTR_LONG,
                // opn2_errorInfo: (ptr) → const char*
                DESC_ERROR_INFO);
    }

    // FFM Method Handles
    private final MethodHandle opn2_init;
    private final MethodHandle opn2_close;
    private final MethodHandle opn2_openBankFile;
    private final MethodHandle opn2_openBankData;
    private final MethodHandle opn2_setNumChips;
    private final MethodHandle opn2_switchEmulator;
    private final MethodHandle opn2_reset;
    private final MethodHandle opn2_panic;
    private final MethodHandle opn2_generate;
    private final MethodHandle opn2_rt_noteOn;
    private final MethodHandle opn2_rt_noteOff;
    private final MethodHandle opn2_rt_controllerChange;
    private final MethodHandle opn2_rt_patchChange;
    private final MethodHandle opn2_rt_pitchBend;
    private final MethodHandle opn2_rt_systemExclusive;
    private final MethodHandle opn2_errorInfo;
    // VGMFileDumper / file-based playback
    private final @Nullable MethodHandle opn2_set_vgm_out_path;
    private final MethodHandle opn2_openData;
    private final MethodHandle opn2_play;

    public FFMOpnMidiNativeBridge() throws Exception
    {
        this(Arena.ofShared());
    }

    private FFMOpnMidiNativeBridge(Arena arena) throws Exception
    {
        super(arena, tryLoadLibrary(arena, "opnmidi", "libOPNMIDI.dylib", "libOPNMIDI.so",
                "libOPNMIDI.dll"));

        // OPN2_MIDIPlayer* opn2_init(long sample_rate)
        opn2_init = downcall("opn2_init", DESC_INIT);

        // void opn2_close(struct OPN2_MIDIPlayer *device)
        opn2_close = downcall("opn2_close", DESC_VOID_PTR);

        // int opn2_openBankFile(struct OPN2_MIDIPlayer *device, const char *filePath)
        opn2_openBankFile = downcall("opn2_openBankFile", DESC_PTR_STR);

        // int opn2_openBankData(struct OPN2_MIDIPlayer *device, const void *mem, unsigned long
        // size)
        opn2_openBankData = downcall("opn2_openBankData", DESC_PTR_PTR_LONG);

        // int opn2_setNumChips(struct OPN2_MIDIPlayer *device, int numChips)
        opn2_setNumChips = downcall("opn2_setNumChips", DESC_PTR_INT);

        // int opn2_switchEmulator(struct OPN2_MIDIPlayer *device, int emulatorId)
        opn2_switchEmulator = downcall("opn2_switchEmulator", DESC_PTR_INT);

        // void opn2_reset(struct OPN2_MIDIPlayer *device)
        opn2_reset = downcall("opn2_reset", DESC_VOID_PTR);

        // void opn2_panic(struct OPN2_MIDIPlayer *device)
        opn2_panic = downcall("opn2_panic", DESC_VOID_PTR);

        // int opn2_generate(struct OPN2_MIDIPlayer *device, int numSamples, short *out)
        opn2_generate = downcall("opn2_generate", DESC_GENERATE);

        // int opn2_rt_noteOn(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt8 note,
        // OPN2_UInt8 velocity)
        opn2_rt_noteOn = downcall("opn2_rt_noteOn", DESC_NOTE_ON);

        // void opn2_rt_noteOff(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt8 note)
        opn2_rt_noteOff = downcall("opn2_rt_noteOff", DESC_NOTE_OFF);

        // void opn2_rt_controllerChange(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel,
        // OPN2_UInt8 type, OPN2_UInt8 value)
        opn2_rt_controllerChange = downcall("opn2_rt_controllerChange", DESC_CTRL_CHANGE);

        // void opn2_rt_patchChange(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt8
        // patch)
        opn2_rt_patchChange = downcall("opn2_rt_patchChange", DESC_NOTE_OFF);

        // void opn2_rt_pitchBend(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt16
        // pitch)
        opn2_rt_pitchBend = downcall("opn2_rt_pitchBend", DESC_PITCH_BEND);

        // int opn2_rt_systemExclusive(struct OPN2_MIDIPlayer *device, const OPN2_UInt8 *msg, size_t
        // size)
        opn2_rt_systemExclusive = downcall("opn2_rt_systemExclusive", DESC_PTR_PTR_LONG);

        // const char* opn2_errorInfo(struct OPN2_MIDIPlayer *device)
        opn2_errorInfo = downcall("opn2_errorInfo", DESC_ERROR_INFO);

        // void opn2_set_vgm_out_path(const char *path) — not in public header, optional
        opn2_set_vgm_out_path = optionalDowncall("opn2_set_vgm_out_path", DESC_VOID_PTR);

        // int opn2_openData(struct OPN2_MIDIPlayer *device, const void *mem, unsigned long size)
        opn2_openData = downcall("opn2_openData", DESC_PTR_PTR_LONG);

        // int opn2_play(struct OPN2_MIDIPlayer *device, int sampleCount, short *out)
        opn2_play = downcall("opn2_play", DESC_GENERATE);
    }

    @Override
    public void init(int sampleRate) throws Exception
    {
        try
        {
            device = (MemorySegment) opn2_init.invokeExact((long) sampleRate);
            if (device.equals(MemorySegment.NULL))
            {
                throw new IllegalStateException(
                        "opn2_init returned NULL (out of memory or invalid sample rate)");
            }
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new IllegalStateException("Error initializing libOPNMIDI", t);
        }
    }

    @Override
    public void loadBankFile(String path) throws Exception
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment pathSeg = temp.allocateFrom(path);
            int rc = (int) opn2_openBankFile.invokeExact(device, pathSeg);
            if (rc != 0)
            {
                throw new IllegalStateException(
                        "opn2_openBankFile failed (rc=" + rc + "): " + path);
            }
        }
        catch (Exception e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            throw e;
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new IllegalStateException("Error loading WOPN bank file: " + path, t);
        }
    }

    @Override
    public void loadBankData(byte[] data) throws Exception
    {
        if (device.equals(MemorySegment.NULL) || data == null || data.length == 0)
            return;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment seg = temp.allocateFrom(ValueLayout.JAVA_BYTE, data);
            int rc = (int) opn2_openBankData.invokeExact(device, seg, (long) data.length);
            if (rc != 0)
            {
                throw new IllegalStateException("opn2_openBankData failed (rc=" + rc + ")");
            }
        }
        catch (Exception e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            throw e;
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new IllegalStateException("Error loading WOPN bank data", t);
        }
    }

    @Override
    public void setNumChips(int numChips)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) opn2_setNumChips.invokeExact(device, numChips);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void switchEmulator(int emulatorId)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) opn2_switchEmulator.invokeExact(device, emulatorId);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void reset()
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            opn2_reset.invokeExact(device);
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
            opn2_panic.invokeExact(device);
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
            int ignored = (int) opn2_rt_noteOn.invokeExact(device, (byte) channel, (byte) note,
                    (byte) velocity);
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
            opn2_rt_noteOff.invokeExact(device, (byte) channel, (byte) note);
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
            opn2_rt_controllerChange.invokeExact(device, (byte) channel, (byte) type, (byte) value);
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
            opn2_rt_patchChange.invokeExact(device, (byte) channel, (byte) patch);
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
        // pitch is 14-bit unsigned (0–16383); opn2_rt_pitchBend takes uint16_t
        try
        {
            opn2_rt_pitchBend.invokeExact(device, (byte) channel, (short) pitch);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void systemExclusive(byte[] data)
    {
        if (device.equals(MemorySegment.NULL) || data == null || data.length == 0)
            return;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment seg = temp.allocateFrom(ValueLayout.JAVA_BYTE, data);
            int ignored = (int) opn2_rt_systemExclusive.invokeExact(device, seg, (long) data.length);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void generate(short[] buffer, int stereoFrames)
    {
        // opn2_generate sampleCount = total shorts in the buffer (L+R interleaved).
        generateInto(opn2_generate, device, buffer);
    }

    @Override
    public void setVgmOutPath(String path)
    {
        if (opn2_set_vgm_out_path == null)
            return;
        // Allocate from the bridge's long-lived arena — libOPNMIDI stores a raw pointer to this
        // string in the global g_vgm_path and dereferences it during close()/fopen(), so the
        // allocation must outlive the entire export sequence, not just this method call.
        try
        {
            MemorySegment pathSeg = arena.allocateFrom(path);
            opn2_set_vgm_out_path.invokeExact(pathSeg);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void openMidiData(byte[] midiBytes) throws Exception
    {
        if (device.equals(MemorySegment.NULL) || midiBytes == null || midiBytes.length == 0)
            return;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment seg = temp.allocateFrom(ValueLayout.JAVA_BYTE, midiBytes);
            int rc = (int) opn2_openData.invokeExact(device, seg, (long) midiBytes.length);
            if (rc != 0)
            {
                throw new IllegalStateException("opn2_openData failed (rc=" + rc + ")");
            }
        }
        catch (Exception e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            throw e;
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new IllegalStateException("Error loading MIDI data", t);
        }
    }

    @Override
    public int playFromFile(short[] buffer)
    {
        if (device.equals(MemorySegment.NULL) || buffer == null || buffer.length == 0)
            return 0;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment outBuf = temp.allocate((long) buffer.length * 2);
            int written = (int) opn2_play.invokeExact(device, buffer.length, outBuf);
            if (written > 0)
                MemorySegment.copy(outBuf, ValueLayout.JAVA_SHORT, 0, buffer, 0,
                        Math.min(written, buffer.length));
            return written;
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            return 0;
        }
    }

    @Override
    public void close()
    {
        if (!device.equals(MemorySegment.NULL))
        {
            try
            {
                opn2_close.invokeExact(device);
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
