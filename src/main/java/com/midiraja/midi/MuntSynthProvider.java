/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.midi;

import java.util.List;

@SuppressWarnings("ThreadPriorityCheck")
public class MuntSynthProvider implements SoftSynthProvider {

    private final MuntNativeBridge bridge;
    private final @org.jspecify.annotations.Nullable NativeAudioEngine audio;
    private @org.jspecify.annotations.Nullable Thread renderThread;
    private volatile boolean running = false;

    public MuntSynthProvider(MuntNativeBridge bridge, @org.jspecify.annotations.Nullable NativeAudioEngine audio) {
        this.bridge = bridge;
        this.audio = audio;
    }

    @Override
    public List<MidiPort> getOutputPorts() {
        return List.of(new MidiPort(0, "Munt MT-32 Emulator (Embedded)"));
    }

    @Override
    public void openPort(int portIndex) throws Exception {
        bridge.createSynth();
    }
    
    private void startRenderThread() {
        running = true;
        renderThread = new Thread(() -> {
            // Buffer size: 512 frames = 1024 shorts (stereo)
            // 512 frames at 32kHz is 16ms of audio.
            final int framesToRender = 512;
            short[] pcmBuffer = new short[framesToRender * 2];
            
            while (running) {
                // Pull rendered PCM data from Munt (it fills the pcmBuffer)
                bridge.renderAudio(pcmBuffer, framesToRender);

                // Push it to the miniaudio ring buffer. 
                // This call will safely block if the buffer is full, pacing the thread.
                if (audio != null) {
                    audio.push(pcmBuffer);
                } else {
                    // If no audio engine, just sleep to simulate time passing
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        // AUDIO PRIORITY: We need high priority to prevent dropouts, 
        // even if the thread scheduler is not guaranteed to honor it.
        renderThread.setPriority(Thread.MAX_PRIORITY); 
        renderThread.setDaemon(true);
        renderThread.start();
    }

    @Override
    public void loadSoundbank(String path) throws Exception {
        bridge.loadRoms(path);
        bridge.openSynth();

        if (audio != null) {
            audio.init(32000, 2, 4096); // Munt renders at 32000Hz natively
            startRenderThread();
        }
    }

    @Override
    public void sendMessage(byte[] data) throws Exception {
        if (data == null || data.length == 0) return;

        int status = data[0] & 0xFF;
        if (status >= 0xF0) {
            bridge.playSysex(data);
            return;
        }

        int command = status & 0xF0;
        int channel = status & 0x0F;

        if (data.length >= 2) {
            int data1 = data[1] & 0xFF;
            int data2 = (data.length >= 3) ? (data[2] & 0xFF) : 0;

            switch (command) {
                case 0x90:
                    bridge.playNoteOn(channel, data1, data2);
                    break;
                case 0x80:
                    bridge.playNoteOff(channel, data1);
                    break;
                case 0xB0:
                    bridge.playControlChange(channel, data1, data2);
                    break;
                case 0xC0:
                    bridge.playProgramChange(channel, data1);
                    break;
                case 0xE0:
                    int bend = (data2 << 7) | data1;
                    bridge.playPitchBend(channel, bend);
                    break;
            }
        }
    }

    @Override
    @SuppressWarnings("EmptyCatch")
    public void closePort() {
        running = false;
        if (renderThread != null) {
            renderThread.interrupt();
            try {
                renderThread.join(500);
            } catch (InterruptedException ignored) {
                // Expected during shutdown
            }
        }
        
        bridge.close();
        if (audio != null) {
            audio.close();
        }
    }
}
