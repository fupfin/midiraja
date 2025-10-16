/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;

public class FluidSynthProvider implements SoftSynthProvider {
    
    private final Arena arena;
    private final @Nullable String explicitDriver;
    
    // FFM Method Handles
    private final @Nullable MethodHandle new_fluid_settings;
    private final @Nullable MethodHandle fluid_settings_setstr;
    private final @Nullable MethodHandle new_fluid_synth;
    private final @Nullable MethodHandle fluid_synth_sfload;
    private final @Nullable MethodHandle new_fluid_audio_driver;
    private final @Nullable MethodHandle fluid_synth_noteon_mh;
    private final @Nullable MethodHandle fluid_synth_noteoff_mh;
    private final @Nullable MethodHandle fluid_synth_cc_mh;
    private final @Nullable MethodHandle fluid_synth_program_change_mh;
    private final @Nullable MethodHandle fluid_synth_pitch_bend_mh;
    private final @Nullable MethodHandle fluid_synth_sysex_mh;
    private final @Nullable MethodHandle delete_fluid_audio_driver;
    private final @Nullable MethodHandle delete_fluid_synth;
    private final @Nullable MethodHandle delete_fluid_settings;

    // FluidSynth Pointers
    private MemorySegment settings = MemorySegment.NULL;
    private MemorySegment synth = MemorySegment.NULL;
    private MemorySegment adriver = MemorySegment.NULL;

    public FluidSynthProvider(@Nullable String explicitDriver) throws Exception {
        this.arena = Arena.ofShared();
        this.explicitDriver = explicitDriver;
        
        // Mock branch for tests
        if ("MOCK_LIBRARY".equals(explicitDriver)) {
            new_fluid_settings = null;
            fluid_settings_setstr = null;
            new_fluid_synth = null;
            fluid_synth_sfload = null;
            new_fluid_audio_driver = null;
            fluid_synth_noteon_mh = null;
            fluid_synth_noteoff_mh = null;
            fluid_synth_cc_mh = null;
            fluid_synth_program_change_mh = null;
            fluid_synth_pitch_bend_mh = null;
            fluid_synth_sysex_mh = null;
            delete_fluid_audio_driver = null;
            delete_fluid_synth = null;
            delete_fluid_settings = null;
            return;
        }

        Linker linker = Linker.nativeLinker();
        SymbolLookup lib;
        
        try {
            // Try default system lookup first
            String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("mac")) {
                lib = tryLoadLibrary(arena, "libfluidsynth.dylib", "/opt/homebrew/lib/libfluidsynth.dylib", "/usr/local/lib/libfluidsynth.dylib");
            } else if (os.contains("win")) {
                lib = tryLoadLibrary(arena, "libfluidsynth.dll");
            } else {
                lib = tryLoadLibrary(arena, "libfluidsynth.so", "libfluidsynth.so.3", "/usr/lib/x86_64-linux-gnu/libfluidsynth.so.3");
            }
        } catch (IllegalArgumentException e) {
            throw new Exception("FluidSynth native library not found! Please install it (e.g., 'brew install fluidsynth' on Mac). " + e.getMessage(), e);
        }

        // --- Bindings ---

        // fluid_settings_t* new_fluid_settings(void)
        new_fluid_settings = linker.downcallHandle(
            lib.find("new_fluid_settings").orElseThrow(() -> new Exception("new_fluid_settings not found")),
            FunctionDescriptor.of(ValueLayout.ADDRESS)
        );

