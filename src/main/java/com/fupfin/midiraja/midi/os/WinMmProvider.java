/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.os;


import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * FFM API (Project Panama) based WinMM provider for Windows. Replaces the legacy JNA implementation
 * for zero-dependency native calls.
 */
public class WinMmProvider implements MidiOutProvider
{
    private static final java.util.logging.Logger log =
            java.util.logging.Logger.getLogger(WinMmProvider.class.getName());
    private static final Linker LINKER = Linker.nativeLinker();
    // In Windows, WinMM is usually globally available or found via standard
    // lookup.
    private static final SymbolLookup WINMM_LOOKUP =
            SymbolLookup.libraryLookup("winmm", Arena.global());

    private static final MethodHandle midiOutGetNumDevs =
            LINKER.downcallHandle(WINMM_LOOKUP.find("midiOutGetNumDevs").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

    private static final MethodHandle midiOutGetDevCapsA =
            LINKER.downcallHandle(WINMM_LOOKUP.find("midiOutGetDevCapsA").orElseThrow(),
                    // uDeviceID (UINT_PTR), lpMidiOutCaps (ADDRESS), cbMidiOutCaps (UINT)
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle midiOutOpen =
            LINKER.downcallHandle(WINMM_LOOKUP.find("midiOutOpen").orElseThrow(),
                    // lphmo (ADDRESS), uDeviceID (UINT), dwCallback (ADDRESS), dwInstance
                    // (ADDRESS), fdwOpen (DWORD)
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));

    private static final MethodHandle midiOutShortMsg = LINKER.downcallHandle(
            WINMM_LOOKUP.find("midiOutShortMsg").orElseThrow(),
            // hmo (HANDLE), dwMsg (DWORD)
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle midiOutClose =
            LINKER.downcallHandle(WINMM_LOOKUP.find("midiOutClose").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // MIDIOUTCAPS structure layout (Total 52 bytes)
    private static final StructLayout MIDIOUTCAPS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("wMid"), ValueLayout.JAVA_SHORT.withName("wPid"),
            ValueLayout.JAVA_INT.withName("vDriverVersion"),
            MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_BYTE).withName("szPname"),
            ValueLayout.JAVA_SHORT.withName("wTechnology"),
            ValueLayout.JAVA_SHORT.withName("wVoices"), ValueLayout.JAVA_SHORT.withName("wNotes"),
            ValueLayout.JAVA_SHORT.withName("wChannelMask"),
            ValueLayout.JAVA_INT.withName("dwSupport"));

    @Nullable
    private MemorySegment handle = null;
    @Nullable
    private Arena sessionArena = null;

    @Override
    public List<MidiPort> getOutputPorts()
    {
        List<MidiPort> ports = new ArrayList<>();
        try (Arena arena = Arena.ofConfined())
        {
            int devs = (int) midiOutGetNumDevs.invokeExact();
            for (int i = 0; i < devs; i++)
            {
                MemorySegment caps = arena.allocate(MIDIOUTCAPS_LAYOUT);
                int status = (int) midiOutGetDevCapsA.invokeExact((long) i, caps,
                        (int) MIDIOUTCAPS_LAYOUT.byteSize());
                if (status == 0)
                {
                    // szPname is at offset 8 (short 2 + short 2 + int 4)
                    MemorySegment nameSegment = caps.asSlice(8, 32);
                    String name = nameSegment.getString(0).trim();
                    ports.add(new MidiPort(i, name));
                }
            }
        }
        catch (Throwable e)
        {
            log.warning("NativeBridge error: " + e.getMessage());
            log.warning("Error enumerating Windows MIDI ports: " + e.getMessage());
        }
        return ports;
    }

    @Override
    public void openPort(int portIndex) throws Exception
    {
        try
        {
            sessionArena = Arena.ofShared();
            MemorySegment hRef = sessionArena.allocate(ValueLayout.ADDRESS);
            int status = (int) midiOutOpen.invokeExact(hRef, portIndex, MemorySegment.NULL,
                    MemorySegment.NULL, 0);
            if (status != 0)
            {
                throw new Exception("Failed to open Windows MIDI port " + portIndex + " (Status: "
                        + status + ")");
            }
            handle = hRef.get(ValueLayout.ADDRESS, 0);
        }
        catch (Throwable t)
        {
            log.warning("NativeBridge error: " + t.getMessage());
            if (sessionArena != null) sessionArena.close();
            throw new Exception("Failed to open Windows MIDI port via FFM", t);
        }
    }

    @Override
    @SuppressWarnings("UnusedVariable")
    public void sendMessage(byte[] data) throws Exception
    {
        var localHandle = handle;
        if (localHandle == null || data == null || data.length == 0) return;

        if (data.length <= 3)
        {
            int msg = 0;
            for (int i = 0; i < data.length; i++)
            {
                msg |= ((data[i] & 0xFF) << (8 * i));
            }
            try
            {
                int status = (int) midiOutShortMsg.invokeExact(localHandle, msg);
                if (status != 0)
                { /* ignore */
                }
            }
            catch (Throwable t)
            {
                log.warning("NativeBridge error: " + t.getMessage());
                throw new Exception("Error sending MIDI message to WinMM", t);
            }
        }
    }

    @Override
    @SuppressWarnings("UnusedVariable")
    public void closePort()
    {
        try
        {
            if (handle != null)
            {
                int _dummy = (int) midiOutClose.invokeExact(handle);
            }
        }
        catch (Throwable e)
        {
            log.warning("NativeBridge error: " + e.getMessage());
        }
        finally
        {
            handle = null;
            if (sessionArena != null)
            {
                sessionArena.close();
                sessionArena = null;
            }
        }
    }
}
