/*
 * Copyright (c) 2026, Park, Sungchul
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.midiraja.midi.os.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.List;

/**
 * JNA Mapping for ALSA Sequencer API (libasound.so.2).
 */
public interface AlsaLibrary extends Library {
    AlsaLibrary INSTANCE = Native.load("asound", AlsaLibrary.class);

    // Constants
    int SND_SEQ_OPEN_OUTPUT = 2;
    int SND_SEQ_NONBLOCK = 1;
    
    int SND_SEQ_PORT_CAP_READ = (1<<0);
    int SND_SEQ_PORT_CAP_WRITE = (1<<1);
    int SND_SEQ_PORT_CAP_SUBS_READ = (1<<5);
    int SND_SEQ_PORT_CAP_SUBS_WRITE = (1<<6);
    int SND_SEQ_PORT_TYPE_MIDI_GENERIC = (1<<1);

    // Event Types
    byte SND_SEQ_EVENT_NOTEON = 6;
    byte SND_SEQ_EVENT_NOTEOFF = 7;
    byte SND_SEQ_EVENT_CONTROLLER = 10;
    byte SND_SEQ_EVENT_PGMCHANGE = 11;
    byte SND_SEQ_EVENT_PITCHBEND = 12;

    int SND_SEQ_CLIENT_SYSTEM = 0;

    int SND_SEQ_TIME_STAMP_REAL = (1<<0);
    int SND_SEQ_TIME_MODE_REL = (1<<1);

    int SND_SEQ_EVENT_LENGTH_FIXED = (0<<2);

    // --- Core Functions ---
    int snd_seq_open(PointerByReference seq, String name, int streams, int mode);
    int snd_seq_close(Pointer seq);
    int snd_seq_client_id(Pointer seq);

    // --- Port Management ---
    int snd_seq_create_simple_port(Pointer seq, String name, int caps, int type);
    int snd_seq_connect_to(Pointer seq, int my_port, int dest_client, int dest_port);

    // --- Query Functions ---
    int snd_seq_client_info_malloc(PointerByReference info);
    void snd_seq_client_info_free(Pointer info);
    int snd_seq_query_next_client(Pointer seq, Pointer info);
    int snd_seq_client_info_get_client(Pointer info);
    String snd_seq_client_info_get_name(Pointer info);

    int snd_seq_port_info_malloc(PointerByReference info);
    void snd_seq_port_info_free(Pointer info);
    int snd_seq_query_next_port(Pointer seq, Pointer info);
    int snd_seq_port_info_set_client(Pointer info, int client);
    int snd_seq_port_info_set_port(Pointer info, int port);
    int snd_seq_port_info_get_client(Pointer info);
    int snd_seq_port_info_get_port(Pointer info);
    int snd_seq_port_info_get_capability(Pointer info);
    int snd_seq_port_info_get_type(Pointer info);
    String snd_seq_port_info_get_name(Pointer info);

    // --- Events (Using RawMIDI parser for simplicity) ---
    // It is notoriously difficult to map the complex snd_seq_event_t union. 
    // Fortunately, ALSA provides midi event parser utilities!
    int snd_midi_event_new(int bufsize, PointerByReference rdev);
    void snd_midi_event_free(Pointer dev);
    int snd_midi_event_reset_encode(Pointer dev);
    int snd_midi_event_encode_byte(Pointer dev, int c, Pointer ev);

    // Direct Structure approach (Size: 28 bytes)
    public static class snd_seq_event_t extends Structure {
        public byte type;          // 0
        public byte flags;         // 1
        public byte tag;           // 2
        public byte queue;         // 3
        public long time;          // 4 (8 bytes union: tick or timespec)
        public byte source_client; // 12
        public byte source_port;   // 13
        public byte dest_client;   // 14
        public byte dest_port;     // 15
        public byte[] data = new byte[12]; // 16 (union size is 12 bytes: 28 - 16)

        public snd_seq_event_t() {
            super(ALIGN_NONE);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("type", "flags", "tag", "queue", "time", "source_client", "source_port", "dest_client", "dest_port", "data");
        }
    }

    int snd_seq_event_output_direct(Pointer seq, snd_seq_event_t ev);
    int snd_seq_drain_output(Pointer seq);
}
