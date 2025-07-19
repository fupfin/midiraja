package com.midraja.midi;

import java.util.List;

public interface MidiOutProvider {
    /**
     * 사용 가능한 MIDI 출력 기기 목록 반환
     */
    List<MidiPort> getOutputPorts();

    /**
     * 특정 포트를 열고 연결
     */
    void openPort(int portIndex) throws Exception;

    /**
     * MIDI 메시지 (바이트 배열) 전송
     */
    void sendMessage(byte[] data) throws Exception;

    /**
     * 포트 닫기 및 리소스 정리
     */
    void closePort();

    /**
     * 모든 채널(0-15)에 마스터 볼륨(CC 7) 값을 전송
     */
    default void setVolume(int volume) {
        if (volume < 0 || volume > 127) return;
        for (int ch = 0; ch < 16; ch++) {
            try {
                sendMessage(new byte[]{(byte) (0xB0 | ch), 7, (byte) volume});
            } catch (Exception ignored) {}
        }
    }

    /**
     * 모든 채널의 소리를 즉시 차단 (All Notes Off)
     */
    default void panic() {
        for (int ch = 0; ch < 16; ch++) {
            try {
                // All Notes Off (Controller 123) 및 All Sound Off (Controller 120)
                sendMessage(new byte[]{(byte) (0xB0 | ch), 123, 0});
                sendMessage(new byte[]{(byte) (0xB0 | ch), 120, 0});
            } catch (Exception ignored) {}
        }
    }
}