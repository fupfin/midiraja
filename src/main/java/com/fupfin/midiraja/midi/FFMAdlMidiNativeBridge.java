/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * FFM implementation of {@link AdlMidiNativeBridge} backed by libADLMIDI.
 *
 * <p>libADLMIDI is NOT thread-safe. All native calls must be made from the
 * render thread, except where noted. The render thread drains the event queue
 * in {@link AdlMidiSynthProvider} before calling {@link #generate}, so MIDI
 * dispatch happens single-threaded.
 *
 * <p><b>Note on JAVA_BYTE/JAVA_SHORT → jint widening:</b> In the C ABI, sub-int
 * types (byte, short) are widened to {@code int} when passed in registers.
 * GraalVM's native image represents them as {@code "jint"} in the leaf type.
 *
 * <p><b>Note on GraalVM scalarisation:</b> See {@link FFMMuntNativeBridge} for
 * details; the same rules apply here.
 */
@SuppressWarnings({"EmptyCatch", "UnusedVariable"})
public class FFMAdlMidiNativeBridge extends AbstractFFMBridge implements AdlMidiNativeBridge
{
    private MemorySegment device = MemorySegment.NULL;

    /**
     * Returns all {@link FunctionDescriptor}s used for FFM downcall handles in this class.
     *
     * <p>Used by {@code NativeMetadataConsistencyTest} to verify that every descriptor is
     * registered in {@code reachability-metadata.json} before a native image build.
     */
    static List<FunctionDescriptor> allDowncallDescriptors()
    {
        return List.of(
            // adl_init: (long sample_rate) → ADL_MIDIPlayer*
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            // adl_close: (ptr) → void
            // adl_reset, adl_panic share this descriptor
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            // adl_setBank, adl_setNumChips, adl_switchEmulator: (ptr, int) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            // adl_openBankFile: (ptr, const char*) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            // adl_generate: (ptr, int, short*) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS),
            // adl_rt_noteOn: (ptr, uint8_t channel, uint8_t note, uint8_t velocity) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE,
                ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE),
            // adl_rt_noteOff: (ptr, uint8_t channel, uint8_t note) → void
            // adl_rt_patchChange shares this descriptor
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE),
            // adl_rt_controllerChange: (ptr, uint8_t channel, uint8_t type, uint8_t value) → void
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE,
                ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE),
            // adl_rt_pitchBend: (ptr, uint8_t channel, int16_t pitch) → void
            // JAVA_SHORT and JAVA_BYTE both widen to jint → same metadata key as above
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_SHORT),
            // adl_rt_systemExclusive: (ptr, const uint8_t* msg, size_t size) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG),
            // adl_getBanksCount: () → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT),
            // adl_errorInfo: (ptr) → const char*
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
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
        super(arena, tryLoadLibrary(arena, "adlmidi", "libADLMIDI.dylib", "libADLMIDI.so", "libADLMIDI.dll"));

        // ADL_MIDIPlayer* adl_init(long sample_rate)
        adl_init = downcall("adl_init", 
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        // void adl_close(struct ADL_MIDIPlayer *device)
        adl_close = downcall("adl_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // int adl_setBank(struct ADL_MIDIPlayer *device, int bank)
        adl_setBank = downcall("adl_setBank", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // int adl_openBankFile(struct ADL_MIDIPlayer *device, const char *filePath)
        adl_openBankFile = downcall("adl_openBankFile", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // int adl_setNumChips(struct ADL_MIDIPlayer *device, int numChips)
        adl_setNumChips = downcall("adl_setNumChips", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // int adl_switchEmulator(struct ADL_MIDIPlayer *device, int emulatorId)
        adl_switchEmulator = downcall("adl_switchEmulator", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // void adl_reset(struct ADL_MIDIPlayer *device)
        adl_reset = downcall("adl_reset", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // void adl_panic(struct ADL_MIDIPlayer *device)
        adl_panic = downcall("adl_panic", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // int adl_generate(struct ADL_MIDIPlayer *device, int numSamples, short *out)
        adl_generate = downcall("adl_generate", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS));

        // int adl_rt_noteOn(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8 note,
        // ADL_UInt8 velocity)
        adl_rt_noteOn = downcall("adl_rt_noteOn", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE,
                ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

        // void adl_rt_noteOff(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8 note)
        adl_rt_noteOff = downcall("adl_rt_noteOff", FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

        // void adl_rt_controllerChange(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8
        // type, ADL_UInt8 value)
        adl_rt_controllerChange =
            downcall("adl_rt_controllerChange", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE,
                    ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

        // void adl_rt_patchChange(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_UInt8
        // patch)
        adl_rt_patchChange = downcall("adl_rt_patchChange", FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

        // void adl_rt_pitchBend(struct ADL_MIDIPlayer *device, ADL_UInt8 channel, ADL_SInt16 pitch)
        adl_rt_pitchBend = downcall("adl_rt_pitchBend", FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_SHORT));

        // int adl_rt_systemExclusive(struct ADL_MIDIPlayer *device, const ADL_UInt8 *msg, size_t
        // size)
        adl_rt_systemExclusive =
            downcall("adl_rt_systemExclusive", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        // int adl_getBanksCount()
        adl_getBanksCount = downcall("adl_getBanksCount", FunctionDescriptor.of(ValueLayout.JAVA_INT));

        // const char* adl_errorInfo(struct ADL_MIDIPlayer *device)
        adl_errorInfo = downcall("adl_errorInfo", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    /**
     * In GraalVM native image, libADLMIDI is statically linked into the binary.
     * {@code loaderLookup()} finds those symbols directly without any dlopen.
     * In JVM mode, we fall back to loading the shared library from disk.
     */


    @Override public void init(int sampleRate) throws Exception
    {
        try
        {
            device = (MemorySegment) adl_init.invokeExact((long) sampleRate);
            if (device.equals(MemorySegment.NULL))
            {
                throw new Exception(
                    "adl_init returned NULL (out of memory or invalid sample rate)");
            }
        }
        catch (Throwable t) {
            System.err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error initializing libADLMIDI", t);
        }
    }

    @Override public void setBank(int bankNumber)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) adl_setBank.invokeExact(device, bankNumber);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void loadBankFile(String path) throws Exception
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment pathSeg = temp.allocateFrom(path);
            int rc = (int) adl_openBankFile.invokeExact(device, pathSeg);
            if (rc != 0)
            {
                throw new Exception("adl_openBankFile failed (rc=" + rc + "): " + path);
            }
        }
        catch (Exception e) {
            System.err.println("[NativeBridge Error] " + e.getMessage());
            throw e;
        }
        catch (Throwable t) {
            System.err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error loading WOPL bank file: " + path, t);
        }
    }

    @Override public void setNumChips(int numChips)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) adl_setNumChips.invokeExact(device, numChips);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void switchEmulator(int emulatorId)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) adl_switchEmulator.invokeExact(device, emulatorId);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void reset()
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            adl_reset.invokeExact(device);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void panic()
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            adl_panic.invokeExact(device);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void noteOn(int channel, int note, int velocity)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored = (int) adl_rt_noteOn.invokeExact(
                device, (byte) channel, (byte) note, (byte) velocity);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void noteOff(int channel, int note)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            adl_rt_noteOff.invokeExact(device, (byte) channel, (byte) note);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void controlChange(int channel, int type, int value)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            adl_rt_controllerChange.invokeExact(device, (byte) channel, (byte) type, (byte) value);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void patchChange(int channel, int patch)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        try
        {
            adl_rt_patchChange.invokeExact(device, (byte) channel, (byte) patch);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void pitchBend(int channel, int pitch)
    {
        if (device.equals(MemorySegment.NULL))
            return;
        // pitch is 14-bit signed (-8192 to +8191); adl_rt_pitchBend takes int16_t
        try
        {
            adl_rt_pitchBend.invokeExact(device, (byte) channel, (short) pitch);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public void systemExclusive(byte[] data)
    {
        if (device.equals(MemorySegment.NULL) || data == null || data.length == 0)
            return;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment seg = temp.allocateFrom(ValueLayout.JAVA_BYTE, data);
            int ignored = (int) adl_rt_systemExclusive.invokeExact(device, seg, (long) data.length);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    // Cached native render buffer to avoid per-frame allocation
    private MemorySegment renderBuffer = MemorySegment.NULL;
    private int currentRenderBufferSize = 0;

    @Override public void generate(short[] buffer, int stereoFrames)
    {
        if (device.equals(MemorySegment.NULL) || buffer == null || buffer.length == 0)
            return;

        int requiredBytes = buffer.length * 2; // 2 bytes per short
        if (currentRenderBufferSize < requiredBytes)
        {
            try
            {
                renderBuffer = arena.allocate(requiredBytes);
                currentRenderBufferSize = requiredBytes;
            }
            catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
                return;
            }
        }

        try
        {
            // adl_generate sampleCount = total shorts in the buffer (L+R interleaved).
            // Passing stereoFrames here only fills half the buffer with valid audio;
            // the rest is garbage → wrong pitch and noise. Must pass buffer.length.
            int ignored = (int) adl_generate.invokeExact(device, buffer.length, renderBuffer);
            MemorySegment.copy(renderBuffer, ValueLayout.JAVA_SHORT, 0, buffer, 0, buffer.length);
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
        }
    }

    @Override public int getBanksCount()
    {
        try
        {
            return (int) adl_getBanksCount.invokeExact();
        }
        catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
            return 0;
        }
    }

    @Override public void close()
    {
        if (!device.equals(MemorySegment.NULL))
        {
            try
            {
                adl_close.invokeExact(device);
            }
            catch (Throwable ignored) {
            System.err.println("[NativeBridge Error] " + ignored.getMessage());
            }
            device = MemorySegment.NULL;
        }
        try { super.close(); } catch(Exception e) {
            System.err.println("[NativeBridge Error] " + e.getMessage());}
    }
}
