package com.midiraja.midi.os;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.midi.MidiPort;

import java.util.ArrayList;
import java.util.List;

import static java.lang.System.err;

public class LinuxProvider implements MidiOutProvider {

    // TODO: Linux ALSA 라이브러리(libasound.so) 매핑을 위한 JNA 인터페이스 구현 예정
    // ALSA의 snd_seq API는 매우 방대하므로 별도 구현이 필요합니다.
    
    @Override
    public List<MidiPort> getOutputPorts() {
        err.println("[Midiraja] Linux ALSA MIDI provider is currently a stub.");
        return new ArrayList<>();
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        throw new UnsupportedOperationException("Linux ALSA port opening not yet implemented.");
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        // No-op for now
    }

    @Override
    public void closePort() {
        // No-op for now
    }
}