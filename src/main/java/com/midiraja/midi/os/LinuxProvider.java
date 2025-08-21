/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi.os;

import org.jspecify.annotations.Nullable;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.System.err;

/**
 * FFM API (Project Panama) based ALSA Sequencer provider for Linux.
 * Replaces the legacy JNA implementation.
 */
public class LinuxProvider implements MidiOutProvider
{
    private static final Linker LINKER = Linker.nativeLinker();
    @Nullable private static final SymbolLookup ALSA_LOOKUP;
    private static final boolean ALSA_AVAILABLE;

    static
    {
        boolean available = false;
        SymbolLookup lookup = null;
        try
        {
            lookup = SymbolLookup.libraryLookup("libasound.so.2", Arena.global());
            available = true;
        }
        catch (Throwable t)
        {
            // libasound.so.2 not found, probably not on a Linux desktop with ALSA
        }
        ALSA_LOOKUP = lookup;
        ALSA_AVAILABLE = available;
    }

    // ALSA Constants
    private static final int SND_SEQ_OPEN_OUTPUT = 2;
    private static final int SND_SEQ_PORT_CAP_WRITE = (1 << 1);
    private static final int SND_SEQ_PORT_CAP_SUBS_WRITE = (1 << 6);
    private static final int PORT_CAP_MASK = SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE;
    private static final int SND_SEQ_CLIENT_SYSTEM = 0;

    @Nullable private static MethodHandle getMH(String name, FunctionDescriptor desc)
    {
        if (!ALSA_AVAILABLE) return null;
        return ALSA_LOOKUP == null ? null : ALSA_LOOKUP.find(name).map(seg -> LINKER.downcallHandle(seg, desc)).orElse(null);
    }

