/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static java.lang.System.err;

import java.io.File;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

@SuppressWarnings({ "EmptyCatch", "UnusedVariable" })
public class FFMMuntNativeBridge extends AbstractFFMBridge implements MuntNativeBridge
{
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
    private volatile int lastRenderedSampleCount = 0;
    private volatile long lastRenderCompletedNanos = System.nanoTime();

    /**
     * Returns all {@link FunctionDescriptor}s used for FFM downcall handles in this class.
     *
     * <p>
     * Used by {@code NativeMetadataConsistencyTest} to verify that every descriptor is registered
     * in {@code reachability-metadata.json} before a native image build. Keeping this list in sync
     * with the constructor's {@code linker.downcallHandle()} calls is a compile-time enforced
     * "reminder" — if you add a new handle, add it here too, then add the corresponding JSON entry
     * in the metadata file.
     *
     * <p>
     * <b>Note on JAVA_BYTE → jint widening:</b> In the C ABI, sub-int types (byte, short) are
     * widened to {@code int} when passed in registers. GraalVM's native image represents them as
     * {@code "jint"} in the leaf type. So {@code JAVA_BYTE} parameters map to {@code "jint"} in the
     * metadata, and a {@code (void*, jbyte, void*, void*) → jint} descriptor registers as
     * {@code (void*, jint, void*, void*) → jint}.
     *
     * <p>
     * <b>Note on GraalVM scalarisation:</b> GraalVM sometimes expands a {@code MemorySegment}
     * context field into two {@code void*} leaf params (base + offset). When this happens, the
     * 4-param entry {@code (void*, jint, void*, void*)} becomes the 5-param entry
     * {@code (void*, void*, jint, void*, void*)}. Both forms must be registered; the test only
     * checks for the 4-param "standard" form — the 5-param expanded form is added manually and
     * documented with a comment in the JSON.
     */
    static List<FunctionDescriptor> allDowncallDescriptors()
    {
        return List.of(
                // create_context
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS),
                // free_context, close_synth
                DESC_VOID_PTR,
                // add_rom_file
                DESC_PTR_STR,
                // set_stereo_output_samplerate
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE),
                // open_synth, get_internal_rendered_sample_count, get_part_states
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                // set_master_volume_override (void*, jbyte → jint in ABI)
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE),
                // set_midi_event_queue_size
                DESC_PTR_INT,
                // play_msg_at
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                // play_sysex_at
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                // render_bit16s
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT),
                // has_active_partials (JAVA_BYTE return → same leaf type as JAVA_INT → covered by
                // (void*)→jint above)
                FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS),
                // get_playing_notes (JAVA_BYTE param → "jint" in metadata after ABI widening)
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                // get_rom_info (void context, void* rom_info_out)
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    // FFM Method Handles
    private final MethodHandle mt32emu_create_context;
    private final MethodHandle mt32emu_free_context;
    private final MethodHandle mt32emu_add_rom_file;
    private final MethodHandle mt32emu_set_stereo_output_samplerate;
    private final MethodHandle mt32emu_set_master_volume_override;
    private final MethodHandle mt32emu_open_synth;
    private final MethodHandle mt32emu_close_synth;
    // Resize Munt's internal MIDI event queue. Must be called after create_context,
    // before open_synth. Queue size must be a power of 2. Default is 1024, which
    // is too small for our panic() (2112 messages) — channels 8–15 note-offs are
    // silently dropped (MT32EMU_RC_QUEUE_FULL = -6) leaving stuck voices.
    private final MethodHandle mt32emu_set_midi_event_queue_size;
    // Thread-safe timestamped API (mt32emu_play_msg_at / mt32emu_play_sysex_at).
    // These enqueue into Munt's internal MidiEventQueue. The render thread's
    // mt32emu_render_bit16s drains that queue at the correct sample positions.
    private final MethodHandle mt32emu_play_msg_at;
    private final MethodHandle mt32emu_play_sysex_at;
    private final MethodHandle mt32emu_get_internal_rendered_sample_count;
    private final MethodHandle mt32emu_render_bit16s;

    // --- ROM Info ---
    private final MethodHandle mt32emu_get_rom_info;

    // --- Diagnostic / State Polling ---
    private final MethodHandle mt32emu_has_active_partials;
    private final MethodHandle mt32emu_get_part_states;
    private final MethodHandle mt32emu_get_playing_notes;

    public FFMMuntNativeBridge() throws Exception
    {
        this(Arena.ofShared());
    }

    private FFMMuntNativeBridge(Arena arena) throws Exception
    {
        super(arena, tryLoadLibrary(arena, "munt", "libmt32emu.dylib", "libmt32emu.so",
                "libmt32emu.dll"));

        // mt32emu_context mt32emu_create_context(mt32emu_report_handler_i report_handler, void
        // *instance_data)
        mt32emu_create_context = downcall("mt32emu_create_context", FunctionDescriptor
                .of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // void mt32emu_free_context(mt32emu_context context)
        mt32emu_free_context = downcall("mt32emu_free_context", DESC_VOID_PTR);

        // mt32emu_return_code mt32emu_add_rom_file(mt32emu_context context, const char *filename)
        mt32emu_add_rom_file = downcall("mt32emu_add_rom_file", DESC_PTR_STR);

        // void mt32emu_set_stereo_output_samplerate(mt32emu_context context, const double
        // samplerate)
        mt32emu_set_stereo_output_samplerate = downcall("mt32emu_set_stereo_output_samplerate",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE));

        // void mt32emu_set_master_volume_override(mt32emu_const_context context, mt32emu_bit8u
        // volume_override) value > 100 disables the override; value <= 100 caps master volume to
        // that value.
        mt32emu_set_master_volume_override = downcall("mt32emu_set_master_volume_override",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

        // mt32emu_return_code mt32emu_open_synth(mt32emu_const_context context)
        mt32emu_open_synth = downcall("mt32emu_open_synth",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // void mt32emu_close_synth(mt32emu_const_context context)
        mt32emu_close_synth = downcall("mt32emu_close_synth", DESC_VOID_PTR);

        // mt32emu_return_code mt32emu_set_midi_event_queue_size(context, queue_size)
        mt32emu_set_midi_event_queue_size = downcall("mt32emu_set_midi_event_queue_size", DESC_PTR_INT);

        // mt32emu_return_code mt32emu_play_msg_at(context, msg, timestamp) — thread-safe
        mt32emu_play_msg_at = downcall("mt32emu_play_msg_at", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // mt32emu_return_code mt32emu_play_sysex_at(context, sysex, len, timestamp) — thread-safe
        mt32emu_play_sysex_at = downcall("mt32emu_play_sysex_at",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // mt32emu_bit32u mt32emu_get_internal_rendered_sample_count(context)
        mt32emu_get_internal_rendered_sample_count = downcall("mt32emu_get_internal_rendered_sample_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // void mt32emu_render_bit16s(mt32emu_const_context context, mt32emu_bit16s *stream,
        // mt32emu_bit32u len)
        mt32emu_render_bit16s = downcall("mt32emu_render_bit16s", FunctionDescriptor
                .ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // void mt32emu_get_rom_info(mt32emu_const_context context, mt32emu_rom_info *rom_info)
        mt32emu_get_rom_info = downcall("mt32emu_get_rom_info",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // mt32emu_boolean mt32emu_has_active_partials(context) — 0=inactive, non-zero=active
        mt32emu_has_active_partials = downcall("mt32emu_has_active_partials",
                FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS));

        // mt32emu_bit32u mt32emu_get_part_states(context) — bitmask: bit N = Part N+1 active
        mt32emu_get_part_states = downcall("mt32emu_get_part_states",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // mt32emu_bit32u mt32emu_get_playing_notes(context, partNumber, keys*, velocities*)
        mt32emu_get_playing_notes = downcall("mt32emu_get_playing_notes",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    // --- Upcall callbacks for the no-op report handler ---

    // Returns MT32EMU_REPORT_HANDLER_VERSION_0 (= 0), telling Munt we only implement v0 callbacks.
    // Signature: mt32emu_report_handler_version getVersionID(mt32emu_report_handler_i handler)
    // where mt32emu_report_handler_i is a pointer-sized union → ADDRESS.
    @SuppressWarnings("unused")
    private static int reportHandlerGetVersionId(MemorySegment handler)
    {
        return 0; // MT32EMU_REPORT_HANDLER_VERSION_0
    }

    // No-op printDebug: suppresses "Rhythm: Attempted to play unmapped key N" and other debug
    // messages that Munt's default handler prints to stdout via vprintf, causing terminal flicker.
    // Signature: void printDebug(void *instance_data, const char *fmt, va_list list)
    // On AArch64/x86-64 C ABI, va_list is passed by pointer → ADDRESS.
    @SuppressWarnings("unused")
    private static void reportHandlerPrintDebug(MemorySegment instanceData, MemorySegment fmt,
            MemorySegment vaList)
    {
    }

    /**
     * Builds a minimal {@code mt32emu_report_handler_i_v0} struct in the given arena with only
     * {@code getVersionID} and {@code printDebug} populated. All other callback slots are NULL so
     * Munt's {@code DelegatingReportHandlerAdapter} falls back to its default (no-op) behaviour for
     * those. The returned segment IS the v0 struct; pass its address as the
     * {@code mt32emu_report_handler_i} union argument to {@code mt32emu_create_context}.
     */
    private MemorySegment buildNoopReportHandler() throws Exception
    {
        var lookup = MethodHandles.lookup();
        var getVersionIdMH = lookup.findStatic(FFMMuntNativeBridge.class,
                "reportHandlerGetVersionId",
                MethodType.methodType(int.class, MemorySegment.class));
        var printDebugMH = lookup.findStatic(FFMMuntNativeBridge.class,
                "reportHandlerPrintDebug",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                        MemorySegment.class));

        // mt32emu_report_handler_i_v0 has 15 function pointer slots (each 8 bytes on 64-bit).
        // We populate only slots 0 (getVersionID) and 1 (printDebug); the rest stay NULL.
        MemorySegment struct = arena.allocate(15 * 8);
        struct.set(ValueLayout.ADDRESS, 0,
                linker.upcallStub(getVersionIdMH,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), arena));
        struct.set(ValueLayout.ADDRESS, 8,
                linker.upcallStub(printDebugMH,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS),
                        arena));
        return struct;
    }

    @Override
    public void createSynth() throws Exception
    {
        try
        {
            MemorySegment reportHandler = buildNoopReportHandler();
            context = (MemorySegment) mt32emu_create_context.invokeExact(reportHandler,
                    MemorySegment.NULL);
            if (context.equals(MemorySegment.NULL))
            {
                throw new Exception("Failed to create Munt context");
            }
            // Increase the MIDI event queue from the default 1024 to 4096.
            // Our panic() sends (4 CC + 128 note-offs) × 16 channels = 2112 messages.
            // With the 1024 default, messages for channels 8–15 overflow silently
            // (MT32EMU_RC_QUEUE_FULL), leaving stuck voices that block new-song notes.
            // Must be called before open_synth. Queue size must be a power of 2.
            int ignored = (int) mt32emu_set_midi_event_queue_size.invokeExact(context, 4096);
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error creating Munt context", t);
        }
    }

    @Override
    public void loadRoms(String romDirectory) throws Exception
    {
        if (context.equals(MemorySegment.NULL))
            return;

        File dir = new File(romDirectory);
        if (!dir.exists() || !dir.isDirectory())
        {
            throw new Exception("Munt ROM directory not found: " + romDirectory);
        }

        File controlRom = new File(dir, "MT32_CONTROL.ROM");
        File pcmRom = new File(dir, "MT32_PCM.ROM");

        if (!controlRom.exists() || !pcmRom.exists())
        {
            // Let's also try lowercase
            controlRom = new File(dir, "mt32_control.rom");
            pcmRom = new File(dir, "mt32_pcm.rom");
            if (!controlRom.exists() || !pcmRom.exists())
            {
                throw new Exception("Missing MT32_CONTROL.ROM or MT32_PCM.ROM in " + romDirectory);
            }
        }

        try
        {
            MemorySegment ctrlPathStr = arena.allocateFrom(controlRom.getAbsolutePath());
            int rc1 = (int) mt32emu_add_rom_file.invokeExact(context, ctrlPathStr);
            if (rc1 <= 0)
                throw new Exception("Munt engine rejected control ROM (return code: "
                        + rc1 + "): " + controlRom.getAbsolutePath());

            MemorySegment pcmPathStr = arena.allocateFrom(pcmRom.getAbsolutePath());
            int rc2 = (int) mt32emu_add_rom_file.invokeExact(context, pcmPathStr);
            if (rc2 <= 0)
                throw new Exception("Munt engine rejected PCM ROM (return code: " + rc2
                        + "): " + pcmRom.getAbsolutePath());
        }
        catch (Exception e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            throw e;
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error invoking Munt ROM API", t);
        }
    }

    /**
     * Returns the control ROM description (e.g. "MT-32 Control v1.07") by reading the
     * {@code mt32emu_rom_info} struct. Must be called after {@link #loadRoms}. The struct is 6
     * consecutive pointer fields (48 bytes on 64-bit); {@code control_rom_description} is at offset
     * 8 (second field).
     */
    @Override
    public @org.jspecify.annotations.Nullable String getRomDescription()
    {
        if (context.equals(MemorySegment.NULL))
            return null;
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment romInfo = temp.allocate(48); // 6 × 8-byte pointers
            mt32emu_get_rom_info.invokeExact(context, romInfo);
            MemorySegment descPtr = romInfo.get(ValueLayout.ADDRESS, 8); // offset 8 = control_rom_description
            if (descPtr.equals(MemorySegment.NULL))
                return null;
            return descPtr.reinterpret(256).getString(0);
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    @Override
    public void openSynth() throws Exception
    {
        if (context.equals(MemorySegment.NULL))
            return;
        try
        {
            // Disable the master volume override: the Extensions struct is heap-zero-initialized,
            // leaving masterVolumeOverride=0 which silences output. 0xFF (>100) disables the
            // override.
            mt32emu_set_master_volume_override.invokeExact(context, (byte) 0xFF);

            // Set the sample rate to match miniaudio (32000 Hz)
            mt32emu_set_stereo_output_samplerate.invokeExact(context, 32000.0);

            int rc = (int) mt32emu_open_synth.invokeExact(context);
            if (rc != 0)
                throw new Exception("Failed to open Munt synth (Check if ROMs are valid)");

            // Reset timing reference so computeTimestamp() uses a fresh base.
            // Without this, the reference was set at construction time; any time spent
            // loading ROMs and opening the synth would make the first events appear far
            // in the future, causing seconds of initial silence.
            lastRenderedSampleCount = 0;
            lastRenderCompletedNanos = System.nanoTime();
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error opening Munt synth", t);
        }
    }

    @Override
    public void resetRenderTiming()
    {
        lastRenderedSampleCount = 0;
        lastRenderCompletedNanos = System.nanoTime();
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
    private int computeTimestamp()
    {
        long elapsedNanos = System.nanoTime() - lastRenderCompletedNanos;
        int elapsedSamples = (int) (elapsedNanos * 32000L / 1_000_000_000L);
        // Cap to ring buffer capacity (4096 frames = 128ms at 32 kHz).
        // Prevents a stale timing reference from scheduling events arbitrarily far
        // in the future (e.g. before resetRenderTiming() has been called).
        elapsedSamples = Math.min(elapsedSamples, 4096);
        return lastRenderedSampleCount + elapsedSamples;
    }

    private void playMsg(int packed)
    {
        if (context.equals(MemorySegment.NULL))
            return;
        try
        {
            int ignored2 = (int) mt32emu_play_msg_at.invokeExact(context, packed, computeTimestamp());
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void playNoteOn(int channel, int key, int velocity)
    {
        playMsg(0x90 | channel | (key << 8) | (velocity << 16));
    }

    @Override
    public void playNoteOff(int channel, int key)
    {
        playMsg(0x80 | channel | (key << 8));
    }

    @Override
    public void playControlChange(int channel, int number, int value)
    {
        playMsg(0xB0 | channel | (number << 8) | (value << 16));
    }

    @Override
    public void playProgramChange(int channel, int program)
    {
        playMsg(0xC0 | channel | (program << 8));
    }

    @Override
    public void playPitchBend(int channel, int value)
    {
        int lsb = value & 0x7F;
        int msb = (value >> 7) & 0x7F;
        playMsg(0xE0 | channel | (lsb << 8) | (msb << 16));
    }

    @Override
    public void playSysex(byte[] sysexData)
    {
        if (context.equals(MemorySegment.NULL) || sysexData == null || sysexData.length == 0)
            return;
        int timestamp = computeTimestamp();
        // Use a confined arena so native memory is freed immediately after the call.
        // mt32emu_play_sysex_at copies the data into its internal queue before returning.
        try (Arena tempArena = Arena.ofConfined())
        {
            MemorySegment seg = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, sysexData);
            int ignored2 = (int) mt32emu_play_sysex_at.invokeExact(context, seg, sysexData.length,
                    timestamp);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
    }

    @Override
    public void reopenSynth() throws Exception
    {
        if (context.equals(MemorySegment.NULL))
            return;
        // Close the synth — immediately terminates all active voices without emulating
        // the MT-32's ~2-second hardware ROM initialization sequence.
        try
        {
            mt32emu_close_synth.invokeExact(context);
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error closing Munt synth", t);
        }
        // Re-open exactly as in openSynth(): disable the masterVolumeOverride.
        try
        {
            mt32emu_set_master_volume_override.invokeExact(context, (byte) 0xFF);
            int rc = (int) mt32emu_open_synth.invokeExact(context);
            if (rc != 0)
                throw new Exception("Failed to reopen Munt synth (rc=" + rc + ")");
        }
        catch (Exception e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            throw e;
        }
        catch (Throwable t)
        {
            err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error reopening Munt synth", t);
        }
        // After open_synth the internal rendered-sample counter restarts at 0.
        // Reset our timing reference so computeTimestamp() produces near-zero values
        // that match the fresh counter, preventing new-song events from being scheduled
        // far in the future.
        lastRenderedSampleCount = 0;
        lastRenderCompletedNanos = System.nanoTime();
    }

    // Cached buffer for audio rendering to avoid GC spikes
    private MemorySegment renderBuffer = MemorySegment.NULL;
    private int currentRenderBufferSize = 0;

    // Called exclusively from the render thread. Munt's internal MidiEventQueue
    // (populated by the playback thread via mt32emu_play_msg_at / mt32emu_play_sysex_at)
    // is drained automatically during mt32emu_render_bit16s at the correct sample
    // positions. No Java-side queue drain is needed here.
    @Override
    public void renderAudio(short[] buffer, int frames)
    {
        if (context.equals(MemorySegment.NULL) || buffer == null || buffer.length == 0)
            return;

        // Ensure the native render buffer is large enough.
        int requiredBytes = buffer.length * 2; // 2 bytes per short
        if (currentRenderBufferSize < requiredBytes)
        {
            try
            {
                renderBuffer = arena.allocate(requiredBytes);
                currentRenderBufferSize = requiredBytes;
            }
            catch (Throwable e)
            {
                err.println("[NativeBridge Error] " + e.getMessage());
                return; // Cannot allocate render buffer; skip this cycle
            }
        }

        try
        {
            mt32emu_render_bit16s.invokeExact(context, renderBuffer, frames);
            MemorySegment.copy(renderBuffer, ValueLayout.JAVA_SHORT, 0, buffer, 0, buffer.length);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }

        // Update the timing reference AFTER rendering so computeTimestamp() in the
        // playback thread produces timestamps relative to the just-completed render.
        try
        {
            lastRenderedSampleCount = (int) mt32emu_get_internal_rendered_sample_count.invokeExact(context);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
        }
        lastRenderCompletedNanos = System.nanoTime();
    }

    @Override
    public boolean hasActivePartials()
    {
        if (context.equals(MemorySegment.NULL))
            return false;
        try
        {
            return (byte) mt32emu_has_active_partials.invokeExact(context) != 0;
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            return false;
        }
    }

    @Override
    public int getPartStates()
    {
        if (context.equals(MemorySegment.NULL))
            return 0;
        try
        {
            return (int) mt32emu_get_part_states.invokeExact(context);
        }
        catch (Throwable e)
        {
            err.println("[NativeBridge Error] " + e.getMessage());
            return 0;
        }
    }

    @Override
    public int getPlayingNotes(int partNumber, byte[] keys, byte[] velocities)
    {
        if (context.equals(MemorySegment.NULL))
            return 0;
        int capacity = (keys != null ? keys.length : 4);
        try (Arena temp = Arena.ofConfined())
        {
            MemorySegment keySeg = temp.allocate(capacity);
            MemorySegment velSeg = temp.allocate(capacity);
            int count = (int) mt32emu_get_playing_notes.invokeExact(context, (byte) partNumber,
                    keySeg, velSeg);
            int toCopy = Math.min(count, capacity);
            if (keys != null)
            {
                for (int i = 0; i < toCopy; i++)
                    keys[i] = keySeg.get(ValueLayout.JAVA_BYTE, i);
            }
            if (velocities != null)
            {
                for (int i = 0; i < toCopy; i++)
                    velocities[i] = velSeg.get(ValueLayout.JAVA_BYTE, i);
            }
            return count;
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
        if (!context.equals(MemorySegment.NULL))
        {
            try
            {
                mt32emu_close_synth.invokeExact(context);
                mt32emu_free_context.invokeExact(context);
            }
            catch (Throwable e)
            {
                err.println("[NativeBridge Error] " + e.getMessage());
            }
            context = MemorySegment.NULL;
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
