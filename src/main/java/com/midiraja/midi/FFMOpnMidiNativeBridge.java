/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

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
 * FFM implementation of {@link OpnMidiNativeBridge} backed by libOPNMIDI.
 *
 * <p>libOPNMIDI provides OPN2 (YM2612, Sega Genesis) and OPNA (YM2608, PC-98)
 * FM synthesis. Unlike libADLMIDI, it has no embedded instrument banks.
 *
 * <p>libOPNMIDI is NOT thread-safe. All native calls must be made from the
 * render thread, except where noted. The render thread drains the event queue
 * in {@link OpnMidiSynthProvider} before calling {@link #generate}, so MIDI
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
public class FFMOpnMidiNativeBridge implements OpnMidiNativeBridge {

    private final Arena arena;
    private MemorySegment device = MemorySegment.NULL;

    /**
     * Returns all {@link FunctionDescriptor}s used for FFM downcall handles in this class.
     *
     * <p>Used by {@code NativeMetadataConsistencyTest} to verify that every descriptor is
     * registered in {@code reachability-metadata.json} before a native image build.
     */
    static List<FunctionDescriptor> allDowncallDescriptors() {
        return List.of(
            // opn2_init: (long sample_rate) → OPN2_MIDIPlayer*
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            // opn2_close: (ptr) → void
            // opn2_reset, opn2_panic share this descriptor
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            // opn2_setNumChips, opn2_switchEmulator: (ptr, int) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            // opn2_openBankFile: (ptr, const char*) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            // opn2_openBankData: (ptr, const void* mem, unsigned long size) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            // opn2_generate: (ptr, int, short*) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            // opn2_rt_noteOn: (ptr, uint8_t channel, uint8_t note, uint8_t velocity) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE),
            // opn2_rt_noteOff: (ptr, uint8_t channel, uint8_t note) → void
            // opn2_rt_patchChange shares this descriptor
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE),
            // opn2_rt_controllerChange: (ptr, uint8_t channel, uint8_t type, uint8_t value) → void
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE),
            // opn2_rt_pitchBend: (ptr, uint8_t channel, uint16_t pitch) → void
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_SHORT),
            // opn2_rt_systemExclusive: (ptr, const uint8_t* msg, size_t size) → int
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            // opn2_errorInfo: (ptr) → const char*
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
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

    public FFMOpnMidiNativeBridge() throws Exception {
        this.arena = Arena.ofShared();

        SymbolLookup lib = findOpnMidiSymbols();
        Linker linker = Linker.nativeLinker();

        // OPN2_MIDIPlayer* opn2_init(long sample_rate)
        opn2_init = linker.downcallHandle(
            lib.find("opn2_init").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );

        // void opn2_close(struct OPN2_MIDIPlayer *device)
        opn2_close = linker.downcallHandle(
            lib.find("opn2_close").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // int opn2_openBankFile(struct OPN2_MIDIPlayer *device, const char *filePath)
        opn2_openBankFile = linker.downcallHandle(
            lib.find("opn2_openBankFile").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // int opn2_openBankData(struct OPN2_MIDIPlayer *device, const void *mem, unsigned long size)
        opn2_openBankData = linker.downcallHandle(
            lib.find("opn2_openBankData").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );

        // int opn2_setNumChips(struct OPN2_MIDIPlayer *device, int numChips)
        opn2_setNumChips = linker.downcallHandle(
            lib.find("opn2_setNumChips").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        // int opn2_switchEmulator(struct OPN2_MIDIPlayer *device, int emulatorId)
        opn2_switchEmulator = linker.downcallHandle(
            lib.find("opn2_switchEmulator").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        // void opn2_reset(struct OPN2_MIDIPlayer *device)
        opn2_reset = linker.downcallHandle(
            lib.find("opn2_reset").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // void opn2_panic(struct OPN2_MIDIPlayer *device)
        opn2_panic = linker.downcallHandle(
            lib.find("opn2_panic").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // int opn2_generate(struct OPN2_MIDIPlayer *device, int numSamples, short *out)
        opn2_generate = linker.downcallHandle(
            lib.find("opn2_generate").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        // int opn2_rt_noteOn(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt8 note, OPN2_UInt8 velocity)
        opn2_rt_noteOn = linker.downcallHandle(
            lib.find("opn2_rt_noteOn").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE)
        );

        // void opn2_rt_noteOff(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt8 note)
        opn2_rt_noteOff = linker.downcallHandle(
            lib.find("opn2_rt_noteOff").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE)
        );

        // void opn2_rt_controllerChange(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt8 type, OPN2_UInt8 value)
        opn2_rt_controllerChange = linker.downcallHandle(
            lib.find("opn2_rt_controllerChange").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE)
        );

        // void opn2_rt_patchChange(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt8 patch)
        opn2_rt_patchChange = linker.downcallHandle(
            lib.find("opn2_rt_patchChange").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE)
        );

        // void opn2_rt_pitchBend(struct OPN2_MIDIPlayer *device, OPN2_UInt8 channel, OPN2_UInt16 pitch)
        opn2_rt_pitchBend = linker.downcallHandle(
            lib.find("opn2_rt_pitchBend").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.JAVA_SHORT)
        );

        // int opn2_rt_systemExclusive(struct OPN2_MIDIPlayer *device, const OPN2_UInt8 *msg, size_t size)
        opn2_rt_systemExclusive = linker.downcallHandle(
            lib.find("opn2_rt_systemExclusive").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );

        // const char* opn2_errorInfo(struct OPN2_MIDIPlayer *device)
        opn2_errorInfo = linker.downcallHandle(
            lib.find("opn2_errorInfo").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
    }

    /**
     * In GraalVM native image, libOPNMIDI is statically linked into the binary.
     * {@code loaderLookup()} finds those symbols directly without any dlopen.
     * In JVM mode, we fall back to loading the shared library from disk.
     */
    private SymbolLookup findOpnMidiSymbols() {
        if (SymbolLookup.loaderLookup().find("opn2_init").isPresent()) {
            return SymbolLookup.loaderLookup();
        }
        return tryLoadSharedLibrary(arena, "libOPNMIDI.dylib", "libOPNMIDI.so");
    }

    private SymbolLookup tryLoadSharedLibrary(Arena arena, String... paths) {
        List<String> failedPaths = new ArrayList<>();
        String projectRoot = new File("").getAbsolutePath();
        String devPathMac   = projectRoot + "/src/main/c/opnmidi/libOPNMIDI.dylib";
        String devPathLinux = projectRoot + "/src/main/c/opnmidi/libOPNMIDI.so";

        String[] allPaths = new String[paths.length + 2];
        System.arraycopy(paths, 0, allPaths, 0, paths.length);
        allPaths[paths.length]     = devPathMac;
        allPaths[paths.length + 1] = devPathLinux;

        for (String path : allPaths) {
            try {
                if (path.startsWith("/")) {
                    File f = new File(path);
                    if (f.exists()) {
                        return SymbolLookup.libraryLookup(f.toPath(), arena);
                    } else {
                        failedPaths.add(path + " (not found)");
                    }
                } else {
                    return SymbolLookup.libraryLookup(path, arena);
                }
            } catch (IllegalArgumentException e) {
                failedPaths.add(path);
            }
        }
        throw new IllegalArgumentException(
            "Cannot open libOPNMIDI. Build it first with: ./scripts/build-native-libs.sh\n"
            + "Searched paths: " + String.join(", ", failedPaths));
    }

    @Override
    public void init(int sampleRate) throws Exception {
        try {
            device = (MemorySegment) opn2_init.invokeExact((long) sampleRate);
            if (device.equals(MemorySegment.NULL)) {
                throw new Exception("opn2_init returned NULL (out of memory or invalid sample rate)");
            }
        } catch (Throwable t) {
            throw new Exception("Error initializing libOPNMIDI", t);
        }
    }

    @Override
    public void loadBankFile(String path) throws Exception {
        if (device.equals(MemorySegment.NULL)) return;
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pathSeg = temp.allocateFrom(path);
            int rc = (int) opn2_openBankFile.invokeExact(device, pathSeg);
            if (rc != 0) {
                throw new Exception("opn2_openBankFile failed (rc=" + rc + "): " + path);
            }
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception("Error loading WOPN bank file: " + path, t);
        }
    }

    @Override
    public void loadBankData(byte[] data) throws Exception {
        if (device.equals(MemorySegment.NULL) || data == null || data.length == 0) return;
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment seg = temp.allocateFrom(ValueLayout.JAVA_BYTE, data);
            int rc = (int) opn2_openBankData.invokeExact(device, seg, (long) data.length);
            if (rc != 0) {
                throw new Exception("opn2_openBankData failed (rc=" + rc + ")");
            }
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception("Error loading WOPN bank data", t);
        }
    }

    @Override
    public void setNumChips(int numChips) {
        if (device.equals(MemorySegment.NULL)) return;
        try { int ignored = (int) opn2_setNumChips.invokeExact(device, numChips); }
        catch (Throwable ignored) {}
    }

    @Override
    public void switchEmulator(int emulatorId) {
        if (device.equals(MemorySegment.NULL)) return;
        try { int ignored = (int) opn2_switchEmulator.invokeExact(device, emulatorId); }
        catch (Throwable ignored) {}
    }

    @Override
    public void reset() {
        if (device.equals(MemorySegment.NULL)) return;
        try { opn2_reset.invokeExact(device); }
        catch (Throwable ignored) {}
    }

    @Override
    public void panic() {
        if (device.equals(MemorySegment.NULL)) return;
        try { opn2_panic.invokeExact(device); }
        catch (Throwable ignored) {}
    }

    @Override
    public void noteOn(int channel, int note, int velocity) {
        if (device.equals(MemorySegment.NULL)) return;
        try { int ignored = (int) opn2_rt_noteOn.invokeExact(device, (byte) channel, (byte) note, (byte) velocity); }
        catch (Throwable ignored) {}
    }

    @Override
    public void noteOff(int channel, int note) {
        if (device.equals(MemorySegment.NULL)) return;
        try { opn2_rt_noteOff.invokeExact(device, (byte) channel, (byte) note); }
        catch (Throwable ignored) {}
    }

    @Override
    public void controlChange(int channel, int type, int value) {
        if (device.equals(MemorySegment.NULL)) return;
        try { opn2_rt_controllerChange.invokeExact(device, (byte) channel, (byte) type, (byte) value); }
        catch (Throwable ignored) {}
    }

    @Override
    public void patchChange(int channel, int patch) {
        if (device.equals(MemorySegment.NULL)) return;
        try { opn2_rt_patchChange.invokeExact(device, (byte) channel, (byte) patch); }
        catch (Throwable ignored) {}
    }

    @Override
    public void pitchBend(int channel, int pitch) {
        if (device.equals(MemorySegment.NULL)) return;
        // pitch is 14-bit unsigned (0–16383); opn2_rt_pitchBend takes uint16_t
        try { opn2_rt_pitchBend.invokeExact(device, (byte) channel, (short) pitch); }
        catch (Throwable ignored) {}
    }

    @Override
    public void systemExclusive(byte[] data) {
        if (device.equals(MemorySegment.NULL) || data == null || data.length == 0) return;
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment seg = temp.allocateFrom(ValueLayout.JAVA_BYTE, data);
            int ignored = (int) opn2_rt_systemExclusive.invokeExact(device, seg, (long) data.length);
        } catch (Throwable ignored) {}
    }

    // Cached native render buffer to avoid per-frame allocation
    private MemorySegment renderBuffer = MemorySegment.NULL;
    private int currentRenderBufferSize = 0;

    @Override
    public void generate(short[] buffer, int stereoFrames) {
        if (device.equals(MemorySegment.NULL) || buffer == null || buffer.length == 0) return;

        int requiredBytes = buffer.length * 2; // 2 bytes per short
        if (currentRenderBufferSize < requiredBytes) {
            try {
                renderBuffer = arena.allocate(requiredBytes);
                currentRenderBufferSize = requiredBytes;
            } catch (Throwable ignored) {
                return;
            }
        }

        try {
            // opn2_generate sampleCount = total shorts in the buffer (L+R interleaved).
            int ignored = (int) opn2_generate.invokeExact(device, buffer.length, renderBuffer);
            MemorySegment.copy(renderBuffer, ValueLayout.JAVA_SHORT, 0, buffer, 0, buffer.length);
        } catch (Throwable ignored) {}
    }

    @Override
    public void close() {
        if (!device.equals(MemorySegment.NULL)) {
            try { opn2_close.invokeExact(device); }
            catch (Throwable ignored) {}
            device = MemorySegment.NULL;
        }
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
