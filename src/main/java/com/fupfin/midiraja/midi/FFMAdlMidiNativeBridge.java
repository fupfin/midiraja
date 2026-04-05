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

/**
 * FFM implementation of {@link AdlMidiNativeBridge} backed by libADLMIDI.
 *
 * <p>
 * libADLMIDI is NOT thread-safe. All native calls must be made from the render thread, except where
 * noted. The render thread drains the event queue in {@link AdlMidiSynthProvider} before calling
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
public class FFMAdlMidiNativeBridge extends AbstractFFMBridge implements AdlMidiNativeBridge
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
                // adl_init: (long sample_rate) → ADL_MIDIPlayer*
                DESC_INIT,
                // adl_close: (ptr) → void
                // adl_reset, adl_panic share this descriptor
                DESC_VOID_PTR,
                // adl_setBank, adl_setNumChips, adl_switchEmulator: (ptr, int) → int
                DESC_PTR_INT,
                // adl_openBankFile: (ptr, const char*) → int
                DESC_PTR_STR,
                // adl_generate: (ptr, int, short*) → int
                DESC_GENERATE,
                // adl_rt_noteOn: (ptr, uint8_t channel, uint8_t note, uint8_t velocity) → int
                DESC_NOTE_ON,
                // adl_rt_noteOff: (ptr, uint8_t channel, uint8_t note) → void
                // adl_rt_patchChange shares this descriptor
                DESC_NOTE_OFF,
                // adl_rt_controllerChange: (ptr, uint8_t channel, uint8_t type, uint8_t value) →
                // void
                DESC_CTRL_CHANGE,
                // adl_rt_pitchBend: (ptr, uint8_t channel, int16_t pitch) → void
                // JAVA_SHORT and JAVA_BYTE both widen to jint → same metadata key as above
                DESC_PITCH_BEND,
                // adl_rt_systemExclusive: (ptr, const uint8_t* msg, size_t size) → int
                DESC_PTR_PTR_LONG,
                // adl_getBanksCount: () → int
                DESC_NO_ARGS_INT,
                // adl_errorInfo: (ptr) → const char*
                DESC_ERROR_INFO);
    }

    // FFM Method Handles
    private final MethodHandle adl_init;
    private final MethodHandle adl_close;
    private final MethodHandle adl_setBank;
    private final MethodHandle adl_openBankFile;
    private final MethodHandle adl_setNumChips;
    private final MethodHandle adl_switchEmulator;
    private final MethodHandle adl_reset;
    private final MethodHandle adl_panic;
    private final MethodHandle adl_generate;
    private final MethodHandle adl_rt_noteOn;
    private final MethodHandle adl_rt_noteOff;
    private final MethodHandle adl_rt_controllerChange;
    private final MethodHandle adl_rt_patchChange;
    private final MethodHandle adl_rt_pitchBend;
    private final MethodHandle adl_rt_systemExclusive;
    private final MethodHandle adl_getBanksCount;
    private final MethodHandle adl_errorInfo;

    public FFMAdlMidiNativeBridge() throws Exception
    {
        this(Arena.ofShared());
    }

    private FFMAdlMidiNativeBridge(Arena arena) throws Exception
    {
        super(arena, tryLoadLibrary(arena, "adlmidi", "libADLMIDI.dylib", "libADLMIDI.so",
                "libADLMIDI.dll"));

        // ADL_MIDIPlayer* adl_init(long sample_rate)
        adl_init = downcall("adl_init", DESC_INIT);

        // void adl_close(struct ADL_MIDIPlayer *device)
        adl_close = downcall("adl_close", DESC_VOID_PTR);

        // int adl_setBank(struct ADL_MIDIPlayer *device, int bank)
        adl_setBank = downcall("adl_setBank", DESC_PTR_INT);

        // int adl_openBankFile(struct ADL_MIDIPlayer *device, const char *filePath)
        adl_openBankFile = downcall("adl_openBankFile", DESC_PTR_STR);

        // int adl_setNumChips(struct ADL_MIDIPlayer *device, int numChips)
        adl_setNumChips = downcall("adl_setNumChips", DESC_PTR_INT);

        // int adl_switchEmulator(struct ADL_MIDIPlayer *device, int emulatorId)
        adl_switchEmulator = downcall("adl_switchEmulator", DESC_PTR_INT);

        // void adl_reset(struct ADL_MIDIPlayer *device)
        adl_reset = downcall("adl_reset", DESC_VOID_PTR);

        // void adl_panic(struct ADL_MIDIPlayer *device)
        adl_panic = downcall("adl_panic", DESC_VOID_PTR);

        // int adl_generate(struct ADL_MIDIPlayer *device, int numSamples, short *out)
        adl_generate = downcall("adl_generate", DESC_GENERATE);

        // int adl_rt_noteOn(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8 note,
        // ADL_UInt8 velocity)
        adl_rt_noteOn = downcall("adl_rt_noteOn", DESC_NOTE_ON);

        // void adl_rt_noteOff(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8 note)
        adl_rt_noteOff = downcall("adl_rt_noteOff", DESC_NOTE_OFF);

        // void adl_rt_controllerChange(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8
        // type, ADL_UInt8 value)
        adl_rt_controllerChange = downcall("adl_rt_controllerChange", DESC_CTRL_CHANGE);

        // void adl_rt_patchChange(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8
        // patch)
        adl_rt_patchChange = downcall("adl_rt_patchChange", DESC_NOTE_OFF);

        // void adl_rt_pitchBend(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_SInt16 pitch)
        adl_rt_pitchBend = downcall("adl_rt_pitchBend", DESC_PITCH_BEND);

        // int adl_rt_systemExclusive(struct ADL_MIDIPlayer *device, const ADL_UInt8 *msg, size_t
        // size)
        adl_rt_systemExclusive = downcall("adl_rt_systemExclusive", DESC_PTR_PTR_LONG);

        // int adl_getBanksCount()
        adl_getBanksCount = downcall("adl_getBanksCount", DESC_NO_ARGS_INT);

        // const char* adl_errorInfo(struct ADL_MIDIPlayer *device)
        adl_errorInfo = downcall("adl_errorInfo", DESC_ERROR_INFO);
    }

    @Override
    public void init(int sampleRate) throws Exception
    {
        try
        {
            device = (MemorySegment) adl_init.invokeExact((long) sampleRate);
            if (device.equals(MemorySegment.NULL))
            {
                throw new IllegalStateException(
                        "adl_init returned NULL (out of memory or invalid sample rate)");
            }
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new IllegalStateException("Error initializing libADLMIDI", t);
        }
    }

    @Override
    public void setBank(int bankNumber)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) adl_setBank.invokeExact(device, bankNumber);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
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
            int rc = (int) adl_openBankFile.invokeExact(device, pathSeg);
            if (rc != 0)
            {
                throw new IllegalStateException("adl_openBankFile failed (rc=" + rc + "): " + path);
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
            throw new IllegalStateException("Error loading WOPL bank file: " + path, t);
        }
    }

    @Override
    public void setNumChips(int numChips)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) adl_setNumChips.invokeExact(device, numChips);
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
            int ignored = (int) adl_switchEmulator.invokeExact(device, emulatorId);
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
            adl_reset.invokeExact(device);
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
            adl_panic.invokeExact(device);
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
            int ignored = (int) adl_rt_noteOn.invokeExact(device, (byte) channel, (byte) note,
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
            adl_rt_noteOff.invokeExact(device, (byte) channel, (byte) note);
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
            adl_rt_controllerChange.invokeExact(device, (byte) channel, (byte) type, (byte) value);
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
            adl_rt_patchChange.invokeExact(device, (byte) channel, (byte) patch);
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
        // pitch is 14-bit signed (-8192 to +8191); adl_rt_pitchBend takes int16_t
        try
        {
            adl_rt_pitchBend.invokeExact(device, (byte) channel, (short) pitch);
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
            int ignored = (int) adl_rt_systemExclusive.invokeExact(device, seg, (long) data.length);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void generate(short[] buffer, int stereoFrames)
    {
        // adl_generate sampleCount = total shorts in the buffer (L+R interleaved).
        // Passing stereoFrames here only fills half the buffer with valid audio;
        // the rest is garbage → wrong pitch and noise. Must pass buffer.length.
        generateInto(adl_generate, device, buffer);
    }

    @Override
    public int getBanksCount()
    {
        try
        {
            return (int) adl_getBanksCount.invokeExact();
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
                adl_close.invokeExact(device);
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