    // Method Handles
    @Nullable private static final MethodHandle snd_seq_open = getMH("snd_seq_open", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    @Nullable private static final MethodHandle snd_seq_close = getMH("snd_seq_close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        @Nullable private static final MethodHandle snd_seq_create_simple_port = getMH("snd_seq_create_simple_port", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    @Nullable private static final MethodHandle snd_seq_connect_to = getMH("snd_seq_connect_to", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    
    // Client Info
    @Nullable private static final MethodHandle snd_seq_client_info_malloc = getMH("snd_seq_client_info_malloc", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_client_info_free = getMH("snd_seq_client_info_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_query_next_client = getMH("snd_seq_query_next_client", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_client_info_get_client = getMH("snd_seq_client_info_get_client", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_client_info_get_name = getMH("snd_seq_client_info_get_name", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    
    // Port Info
    @Nullable private static final MethodHandle snd_seq_port_info_malloc = getMH("snd_seq_port_info_malloc", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_port_info_free = getMH("snd_seq_port_info_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_query_next_port = getMH("snd_seq_query_next_port", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_port_info_set_client = getMH("snd_seq_port_info_set_client", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    @Nullable private static final MethodHandle snd_seq_port_info_set_port = getMH("snd_seq_port_info_set_port", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    @Nullable private static final MethodHandle snd_seq_port_info_get_port = getMH("snd_seq_port_info_get_port", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_port_info_get_capability = getMH("snd_seq_port_info_get_capability", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_port_info_get_name = getMH("snd_seq_port_info_get_name", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // MIDI Event Parser
    @Nullable private static final MethodHandle snd_midi_event_new = getMH("snd_midi_event_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_midi_event_free = getMH("snd_midi_event_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_midi_event_encode = getMH("snd_midi_event_encode", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    @Nullable private static final MethodHandle snd_seq_event_output_direct = getMH("snd_seq_event_output_direct", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    
    // Drain output
    @Nullable private static final MethodHandle snd_seq_drain_output = getMH("snd_seq_drain_output", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    @Nullable private MemorySegment seqHandle = null;
    private int myPort = -1;
    @Nullable private MemorySegment midiEventParser = null;
    @Nullable private MemorySegment eventBuffer = null;
    @Nullable private Arena sessionArena = null;

    @Override
    @SuppressWarnings("NullAway")
    public List<MidiPort> getOutputPorts()
    {
        var ports = new ArrayList<MidiPort>();
        if (!ALSA_AVAILABLE) return ports;

        try (Arena arena = Arena.ofConfined())
        {
            MemorySegment seqRef = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment nameStr = arena.allocateFrom("default");
            
            if ((int) snd_seq_open.invokeExact(seqRef, nameStr, SND_SEQ_OPEN_OUTPUT, 0) < 0)
            {
                err.println("[Midiraja] Warning: ALSA Sequencer could not be opened.");
                return ports;
            }

            MemorySegment seq = seqRef.get(ValueLayout.ADDRESS, 0);
            
            MemorySegment cInfoRef = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment pInfoRef = arena.allocate(ValueLayout.ADDRESS);

            snd_seq_client_info_malloc.invokeExact(cInfoRef);
            snd_seq_port_info_malloc.invokeExact(pInfoRef);

            MemorySegment cInfo = cInfoRef.get(ValueLayout.ADDRESS, 0);
            MemorySegment pInfo = pInfoRef.get(ValueLayout.ADDRESS, 0);

            // Iterate over all clients
            while ((int) snd_seq_query_next_client.invokeExact(seq, cInfo) == 0)
            {
                int client = (int) snd_seq_client_info_get_client.invokeExact(cInfo);
                if (client == SND_SEQ_CLIENT_SYSTEM) continue;

                MemorySegment clientNamePtr = (MemorySegment) snd_seq_client_info_get_name.invokeExact(cInfo);
                String clientName = clientNamePtr.getString(0);

                snd_seq_port_info_set_client.invokeExact(pInfo, client);
                snd_seq_port_info_set_port.invokeExact(pInfo, -1);

                // Iterate over ports
                while ((int) snd_seq_query_next_port.invokeExact(seq, pInfo) == 0)
                {
                    int port = (int) snd_seq_port_info_get_port.invokeExact(pInfo);
                    int caps = (int) snd_seq_port_info_get_capability.invokeExact(pInfo);

                    if ((caps & PORT_CAP_MASK) == PORT_CAP_MASK)
                    {
                        MemorySegment portNamePtr = (MemorySegment) snd_seq_port_info_get_name.invokeExact(pInfo);
                        String portName = portNamePtr.getString(0);
                        int globalPortIndex = (client << 16) | port;
                        ports.add(new MidiPort(globalPortIndex, clientName + " - " + portName));
                    }
                }
            }

            snd_seq_port_info_free.invokeExact(pInfo);
            snd_seq_client_info_free.invokeExact(cInfo);
            snd_seq_close.invokeExact(seq);
        }
        catch (Throwable t)
        {
            err.println("ALSA Query Error: " + t.getMessage());
        }

        return ports;
    }

    @Override
    @SuppressWarnings("NullAway")
    public void openPort(int portIndex) throws Exception
    {
        if (!ALSA_AVAILABLE) throw new Exception("ALSA is not available on this system.");

        try
        {
            sessionArena = Arena.ofShared();
            MemorySegment seqRef = sessionArena.allocate(ValueLayout.ADDRESS);
            MemorySegment nameStr = sessionArena.allocateFrom("default");
            
            if ((int) snd_seq_open.invokeExact(seqRef, nameStr, SND_SEQ_OPEN_OUTPUT, 0) < 0)
            {
                throw new Exception("Failed to open ALSA Sequencer");
            }
            seqHandle = seqRef.get(ValueLayout.ADDRESS, 0);

            MemorySegment portNameStr = sessionArena.allocateFrom("Midiraja Out");
            // SND_SEQ_PORT_CAP_READ | SND_SEQ_PORT_CAP_SUBS_READ (For generic MIDI type 1<<1)
            myPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, portNameStr, (1<<0) | (1<<5), (1<<1));

            int destClient = (portIndex >> 16) & 0xFFFF;
            int destPort = portIndex & 0xFFFF;

            if ((int) snd_seq_connect_to.invokeExact(seqHandle, myPort, destClient, destPort) < 0)
            {
                throw new Exception("ALSA connection failed. Invalid port index.");
            }

            // Init midi event parser
            MemorySegment parserRef = sessionArena.allocate(ValueLayout.ADDRESS);
            snd_midi_event_new.invokeExact(256, parserRef);
            midiEventParser = parserRef.get(ValueLayout.ADDRESS, 0);
            
            // Allocate ALSA event structure (64 bytes is safely larger than snd_seq_event_t)
            eventBuffer = sessionArena.allocate(64, 8); 
        }
        catch (Throwable t)
        {
            if (sessionArena != null) sessionArena.close();
            throw new Exception("Failed to open ALSA port via FFM", t);
        }
    }

    @Override
    @SuppressWarnings("NullAway")
    public void sendMessage(byte[] data) throws Exception
    {
        var handle = seqHandle;
        var parser = midiEventParser;
        var ev = eventBuffer;
        var arena = sessionArena;

        if (handle == null || parser == null || ev == null || arena == null) return;
        if (data.length == 0) return;

        try
        {
            MemorySegment dataSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            
            // Encode raw byte[] into the ALSA snd_seq_event_t structure (ev) using the parser
            long processed = (long) snd_midi_event_encode.invokeExact(parser, dataSeg, (long) data.length, ev);
            
            if (processed > 0)
            {
                // Source port
                ev.set(ValueLayout.JAVA_BYTE, 4, (byte) myPort); // event.source.port offset
                // Dest port
                ev.set(ValueLayout.JAVA_BYTE, 6, (byte) 253); // SND_SEQ_ADDRESS_SUBSCRIBERS offset

                int s1 = (int) snd_seq_event_output_direct.invokeExact(handle, ev);
                int s2 = (int) snd_seq_drain_output.invokeExact(handle);
            }
        }
        catch (Throwable t)
        {
            throw new Exception("Error sending ALSA MIDI message", t);
        }
    }

    @Override
    @SuppressWarnings("NullAway")
    public void closePort()
    {
        try
        {
            if (midiEventParser != null) snd_midi_event_free.invokeExact(midiEventParser);
            if (seqHandle != null) snd_seq_close.invokeExact(seqHandle);
        }
        catch (Throwable _)
        {
            // ignore
        }
        finally
        {
            midiEventParser = null;
            seqHandle = null;
            eventBuffer = null;
            if (sessionArena != null)
            {
                sessionArena.close();
                sessionArena = null;
            }
        }
    }
}