        // int fluid_settings_setstr(fluid_settings_t* settings, const char* name, const char* str)
        fluid_settings_setstr = linker.downcallHandle(
            lib.find("fluid_settings_setstr").orElseThrow(() -> new Exception("fluid_settings_setstr not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // fluid_synth_t* new_fluid_synth(fluid_settings_t* settings)
        new_fluid_synth = linker.downcallHandle(
            lib.find("new_fluid_synth").orElseThrow(() -> new Exception("new_fluid_synth not found")),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // int fluid_synth_sfload(fluid_synth_t* synth, const char* filename, int reset_presets)
        fluid_synth_sfload = linker.downcallHandle(
            lib.find("fluid_synth_sfload").orElseThrow(() -> new Exception("fluid_synth_sfload not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        // fluid_audio_driver_t* new_fluid_audio_driver(fluid_settings_t* settings, fluid_synth_t* synth)
        new_fluid_audio_driver = linker.downcallHandle(
            lib.find("new_fluid_audio_driver").orElseThrow(() -> new Exception("new_fluid_audio_driver not found")),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // int fluid_synth_noteon(fluid_synth_t* synth, int chan, int key, int vel)
        fluid_synth_noteon_mh = linker.downcallHandle(
            lib.find("fluid_synth_noteon").orElseThrow(() -> new Exception("fluid_synth_noteon not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // int fluid_synth_noteoff(fluid_synth_t* synth, int chan, int key)
        fluid_synth_noteoff_mh = linker.downcallHandle(
            lib.find("fluid_synth_noteoff").orElseThrow(() -> new Exception("fluid_synth_noteoff not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // int fluid_synth_cc(fluid_synth_t* synth, int chan, int num, int val)
        fluid_synth_cc_mh = linker.downcallHandle(
            lib.find("fluid_synth_cc").orElseThrow(() -> new Exception("fluid_synth_cc not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // int fluid_synth_program_change(fluid_synth_t* synth, int chan, int program)
        fluid_synth_program_change_mh = linker.downcallHandle(
            lib.find("fluid_synth_program_change").orElseThrow(() -> new Exception("fluid_synth_program_change not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // int fluid_synth_pitch_bend(fluid_synth_t* synth, int chan, int val)
        fluid_synth_pitch_bend_mh = linker.downcallHandle(
            lib.find("fluid_synth_pitch_bend").orElseThrow(() -> new Exception("fluid_synth_pitch_bend not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        );

        // int fluid_synth_sysex(fluid_synth_t* synth, const char* data, int len, char* response, int* response_len, int* handled, int dryrun)
        fluid_synth_sysex_mh = linker.downcallHandle(
            lib.find("fluid_synth_sysex").orElseThrow(() -> new Exception("fluid_synth_sysex not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        // void delete_fluid_audio_driver(fluid_audio_driver_t* driver)
        delete_fluid_audio_driver = linker.downcallHandle(
            lib.find("delete_fluid_audio_driver").orElseThrow(() -> new Exception("delete_fluid_audio_driver not found")),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // void delete_fluid_synth(fluid_synth_t* synth)
        delete_fluid_synth = linker.downcallHandle(
            lib.find("delete_fluid_synth").orElseThrow(() -> new Exception("delete_fluid_synth not found")),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // void delete_fluid_settings(fluid_settings_t* settings)
        delete_fluid_settings = linker.downcallHandle(
            lib.find("delete_fluid_settings").orElseThrow(() -> new Exception("delete_fluid_settings not found")),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
    }

    private SymbolLookup tryLoadLibrary(Arena arena, String... paths) {
        IllegalArgumentException lastException = null;
        for (String path : paths) {
            try {
                if (path.startsWith("/")) {
                    java.io.File f = new java.io.File(path);
                    if (f.exists()) {
                        return SymbolLookup.libraryLookup(f.toPath(), arena);
                    }
                } else {
                    return SymbolLookup.libraryLookup(path, arena);
                }
            } catch (IllegalArgumentException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IllegalArgumentException("Library not found in specified paths.");
    }

    @Override
    public List<MidiPort> getOutputPorts() {
        return List.of(new MidiPort(0, "FluidSynth (Embedded)"));
    }

    @Override
    @SuppressWarnings({"EmptyCatch", "UnusedVariable"})
    public void openPort(int portIndex) throws Exception {
        if ("MOCK_LIBRARY".equals(explicitDriver)) return;
        
        try {
            if (new_fluid_settings != null) {
                settings = (MemorySegment) new_fluid_settings.invokeExact();
            }
            
            if (explicitDriver != null && !explicitDriver.isEmpty() && fluid_settings_setstr != null) {
                MemorySegment keyStr = arena.allocateFrom("audio.driver");
                MemorySegment valStr = arena.allocateFrom(explicitDriver);
                int _dummy = (int) fluid_settings_setstr.invokeExact(settings, keyStr, valStr);
            }

            if (new_fluid_synth != null) {
                synth = (MemorySegment) new_fluid_synth.invokeExact(settings);
            }
            if (new_fluid_audio_driver != null) {
                adriver = (MemorySegment) new_fluid_audio_driver.invokeExact(settings, synth);
            }
            
            if (adriver == null || adriver.equals(MemorySegment.NULL)) {
                throw new Exception("Failed to initialize FluidSynth audio driver.");
            }
        } catch (Throwable t) {
            throw new Exception("Failed to open FluidSynth engine via FFM: " + t.getMessage(), t);
        }
    }

    @Override
    public void loadSoundbank(String path) throws Exception {
        if ("MOCK_LIBRARY".equals(explicitDriver)) return;
        if (synth == null || synth.equals(MemorySegment.NULL)) {
            throw new Exception("FluidSynth is not open yet.");
        }
        
        try {
            if (fluid_synth_sfload != null) {
                MemorySegment pathStr = arena.allocateFrom(path);
                int sfId = (int) fluid_synth_sfload.invokeExact(synth, pathStr, 1);
                if (sfId < 0) {
                    throw new Exception("Failed to load soundfont: " + path);
                }
            }
        } catch (Throwable t) {
            throw new Exception("Failed to load soundbank via FFM: " + t.getMessage(), t);
        }
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (data == null || data.length == 0) return;

        int status = data[0] & 0xFF;
        
        if (status >= 0xF0) {
            fluid_synth_sysex(data);
            return;
        }

        int command = status & 0xF0;
        int channel = status & 0x0F;

        if (data.length >= 2) {
            int data1 = data[1] & 0xFF;
            int data2 = (data.length >= 3) ? (data[2] & 0xFF) : 0;

            switch (command) {
                case 0x90: // Note On
                    fluid_synth_noteon(channel, data1, data2);
                    break;
                case 0x80: // Note Off
                    fluid_synth_noteoff(channel, data1);
                    break;
                case 0xB0: // Control Change
                    fluid_synth_cc(channel, data1, data2);
                    break;
                case 0xC0: // Program Change
                    fluid_synth_program_change(channel, data1);
                    break;
                case 0xE0: // Pitch Bend
                    int bend = (data2 << 7) | data1;
                    fluid_synth_pitch_bend(channel, bend);
                    break;
            }
        }
    }

    @SuppressWarnings({"EmptyCatch", "UnusedVariable"})

    protected void fluid_synth_noteon(int channel, int key, int velocity) {
        if (synth == null || fluid_synth_noteon_mh == null) return;
        try {
            int _dummy = (int) fluid_synth_noteon_mh.invokeExact(synth, channel, key, velocity);
        } catch (Throwable ignored) {}
    }

    protected void fluid_synth_noteoff(int channel, int key) {
        if (synth == null || fluid_synth_noteoff_mh == null) return;
        try {
            int _dummy = (int) fluid_synth_noteoff_mh.invokeExact(synth, channel, key);
        } catch (Throwable ignored) {}
    }

    protected void fluid_synth_cc(int channel, int num, int val) {
        if (synth == null || fluid_synth_cc_mh == null) return;
        try {
            int _dummy = (int) fluid_synth_cc_mh.invokeExact(synth, channel, num, val);
        } catch (Throwable ignored) {}
    }

    protected void fluid_synth_program_change(int channel, int program) {
        if (synth == null || fluid_synth_program_change_mh == null) return;
        try {
            int _dummy = (int) fluid_synth_program_change_mh.invokeExact(synth, channel, program);
        } catch (Throwable ignored) {}
    }

    protected void fluid_synth_pitch_bend(int channel, int val) {
        if (synth == null || fluid_synth_pitch_bend_mh == null) return;
        try {
            int _dummy = (int) fluid_synth_pitch_bend_mh.invokeExact(synth, channel, val);
        } catch (Throwable ignored) {}
    }

    protected void fluid_synth_sysex(byte[] data) {
        if (synth == null || fluid_synth_sysex_mh == null) return;
        try {
            MemorySegment dataSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            // int fluid_synth_sysex(fluid_synth_t* synth, const char* data, int len, char* response, int* response_len, int* handled, int dryrun)
            int _dummy = (int) fluid_synth_sysex_mh.invokeExact(
                synth, 
                dataSeg, 
                data.length, 
                MemorySegment.NULL, 
                MemorySegment.NULL, 
                MemorySegment.NULL, 
                0
            );
        } catch (Throwable ignored) {}
    }

    @Override
    public void closePort() {
        if ("MOCK_LIBRARY".equals(explicitDriver)) return;

        try {
            if (adriver != null && !adriver.equals(MemorySegment.NULL) && delete_fluid_audio_driver != null) {
                delete_fluid_audio_driver.invokeExact(adriver);
                adriver = MemorySegment.NULL;
            }
            if (synth != null && !synth.equals(MemorySegment.NULL) && delete_fluid_synth != null) {
                delete_fluid_synth.invokeExact(synth);
                synth = MemorySegment.NULL;
            }
            if (settings != null && !settings.equals(MemorySegment.NULL) && delete_fluid_settings != null) {
                delete_fluid_settings.invokeExact(settings);
                settings = MemorySegment.NULL;
            }
        } catch (Throwable t) {
            System.err.println("Error closing FluidSynth via FFM: " + t.getMessage());
        } finally {
            if (arena != null && arena.scope().isAlive()) {
                arena.close();
            }
        }
    }
}