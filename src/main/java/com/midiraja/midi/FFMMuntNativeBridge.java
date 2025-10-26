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

@SuppressWarnings({"EmptyCatch", "UnusedVariable"})
public class FFMMuntNativeBridge implements MuntNativeBridge {

    private final Arena arena;
    private MemorySegment context = MemorySegment.NULL;

    // Timing reference for computing MIDI event timestamps.
    // The render thread writes these after each renderAudio() call.
    // The playback thread reads them in playNoteOn() etc. to produce
    // wall-clock-derived future timestamps for mt32emu_play_msg_at.
    //
    // Why this matters: mt32emu_render_bit16s splits each render chunk
    // at event boundaries (see doRenderStreams in Synth.cpp). Events with
    // distinct *future* timestamps produce audio at distinct sample positions,
    // giving notes a proper duration. Events with past or equal timestamps
    // collapse to a 1-sample gap and are inaudible. The wall-clock offset
    // from the last completed render keeps timestamps strictly increasing
    // even while the render thread is blocked in audio.push().
    private volatile int  lastRenderedSampleCount = 0;
    private volatile long lastRenderCompletedNanos = System.nanoTime();

    // FFM Method Handles
    private final MethodHandle mt32emu_create_context;
    private final MethodHandle mt32emu_free_context;
    private final MethodHandle mt32emu_add_rom_file;
    private final MethodHandle mt32emu_set_stereo_output_samplerate;
    private final MethodHandle mt32emu_set_master_volume_override;
    private final MethodHandle mt32emu_open_synth;
    private final MethodHandle mt32emu_close_synth;
    // Thread-safe timestamped API (mt32emu_play_msg_at / mt32emu_play_sysex_at).
    // These enqueue into Munt's internal MidiEventQueue. The render thread's
    // mt32emu_render_bit16s drains that queue at the correct sample positions.
    private final MethodHandle mt32emu_play_msg_at;
    private final MethodHandle mt32emu_play_sysex_at;
    private final MethodHandle mt32emu_get_internal_rendered_sample_count;
    private final MethodHandle mt32emu_render_bit16s;

