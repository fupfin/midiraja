package com.midiraja.midi.os;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;

public class MacProvider implements MidiOutProvider {

    public interface CoreFoundation extends Library {
        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class);
        Pointer CFStringCreateWithCString(Pointer alloc, String cStr, int encoding);
        int CFStringGetCString(Pointer theString, byte[] buffer, int bufferSize, int encoding);
        void CFRelease(Pointer cf);
    }

    public interface CoreMIDI extends Library {
        CoreMIDI INSTANCE = Native.load("CoreMIDI", CoreMIDI.class);
        int MIDIGetNumberOfDestinations();
        Pointer MIDIGetDestination(int itemIndex);
        int MIDIObjectGetStringProperty(Pointer obj, Pointer propertyID, PointerByReference str);
        int MIDIClientCreate(Pointer name, Pointer notifyProc, Pointer notifyRefCon, PointerByReference outClient);
        int MIDIOutputPortCreate(Pointer client, Pointer portName, PointerByReference outPort);
        int MIDISend(Pointer port, Pointer dest, Pointer pktlist);
        Pointer MIDIPacketListInit(Pointer pktlist);
        Pointer MIDIPacketListAdd(Pointer pktlist, int listSize, Pointer curPacket, long time, int nData, byte[] data);
    }

    private Pointer clientName;
    private Pointer portName;
    private Pointer client;
    private Pointer outPort;
    private Pointer destination;
    private Memory pktListMem;

    @Override
    public List<MidiPort> getOutputPorts() {
        List<MidiPort> ports = new ArrayList<>();
        int destCount = CoreMIDI.INSTANCE.MIDIGetNumberOfDestinations();
        Pointer kMIDIPropertyName = CoreFoundation.INSTANCE.CFStringCreateWithCString(null, "name", 0x08000100);

        for (int i = 0; i < destCount; i++) {
            Pointer dest = CoreMIDI.INSTANCE.MIDIGetDestination(i);
            PointerByReference strRef = new PointerByReference();
            int status = CoreMIDI.INSTANCE.MIDIObjectGetStringProperty(dest, kMIDIPropertyName, strRef);
            if (status == 0) {
                Pointer cfString = strRef.getValue();
                byte[] buffer = new byte[256];
                if (CoreFoundation.INSTANCE.CFStringGetCString(cfString, buffer, buffer.length, 0x08000100) != 0) {
                    ports.add(new MidiPort(i, new String(buffer).trim()));
                }
                CoreFoundation.INSTANCE.CFRelease(cfString);
            }
        }
        CoreFoundation.INSTANCE.CFRelease(kMIDIPropertyName);
        return ports;
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        int destCount = CoreMIDI.INSTANCE.MIDIGetNumberOfDestinations();
        if (portIndex < 0 || portIndex >= destCount) {
            throw new IllegalArgumentException("Invalid Mac port index.");
        }

        destination = CoreMIDI.INSTANCE.MIDIGetDestination(portIndex);
        clientName = CoreFoundation.INSTANCE.CFStringCreateWithCString(null, "MidrajaClient", 0x08000100);
        portName = CoreFoundation.INSTANCE.CFStringCreateWithCString(null, "MidrajaOutPort", 0x08000100);
        
        PointerByReference clientRef = new PointerByReference();
        PointerByReference portRef = new PointerByReference();
        
        CoreMIDI.INSTANCE.MIDIClientCreate(clientName, null, null, clientRef);
        CoreMIDI.INSTANCE.MIDIOutputPortCreate(clientRef.getValue(), portName, portRef);

        client = clientRef.getValue();
        outPort = portRef.getValue();
        pktListMem = new Memory(512); // 메시지를 담을 공간
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (outPort == null || destination == null) return;
        Pointer curPkt = CoreMIDI.INSTANCE.MIDIPacketListInit(pktListMem);
        CoreMIDI.INSTANCE.MIDIPacketListAdd(pktListMem, 512, curPkt, 0, data.length, data);
        CoreMIDI.INSTANCE.MIDISend(outPort, destination, pktListMem);
    }

    @Override
    public void closePort() {
        if (clientName != null) CoreFoundation.INSTANCE.CFRelease(clientName);
        if (portName != null) CoreFoundation.INSTANCE.CFRelease(portName);
        // Note: CoreMIDI handles cleanup when client is closed or app exits.
    }
}