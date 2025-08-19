/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi.os;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * FFM API (Project Panama) based CoreMIDI provider for macOS.
 * Replaces the legacy JNA implementation for zero-dependency native calls.
 */
public class MacProvider implements MidiOutProvider
{
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CF_LOOKUP = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());
    private static final SymbolLookup MIDI_LOOKUP = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreMIDI.framework/CoreMIDI", Arena.global());

    // --- CoreFoundation MethodHandles ---
    private static final MethodHandle CFStringCreateWithCString = LINKER.downcallHandle(
            CF_LOOKUP.find("CFStringCreateWithCString").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    private static final MethodHandle CFStringGetCString = LINKER.downcallHandle(
            CF_LOOKUP.find("CFStringGetCString").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    );
    private static final MethodHandle CFRelease = LINKER.downcallHandle(
            CF_LOOKUP.find("CFRelease").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );

    // --- CoreMIDI MethodHandles ---
    private static final MethodHandle MIDIGetNumberOfDestinations = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDIGetNumberOfDestinations").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT) // ItemCount
    );
    private static final MethodHandle MIDIGetDestination = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDIGetDestination").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG) // ItemCount passed as long to be safe (uint32/unsigned long)
    );
    private static final MethodHandle MIDIObjectGetStringProperty = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDIObjectGetStringProperty").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle MIDIClientCreate = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDIClientCreate").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle MIDIOutputPortCreate = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDIOutputPortCreate").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle MIDISend = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDISend").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle MIDIPacketListInit = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDIPacketListInit").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );
    private static final MethodHandle MIDIPacketListAdd = LINKER.downcallHandle(
            MIDI_LOOKUP.find("MIDIPacketListAdd").orElseThrow(),
            // pktlist, listSize, curPacket, time (UInt64), nData, data
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );

    private static final int kCFStringEncodingUTF8 = 0x08000100;

    @Nullable private MemorySegment clientName = null;
    @Nullable private MemorySegment portName = null;
    @SuppressWarnings("UnusedVariable")
    @Nullable private MemorySegment client = null;
    @Nullable private MemorySegment outPort = null;
    @Nullable private MemorySegment destination = null;
    @Nullable private Arena sessionArena = null;
    @Nullable private MemorySegment pktListMem = null;

    private MemorySegment createCFString(Arena arena, String str) throws Throwable
    {
        MemorySegment cStr = arena.allocateFrom(str);
        return (MemorySegment) CFStringCreateWithCString.invokeExact(MemorySegment.NULL, cStr, kCFStringEncodingUTF8);
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
                
                int status = (int) MIDIObjectGetStringProperty.invokeExact(dest, kMIDIPropertyName, strRef);
                if (status == 0)
                {
                    MemorySegment cfString = strRef.get(ValueLayout.ADDRESS, 0);
                    MemorySegment buffer = arena.allocate(256, 1);
                    
                    int getResult = (int) CFStringGetCString.invokeExact(cfString, buffer, 256, kCFStringEncodingUTF8);
                    if (getResult != 0)
                    {
                        String name = buffer.getString(0, java.nio.charset.StandardCharsets.UTF_8).trim();
                        ports.add(new MidiPort(i, name));
                    }
                    CFRelease.invokeExact(cfString);
                }
            }
            CFRelease.invokeExact(kMIDIPropertyName);
        }
        catch (Throwable e)
        {
            // FFM API throws Throwable, handle gracefully
            System.err.println("Error enumerating Mac MIDI ports: " + e.getMessage());
        }
        return ports;
    }

    @Override
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

            MIDIClientCreate.invokeExact(clientName, MemorySegment.NULL, MemorySegment.NULL, clientRef);
            client = clientRef.get(ValueLayout.ADDRESS, 0);
            
            MIDIOutputPortCreate.invokeExact(client, portName, portRef);
            outPort = portRef.get(ValueLayout.ADDRESS, 0);
            
            pktListMem = sessionArena.allocate(512, 1); // Buffer for MIDI packets
        }
        catch (Throwable t)
        {
            throw new Exception("Failed to open Mac MIDI port via FFM", t);
        }
    }

    @Override
    public void sendMessage(byte[] data) throws Exception
    {
        var localOutPort = outPort;
        var localDest = destination;
        var localPktListMem = pktListMem;
        var arena = sessionArena;
        
        if (localOutPort == null || localDest == null || localPktListMem == null || arena == null) return;
        
        try
        {
            MemorySegment curPkt = (MemorySegment) MIDIPacketListInit.invokeExact(localPktListMem);
            MemorySegment dataSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            
            CoreMIDI_MIDIPacketListAdd(localPktListMem, 512, curPkt, 0, data.length, dataSeg);
            MIDISend.invokeExact(localOutPort, localDest, localPktListMem);
        }
        catch (Throwable t)
        {
            throw new Exception("Error sending MIDI message", t);
        }
    }
    
    private void CoreMIDI_MIDIPacketListAdd(MemorySegment pktList, long listSize, MemorySegment curPkt, long time, long nData, MemorySegment data) throws Throwable {
        MIDIPacketListAdd.invokeExact(pktList, listSize, curPkt, time, nData, data);
    }

    @Override
    public void closePort()
    {
        try
        {
            if (clientName != null) CFRelease.invokeExact(clientName);
            if (portName != null) CFRelease.invokeExact(portName);
        }
        catch (Throwable _)
        {
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
