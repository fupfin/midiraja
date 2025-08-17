/*
 * Copyright (c) 2026, Park, Sungchul
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.midiraja.midi.os;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.midiraja.midi.os.linux.AlsaLibrary;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;

import static java.lang.System.err;

public class LinuxProvider implements MidiOutProvider {
    private Pointer seqHandle = null;
    private int myPort = -1;
    private Pointer midiEventParser = null;
    private AlsaLibrary.snd_seq_event_t eventBuffer;

    private static final int PORT_CAP_MASK = AlsaLibrary.SND_SEQ_PORT_CAP_WRITE | AlsaLibrary.SND_SEQ_PORT_CAP_SUBS_WRITE;

    @Override
    public List<MidiPort> getOutputPorts() {
        var ports = new ArrayList<MidiPort>();
        PointerByReference seqRef = new PointerByReference();
        if (AlsaLibrary.INSTANCE.snd_seq_open(seqRef, "default", AlsaLibrary.SND_SEQ_OPEN_OUTPUT, 0) < 0) {
            err.println("[Midiraja] Warning: ALSA Sequencer could not be opened.");
            return ports;
        }

        Pointer seq = seqRef.getValue();
        PointerByReference cInfoRef = new PointerByReference();
        PointerByReference pInfoRef = new PointerByReference();

        AlsaLibrary.INSTANCE.snd_seq_client_info_malloc(cInfoRef);
        AlsaLibrary.INSTANCE.snd_seq_port_info_malloc(pInfoRef);

        Pointer cInfo = cInfoRef.getValue();
        Pointer pInfo = pInfoRef.getValue();

        // 1. Iterate over all clients
        while (AlsaLibrary.INSTANCE.snd_seq_query_next_client(seq, cInfo) == 0) {
            int client = AlsaLibrary.INSTANCE.snd_seq_client_info_get_client(cInfo);
            String clientName = AlsaLibrary.INSTANCE.snd_seq_client_info_get_name(cInfo);
            if (client == AlsaLibrary.SND_SEQ_CLIENT_SYSTEM) continue;

            AlsaLibrary.INSTANCE.snd_seq_port_info_set_client(pInfo, client);
            AlsaLibrary.INSTANCE.snd_seq_port_info_set_port(pInfo, -1);

            // 2. Iterate over ports for the client
            while (AlsaLibrary.INSTANCE.snd_seq_query_next_port(seq, pInfo) == 0) {
                int port = AlsaLibrary.INSTANCE.snd_seq_port_info_get_port(pInfo);
                int caps = AlsaLibrary.INSTANCE.snd_seq_port_info_get_capability(pInfo);

                // Check if port is writable (i.e. we can send MIDI to it)
                if ((caps & PORT_CAP_MASK) == PORT_CAP_MASK) {
                    String portName = AlsaLibrary.INSTANCE.snd_seq_port_info_get_name(pInfo);
                    int globalPortIndex = (client << 16) | port; // Pack client and port into a single integer
                    ports.add(new MidiPort(globalPortIndex, clientName + " - " + portName));
                }
            }
        }

        AlsaLibrary.INSTANCE.snd_seq_port_info_free(pInfo);
        AlsaLibrary.INSTANCE.snd_seq_client_info_free(cInfo);
        AlsaLibrary.INSTANCE.snd_seq_close(seq);

        return ports;
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        PointerByReference seqRef = new PointerByReference();
        int errCode = AlsaLibrary.INSTANCE.snd_seq_open(seqRef, "default", AlsaLibrary.SND_SEQ_OPEN_OUTPUT, 0);
        if (errCode < 0) {
            throw new Exception("Failed to open ALSA Sequencer");
        }
        seqHandle = seqRef.getValue();

        // Create our own output port
        myPort = AlsaLibrary.INSTANCE.snd_seq_create_simple_port(
                seqHandle, "Midiraja Output",
                AlsaLibrary.SND_SEQ_PORT_CAP_READ | AlsaLibrary.SND_SEQ_PORT_CAP_SUBS_READ,
                AlsaLibrary.SND_SEQ_PORT_TYPE_MIDI_GENERIC);

        if (myPort < 0) {
            closePort();
            throw new Exception("Failed to create ALSA port");
        }

        // Unpack client and port from index
        int destClient = (portIndex >> 16) & 0xFFFF;
        int destPort = portIndex & 0xFFFF;

        // Connect our port to the destination port
        errCode = AlsaLibrary.INSTANCE.snd_seq_connect_to(seqHandle, myPort, destClient, destPort);
        if (errCode < 0) {
            closePort();
            throw new Exception("Failed to connect ALSA port to destination " + destClient + ":" + destPort);
        }

        // Setup MIDI parser to encode bytes into snd_seq_event_t
        PointerByReference parserRef = new PointerByReference();
        AlsaLibrary.INSTANCE.snd_midi_event_new(32, parserRef);
        midiEventParser = parserRef.getValue();
        
        eventBuffer = new AlsaLibrary.snd_seq_event_t();
        eventBuffer.source_port = (byte) myPort;
        eventBuffer.dest_client = (byte) destClient;
        eventBuffer.dest_port = (byte) destPort;
        // Schedule immediately without queues
        eventBuffer.queue = (byte) 253; // SND_SEQ_QUEUE_DIRECT
        eventBuffer.flags = 0; // SND_SEQ_EVENT_LENGTH_FIXED
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (seqHandle == null || midiEventParser == null) return;
        
        // Feed bytes to ALSA parser which fills our event struct
        for (byte b : data) {
            int ret = AlsaLibrary.INSTANCE.snd_midi_event_encode_byte(midiEventParser, b & 0xFF, eventBuffer.getPointer());
            if (ret == 1) { // 1 means full event encoded
                eventBuffer.read(); // Read back from native memory just encoded
                // Ensure routing is correct
                eventBuffer.source_port = (byte) myPort;
                AlsaLibrary.INSTANCE.snd_seq_event_output_direct(seqHandle, eventBuffer);
            }
        }
        AlsaLibrary.INSTANCE.snd_seq_drain_output(seqHandle);
    }

    @Override
    public void panic() {
        if (seqHandle == null) return;
        try {
            // ALSA panic loop: CC 123 (All Notes Off) for all 16 channels
            for (int ch = 0; ch < 16; ch++) {
                sendMessage(new byte[]{(byte) (0xB0 | ch), 123, 0});
            }
        } catch (Exception _) {}
    }

    @Override
    public void closePort() {
        if (midiEventParser != null) {
            AlsaLibrary.INSTANCE.snd_midi_event_free(midiEventParser);
            midiEventParser = null;
        }
        if (seqHandle != null) {
            AlsaLibrary.INSTANCE.snd_seq_close(seqHandle);
            seqHandle = null;
        }
        myPort = -1;
    }
}