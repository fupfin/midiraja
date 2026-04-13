/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.Sequence;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.midi.OpnMidiNativeBridge;

class Ym2612VgmExporterTest
{
    static class RecordingBridge implements OpnMidiNativeBridge
    {
        final List<String> calls = new ArrayList<>();
        String vgmOutPath = null;
        int playCallCount = 0;
        int playReturnValue = 1; // 양수면 계속, 0 이하면 종료

        @Override
        public void setVgmOutPath(String path)
        {
            vgmOutPath = path;
            calls.add("setVgmOutPath");
        }

        @Override
        public void init(int sampleRate)
        {
            calls.add("init");
        }

        @Override
        public void switchEmulator(int emulatorId)
        {
            calls.add("switchEmulator:" + emulatorId);
        }

        @Override
        public void loadBankData(byte[] data)
        {
            calls.add("loadBankData");
        }

        @Override
        public void setNumChips(int numChips)
        {
            calls.add("setNumChips:" + numChips);
        }

        @Override
        public void openMidiData(byte[] midiBytes) throws Exception
        {
            calls.add("openMidiData");
        }

        @Override
        public int playFromFile(short[] buffer)
        {
            playCallCount++;
            // 첫 번째 호출만 양수 반환, 이후 0 반환 → 루프 종료
            int result = (playCallCount <= 1) ? playReturnValue : 0;
            calls.add("playFromFile:" + result);
            return result;
        }

        @Override
        public void close()
        {
            calls.add("close");
        }

        // --- 사용하지 않는 MidiNativeBridge 메서드 ---

        @Override
        public void loadBankFile(String path)
        {
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void panic()
        {
        }

        @Override
        public void noteOn(int channel, int note, int velocity)
        {
        }

        @Override
        public void noteOff(int channel, int note)
        {
        }

        @Override
        public void controlChange(int channel, int type, int value)
        {
        }

        @Override
        public void patchChange(int channel, int patch)
        {
        }

        @Override
        public void pitchBend(int channel, int pitch)
        {
        }

        @Override
        public void systemExclusive(byte[] data)
        {
        }

        @Override
        public void generate(short[] buffer, int stereoFrames)
        {
        }
    }

    static Sequence minimalSequence() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack(); // type 0 직렬화를 위해 트랙 1개 필요
        return seq;
    }

    @Test
    void callOrder_setVgmOutPathBeforeInit() throws Exception
    {
        var bridge = new RecordingBridge();
        var exporter = new Ym2612VgmExporter(bridge);
        exporter.export(minimalSequence());

        int setVgmIdx = bridge.calls.indexOf("setVgmOutPath");
        int initIdx = bridge.calls.indexOf("init");
        assertTrue(setVgmIdx >= 0, "setVgmOutPath must be called");
        assertTrue(initIdx >= 0, "init must be called");
        assertTrue(setVgmIdx < initIdx, "setVgmOutPath must be called before init");
    }

    @Test
    void callOrder_switchEmulatorIs7() throws Exception
    {
        var bridge = new RecordingBridge();
        new Ym2612VgmExporter(bridge).export(minimalSequence());

        assertTrue(bridge.calls.contains("switchEmulator:7"),
                "switchEmulator must be called with value 7 (VGMFileDumper)");
    }

    @Test
    void callOrder_fullSequence() throws Exception
    {
        var bridge = new RecordingBridge();
        new Ym2612VgmExporter(bridge).export(minimalSequence());

        // setVgmOutPath → init → switchEmulator:7 → loadBankData → setNumChips:1 →
        // openMidiData → playFromFile → close
        int setVgm = bridge.calls.indexOf("setVgmOutPath");
        int init = bridge.calls.indexOf("init");
        int switchEmu = bridge.calls.indexOf("switchEmulator:7");
        int loadBank = bridge.calls.indexOf("loadBankData");
        int setChips = bridge.calls.indexOf("setNumChips:1");
        int openMidi = bridge.calls.indexOf("openMidiData");
        int play = bridge.calls.indexOf("playFromFile:1");
        int close = bridge.calls.lastIndexOf("close");

        assertTrue(setVgm < init, "setVgmOutPath before init");
        assertTrue(init < switchEmu, "init before switchEmulator");
        assertTrue(switchEmu < loadBank, "switchEmulator before loadBankData");
        assertTrue(loadBank < setChips, "loadBankData before setNumChips");
        assertTrue(setChips < openMidi, "setNumChips before openMidiData");
        assertTrue(openMidi < play, "openMidiData before playFromFile");
        assertTrue(play < close, "playFromFile before close");
    }

    @Test
    void vgmOutPath_isAbsolute() throws Exception
    {
        var bridge = new RecordingBridge();
        new Ym2612VgmExporter(bridge).export(minimalSequence());

        assertNotNull(bridge.vgmOutPath, "setVgmOutPath must be called with a path");
        assertTrue(bridge.vgmOutPath.startsWith("/") || bridge.vgmOutPath.contains(":"),
                "VGM out path must be absolute: " + bridge.vgmOutPath);
    }

    @Test
    void close_calledEvenIfPlayFromFileThrows() throws Exception
    {
        var bridge = new RecordingBridge()
        {
            @Override
            public int playFromFile(short[] buffer)
            {
                calls.add("playFromFile:throw");
                throw new RuntimeException("simulated failure");
            }
        };

        assertThrows(Exception.class, () -> new Ym2612VgmExporter(bridge).export(minimalSequence()));
        assertTrue(bridge.calls.contains("close"), "close must be called even on exception");
    }
}