    public FFMMuntNativeBridge() throws Exception {
        this.arena = Arena.ofShared();

        SymbolLookup lib = tryLoadLibrary(arena, "libmt32emu.dylib", "libmt32emu.so", "libmt32emu.dll");
        Linker linker = Linker.nativeLinker();

        // mt32emu_context mt32emu_create_context(mt32emu_report_handler_i report_handler, void *instance_data)
        mt32emu_create_context = linker.downcallHandle(
            lib.find("mt32emu_create_context").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void mt32emu_free_context(mt32emu_context context)
        mt32emu_free_context = linker.downcallHandle(
            lib.find("mt32emu_free_context").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // mt32emu_return_code mt32emu_add_rom_file(mt32emu_context context, const char *filename)
        mt32emu_add_rom_file = linker.downcallHandle(
            lib.find("mt32emu_add_rom_file").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // void mt32emu_set_stereo_output_samplerate(mt32emu_context context, const double samplerate)
        mt32emu_set_stereo_output_samplerate = linker.downcallHandle(
            lib.find("mt32emu_set_stereo_output_samplerate").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE)
        );

        // void mt32emu_set_master_volume_override(mt32emu_const_context context, mt32emu_bit8u volume_override)
        // value > 100 disables the override; value <= 100 caps master volume to that value.
        mt32emu_set_master_volume_override = linker.downcallHandle(
            lib.find("mt32emu_set_master_volume_override").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE)
        );

        // mt32emu_return_code mt32emu_open_synth(mt32emu_const_context context)
        mt32emu_open_synth = linker.downcallHandle(
            lib.find("mt32emu_open_synth").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        // void mt32emu_close_synth(mt32emu_const_context context)
        mt32emu_close_synth = linker.downcallHandle(
            lib.find("mt32emu_close_synth").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // mt32emu_return_code mt32emu_play_msg_at(context, msg, timestamp) — thread-safe
        mt32emu_play_msg_at = linker.downcallHandle(
            lib.find("mt32emu_play_msg_at").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // mt32emu_return_code mt32emu_play_sysex_at(context, sysex, len, timestamp) — thread-safe
        mt32emu_play_sysex_at = linker.downcallHandle(
            lib.find("mt32emu_play_sysex_at").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // mt32emu_bit32u mt32emu_get_internal_rendered_sample_count(context)
        mt32emu_get_internal_rendered_sample_count = linker.downcallHandle(
            lib.find("mt32emu_get_internal_rendered_sample_count").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        // void mt32emu_render_bit16s(mt32emu_const_context context, mt32emu_bit16s *stream, mt32emu_bit32u len)
        mt32emu_render_bit16s = linker.downcallHandle(
            lib.find("mt32emu_render_bit16s").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
    }

    private SymbolLookup tryLoadLibrary(Arena arena, String... paths) {
        List<String> failedPaths = new ArrayList<>();
        // Also look in our local build dir for development ease
        String projectRoot = new File("").getAbsolutePath();
        String devPathMac = projectRoot + "/src/main/c/munt/libmt32emu.dylib";
        String devPathLinux = projectRoot + "/src/main/c/munt/libmt32emu.so";

        String[] allPaths = new String[paths.length + 2];
        System.arraycopy(paths, 0, allPaths, 0, paths.length);
        allPaths[paths.length] = devPathMac;
        allPaths[paths.length + 1] = devPathLinux;

        for (String path : allPaths) {
            try {
                if (path.startsWith("/")) {
                    File f = new File(path);
                    if (f.exists()) {
                        return SymbolLookup.libraryLookup(f.toPath(), arena);
                    }
                } else {
                    return SymbolLookup.libraryLookup(path, arena);
                }
            } catch (IllegalArgumentException e) {
                failedPaths.add(path);
            }
        }
        throw new IllegalArgumentException("Cannot open libmt32emu. Searched paths: " + String.join(", ", failedPaths));
    }

    @Override
    public void createSynth() throws Exception {
        try {
            context = (MemorySegment) mt32emu_create_context.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
            if (context.equals(MemorySegment.NULL)) {
                throw new Exception("Failed to create Munt context");
            }
        } catch (Throwable t) {
            throw new Exception("Error creating Munt context", t);
        }
    }

    @Override
    public void loadRoms(String romDirectory) throws Exception {
        if (context.equals(MemorySegment.NULL)) return;

        File dir = new File(romDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new Exception("Munt ROM directory not found: " + romDirectory);
        }

        File controlRom = new File(dir, "MT32_CONTROL.ROM");
        File pcmRom = new File(dir, "MT32_PCM.ROM");

        if (!controlRom.exists() || !pcmRom.exists()) {
             // Let's also try lowercase
             controlRom = new File(dir, "mt32_control.rom");
             pcmRom = new File(dir, "mt32_pcm.rom");
             if (!controlRom.exists() || !pcmRom.exists()) {
                 throw new Exception("Missing MT32_CONTROL.ROM or MT32_PCM.ROM in " + romDirectory);
             }
        }

        try {
            MemorySegment ctrlPathStr = arena.allocateFrom(controlRom.getAbsolutePath());
            int rc1 = (int) mt32emu_add_rom_file.invokeExact(context, ctrlPathStr);
            if (rc1 <= 0) throw new Exception("Munt engine rejected control ROM (return code: " + rc1 + "): " + controlRom.getAbsolutePath());

            MemorySegment pcmPathStr = arena.allocateFrom(pcmRom.getAbsolutePath());
            int rc2 = (int) mt32emu_add_rom_file.invokeExact(context, pcmPathStr);
            if (rc2 <= 0) throw new Exception("Munt engine rejected PCM ROM (return code: " + rc2 + "): " + pcmRom.getAbsolutePath());
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception("Error invoking Munt ROM API", t);
        }
    }

    @Override
    public void openSynth() throws Exception {
        if (context.equals(MemorySegment.NULL)) return;
        try {
            // Disable the master volume override before opening the synth.
            // Munt's Extensions struct is zero-initialized, leaving masterVolumeOverride = 0.
            // The condition in Synth::open() is "if (masterVolumeOverride < 100)", so value 0
            // silences all output by overriding master volume to 0.
            // Passing 0xFF (> 100) disables the override and keeps the ROM default of 100.
            mt32emu_set_master_volume_override.invokeExact(context, (byte) 0xFF);

            // Set the sample rate to match miniaudio (32000 Hz)
            mt32emu_set_stereo_output_samplerate.invokeExact(context, 32000.0);

            int rc = (int) mt32emu_open_synth.invokeExact(context);
            if (rc != 0) throw new Exception("Failed to open Munt synth (Check if ROMs are valid)");
        } catch (Throwable t) {
            throw new Exception("Error opening Munt synth", t);
        }
    }

    // Compute the Munt sample-count timestamp for a MIDI event queued now.
    //
    // Formula: lastRenderedSampleCount + wall_clock_elapsed_since_last_render * 32000 Hz
    //
    // The render thread updates lastRenderedSampleCount / lastRenderCompletedNanos after
    // every renderAudio() call, then blocks in audio.push() until the ring buffer drains.
    // During that block Munt's sample counter stops advancing, but the wall clock keeps
    // ticking. Adding the elapsed wall-clock time (converted to samples) makes each event
    // queued at a different wall-clock instant get a strictly-increasing future timestamp.
    // mt32emu_render_bit16s then places each event at the correct sample position within
    // the rendered chunk (Synth::doRenderStreams splits the chunk at event timestamps).
    private int computeTimestamp() {
        long elapsedNanos = System.nanoTime() - lastRenderCompletedNanos;
        int elapsedSamples = (int)(elapsedNanos * 32000L / 1_000_000_000L);
        return lastRenderedSampleCount + elapsedSamples;
    }

    private void playMsg(int packed) {
        if (context.equals(MemorySegment.NULL)) return;
        try { int ignored2 = (int) mt32emu_play_msg_at.invokeExact(context, packed, computeTimestamp()); }
        catch (Throwable ignored) {}
    }

    @Override
    public void playNoteOn(int channel, int key, int velocity) {
        playMsg(0x90 | channel | (key << 8) | (velocity << 16));
    }

    @Override
    public void playNoteOff(int channel, int key) {
        playMsg(0x80 | channel | (key << 8));
    }

    @Override
    public void playControlChange(int channel, int number, int value) {
        playMsg(0xB0 | channel | (number << 8) | (value << 16));
    }

    @Override
    public void playProgramChange(int channel, int program) {
        playMsg(0xC0 | channel | (program << 8));
    }

    @Override
    public void playPitchBend(int channel, int value) {
        int lsb = value & 0x7F;
        int msb = (value >> 7) & 0x7F;
        playMsg(0xE0 | channel | (lsb << 8) | (msb << 16));
    }

    @Override
    public void playSysex(byte[] sysexData) {
        if (context.equals(MemorySegment.NULL) || sysexData == null || sysexData.length == 0) return;
        int timestamp = computeTimestamp();
        // Use a confined arena so native memory is freed immediately after the call.
        // mt32emu_play_sysex_at copies the data into its internal queue before returning.
        try (Arena tempArena = Arena.ofConfined()) {
            MemorySegment seg = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, sysexData);
            int ignored2 = (int) mt32emu_play_sysex_at.invokeExact(context, seg, sysexData.length, timestamp);
        } catch (Throwable ignored) {}
    }

    // Cached buffer for audio rendering to avoid GC spikes
    private MemorySegment renderBuffer = MemorySegment.NULL;
    private int currentRenderBufferSize = 0;

    // Called exclusively from the render thread. Munt's internal MidiEventQueue
    // (populated by the playback thread via mt32emu_play_msg_at / mt32emu_play_sysex_at)
    // is drained automatically during mt32emu_render_bit16s at the correct sample
    // positions. No Java-side queue drain is needed here.
    @Override
    public void renderAudio(short[] buffer, int frames) {
        if (context.equals(MemorySegment.NULL) || buffer == null || buffer.length == 0) return;

        // Ensure the native render buffer is large enough.
        int requiredBytes = buffer.length * 2; // 2 bytes per short
        if (currentRenderBufferSize < requiredBytes) {
            try {
                renderBuffer = arena.allocate(requiredBytes);
                currentRenderBufferSize = requiredBytes;
            } catch (Throwable ignored) {
                return; // Cannot allocate render buffer; skip this cycle
            }
        }

        try {
            mt32emu_render_bit16s.invokeExact(context, renderBuffer, frames);
            MemorySegment.copy(renderBuffer, ValueLayout.JAVA_SHORT, 0, buffer, 0, buffer.length);
        } catch (Throwable ignored) {}

        // Update the timing reference AFTER rendering so computeTimestamp() in the
        // playback thread produces timestamps relative to the just-completed render.
        try {
            lastRenderedSampleCount = (int) mt32emu_get_internal_rendered_sample_count.invokeExact(context);
        } catch (Throwable ignored) {}
        lastRenderCompletedNanos = System.nanoTime();
    }

    @Override
    public void close() {
        if (!context.equals(MemorySegment.NULL)) {
            try {
                mt32emu_close_synth.invokeExact(context);
                mt32emu_free_context.invokeExact(context);
            } catch (Throwable ignored) {}
            context = MemorySegment.NULL;
        }
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
