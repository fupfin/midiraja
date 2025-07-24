package com.midiraja.midi.os;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WindowsProvider implements MidiOutProvider {

    public interface WinMM extends Library {
        WinMM INSTANCE = Native.load("winmm", WinMM.class);

        int midiOutGetNumDevs();
        int midiOutGetDevCapsA(Pointer uDeviceID, MIDIOUTCAPS caps, int cbMidiOutCaps);
        int midiOutOpen(PointerByReference lphmo, int uDeviceID, Pointer dwCallback, Pointer dwCallbackInstance, int dwFlags);
        int midiOutShortMsg(Pointer hmo, int dwMsg);
        int midiOutClose(Pointer hmo);
    }

    public static class MIDIOUTCAPS extends Structure {
        public short wMid;
        public short wPid;
        public int vDriverVersion;
        public byte[] szPname = new byte[32]; // 기기 이름
        public short wTechnology;
        public short wVoices;
        public short wNotes;
        public short wChannelMask;
        public int dwSupport;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("wMid", "wPid", "vDriverVersion", "szPname", "wTechnology", "wVoices", "wNotes", "wChannelMask", "dwSupport");
        }
    }

    private Pointer handle;

    @Override
    public List<MidiPort> getOutputPorts() {
        List<MidiPort> ports = new ArrayList<>();
        int devs = WinMM.INSTANCE.midiOutGetNumDevs();
        for (int i = 0; i < devs; i++) {
            MIDIOUTCAPS caps = new MIDIOUTCAPS();
            if (WinMM.INSTANCE.midiOutGetDevCapsA(new Pointer(i), caps, caps.size()) == 0) {
                String name = Native.toString(caps.szPname);
                ports.add(new MidiPort(i, name));
            }
        }
        return ports;
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        PointerByReference ref = new PointerByReference();
        if (WinMM.INSTANCE.midiOutOpen(ref, portIndex, null, null, 0) != 0) {
            throw new Exception("Failed to open Windows MIDI port " + portIndex);
        }
        handle = ref.getValue();
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (handle == null || data == null || data.length == 0) return;
        
        // Windows WinMM은 1~3바이트 짧은 메시지를 32비트(int)로 패킹해서 보냅니다.
        if (data.length <= 3) {
            int msg = 0;
            for (int i = 0; i < data.length; i++) {
                msg |= ((data[i] & 0xFF) << (8 * i));
            }
            WinMM.INSTANCE.midiOutShortMsg(handle, msg);
        } else {
            // TODO: System Exclusive(길이가 긴 메시지) 처리 로직 (midiOutLongMsg 필요)
            // 현재 구조에서는 무시하거나 나중에 구현합니다.
        }
    }

    @Override
    public void closePort() {
        if (handle != null) {
            WinMM.INSTANCE.midiOutClose(handle);
            handle = null;
        }
    }
}