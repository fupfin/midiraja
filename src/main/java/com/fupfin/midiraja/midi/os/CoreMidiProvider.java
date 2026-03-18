/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.os;

import com.fupfin.midiraja.io.Console;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * FFM API (Project Panama) based CoreMIDI provider for macOS. Replaces the legacy JNA
 * implementation for zero-dependency native calls.
 */
public class CoreMidiProvider implements MidiOutProvider
{
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CF_LOOKUP = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());
    private static final SymbolLookup MIDI_LOOKUP = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreMIDI.framework/CoreMIDI", Arena.global());

    // --- CoreFoundation MethodHandles ---
    private static final MethodHandle CFStringCreateWithCString =
            LINKER.downcallHandle(CF_LOOKUP.find("CFStringCreateWithCString").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle CFStringGetCString =
            LINKER.downcallHandle(CF_LOOKUP.find("CFStringGetCString").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle CFRelease =
            LINKER.downcallHandle(CF_LOOKUP.find("CFRelease").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // --- CoreMIDI MethodHandles ---
    private static final MethodHandle MIDIGetNumberOfDestinations =
            LINKER.downcallHandle(MIDI_LOOKUP.find("MIDIGetNumberOfDestinations").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT) // ItemCount
            );
    private static final MethodHandle MIDIGetDestination =
            LINKER.downcallHandle(MIDI_LOOKUP.find("MIDIGetDestination").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG) // ItemCount
                                                                                      // passed as
                                                                                      // long to be
                                                                                      // safe
                                                                                      // (uint32/unsigned
                                                                                      // long)
            );
    private static final MethodHandle MIDIObjectGetStringProperty =
            LINKER.downcallHandle(MIDI_LOOKUP.find("MIDIObjectGetStringProperty").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MIDIClientCreate =
            LINKER.downcallHandle(MIDI_LOOKUP.find("MIDIClientCreate").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MIDIOutputPortCreate =
            LINKER.downcallHandle(MIDI_LOOKUP.find("MIDIOutputPortCreate").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MIDISend = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDISend").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MIDIPacketListInit =
            LINKER.downcallHandle(MIDI_LOOKUP.find("MIDIPacketListInit").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MIDIPacketListAdd =
            LINKER.downcallHandle(MIDI_LOOKUP.find("MIDIPacketListAdd").orElseThrow(),
                    // pktlist, listSize, curPacket, time (UInt64), nData, data
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final int kCFStringEncodingUTF8 = 0x08000100;

    @Nullable
    private MemorySegment clientName = null;
    @Nullable
    private MemorySegment portName = null;
    @SuppressWarnings("UnusedVariable")
    @Nullable
    private MemorySegment client = null;
    @Nullable
    private MemorySegment outPort = null;
    @Nullable
    private MemorySegment destination = null;
    @Nullable
    private Arena sessionArena = null;
    @Nullable
    private MemorySegment pktListMem = null;

    private MemorySegment createCFString(Arena arena, String str) throws Throwable
    {
        MemorySegment cStr = arena.allocateFrom(str);
        return (MemorySegment) CFStringCreateWithCString.invokeExact(MemorySegment.NULL, cStr,
                kCFStringEncodingUTF8);
    }

    @Override
    public List<MidiPort> getOutputPorts()
    {
        List<MidiPort> ports = new ArrayList<>();
        try (Arena arena = Arena.ofConfined())
        {
            int destCount = (int) MIDIGetNumberOfDestinations.invokeExact();
            MemorySegment kMIDIPropertyName = createCFString(arena, "name");

            for (int i = 0; i < destCount; i++)
            {
                MemorySegment dest = (MemorySegment) MIDIGetDestination.invokeExact((long) i);
                MemorySegment strRef = arena.allocate(ValueLayout.ADDRESS);

                int status = (int) MIDIObjectGetStringProperty.invokeExact(dest, kMIDIPropertyName,
                        strRef);
                if (status == 0)
                {
                    MemorySegment cfString = strRef.get(ValueLayout.ADDRESS, 0);
                    MemorySegment buffer = arena.allocate(256, 1);

                    int getResult = (int) CFStringGetCString.invokeExact(cfString, buffer, 256,
                            kCFStringEncodingUTF8);
                    if (getResult != 0)
                    {
                        String name =
                                buffer.getString(0, StandardCharsets.UTF_8).trim();
                        ports.add(new MidiPort(i, name));
                    }
                    CFRelease.invokeExact(cfString);
                }
            }
            CFRelease.invokeExact(kMIDIPropertyName);
        }
        catch (Throwable e)
        {
            Console.err.println("[NativeBridge Error] " + e.getMessage());
            // FFM API throws Throwable, handle gracefully
            Console.err.println("Error enumerating Mac MIDI ports: " + e.getMessage());
        }
        return ports;
    }

    @Override
    @SuppressWarnings("UnusedVariable")
    public void openPort(int portIndex) throws Exception
    {
        try
        {
            int destCount = (int) MIDIGetNumberOfDestinations.invokeExact();
            if (portIndex < 0 || portIndex >= destCount)
            {
                throw new IllegalArgumentException("Invalid Mac port index.");
            }

            sessionArena = Arena.ofShared();
            destination = (MemorySegment) MIDIGetDestination.invokeExact((long) portIndex);

            clientName = createCFString(sessionArena, "MidrajaClient");
            portName = createCFString(sessionArena, "MidrajaOutPort");

            MemorySegment clientRef = sessionArena.allocate(ValueLayout.ADDRESS);
            MemorySegment portRef = sessionArena.allocate(ValueLayout.ADDRESS);

            int _clientStatus = (int) MIDIClientCreate.invokeExact(clientName, MemorySegment.NULL,
                    MemorySegment.NULL, clientRef);
            if (_clientStatus != 0)
            { /* ignore */
            }
            client = clientRef.get(ValueLayout.ADDRESS, 0);

            int _portStatus = (int) MIDIOutputPortCreate.invokeExact(client, portName, portRef);
            if (_portStatus != 0)
            { /* ignore */
            }
            outPort = portRef.get(ValueLayout.ADDRESS, 0);

            pktListMem = sessionArena.allocate(512, 1); // Buffer for MIDI packets
        }
        catch (Throwable t)
        {
            Console.err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Failed to open Mac MIDI port via FFM", t);
        }
    }

    @Override
    @SuppressWarnings("UnusedVariable")
    public synchronized void sendMessage(byte[] data) throws Exception
    {
        var localOutPort = outPort;
        var localDest = destination;
        var localPktListMem = pktListMem;

        if (localOutPort == null || localDest == null || localPktListMem == null) return;

        try (Arena tempArena = Arena.ofConfined())
        {
            MemorySegment curPkt = (MemorySegment) MIDIPacketListInit.invokeExact(localPktListMem);
            MemorySegment dataSeg = tempArena.allocateFrom(ValueLayout.JAVA_BYTE, data);

            // FFM invokeExact requires exact return type matching
            MemorySegment nextPkt = (MemorySegment) MIDIPacketListAdd.invokeExact(localPktListMem,
                    512L, curPkt, 0L, (long) data.length, dataSeg);

            if (nextPkt != null && !nextPkt.equals(MemorySegment.NULL))
            {
                int status = (int) MIDISend.invokeExact(localOutPort, localDest, localPktListMem);
                if (status != 0)
                { /* ignore */
                }
            }
        }
        catch (Throwable t)
        {
            Console.err.println("[NativeBridge Error] " + t.getMessage());
            throw new Exception("Error sending MIDI message: " + t.getMessage(), t);
        }
    }

    @Override
    public void closePort()
    {
        try
        {
            if (clientName != null) CFRelease.invokeExact(clientName);
            if (portName != null) CFRelease.invokeExact(portName);
        }
        catch (Throwable e)
        {
            Console.err.println("[NativeBridge Error] " + e.getMessage());
            // ignored
        }
        finally
        {
            clientName = null;
            portName = null;
            outPort = null;
            destination = null;
            client = null;
            pktListMem = null;
            if (sessionArena != null)
            {
                sessionArena.close();
                sessionArena = null;
            }
        }
    }
}
