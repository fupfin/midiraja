/*
 * Copyright (c) 2026, Park, Sungchul
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.midiraja.engine;

import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.IntStream;

public class PlaybackEngine {
    public enum PlaybackStatus {
        FINISHED, NEXT, PREVIOUS, QUIT_ALL
    }

    private final Sequence sequence;
    private final MidiOutProvider provider;
    
    private volatile long currentTick = 0;
    private volatile long currentMicroseconds = 0;
    private volatile long seekTarget = -1;
    private volatile float currentBpm = 120.0f;
    private volatile double currentSpeed = 1.0;
    private volatile int currentTranspose = 0;
    private volatile double volumeScale = 1.0;
    private volatile boolean isPlaying = false;
    private volatile PlaybackStatus endStatus = PlaybackStatus.FINISHED;
    
    private final double[] channelLevels = new double[16];
    private final List<MidiEvent> sortedEvents;
    private final int resolution;

    public PlaybackEngine(Sequence sequence, MidiOutProvider provider, int initialVolumePercent, double initialSpeed, String startTimeStr, Integer initialTranspose) {
        this.sequence = sequence;
        this.provider = provider;
        this.volumeScale = initialVolumePercent / 100.0;
        this.currentSpeed = initialSpeed;
        this.currentTranspose = initialTranspose != null ? initialTranspose : 0;
        this.resolution = sequence.getResolution();
        
        this.sortedEvents = Arrays.stream(sequence.getTracks())
                .flatMap(track -> IntStream.range(0, track.size()).mapToObj(track::get))
                .sorted(Comparator.comparingLong(MidiEvent::getTick))
                .toList();

        if (startTimeStr != null && !startTimeStr.isBlank()) {
            this.seekTarget = getTickForTime(parseTimeToMicroseconds(startTimeStr));
        }
    }

    public PlaybackStatus start() throws Exception {
        isPlaying = true;
        endStatus = PlaybackStatus.FINISHED;
        
        try (var scope = StructuredTaskScope.open()) {
            // Start UI and Input as concurrent subtasks
            scope.fork(() -> { uiLoop(); return null; });
            scope.fork(() -> { inputLoop(); return null; });
            
            // Run the main playback loop in the current thread (or fork it too)
            try {
                playLoop();
            } finally {
                // Ensure all notes are silenced and stop signal is sent
                isPlaying = false;
                provider.panic();
            }
            
            // Wait for UI and Input threads to notice isPlaying = false and exit cleanly
            scope.join();
        }

        return endStatus;
    }

    private long parseTimeToMicroseconds(String timeStr) {
        try {
            String[] parts = timeStr.trim().split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Long.parseLong(part);
            }
            return seconds * 1000000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long getTickForTime(long targetMicroseconds) {
        if (targetMicroseconds <= 0) return -1;
        long targetNanos = targetMicroseconds * 1000;
        long currentNanos = 0;
        long lastTick = 0;
        float bpm = 120.0f;
        double ticksToNanos = (60000000000.0 / (bpm * resolution)); // Absolute time logic ignores speed multiplier

        for (MidiEvent ev : sortedEvents) {
            long t = ev.getTick();
            long nextNanos = currentNanos + (long) ((t - lastTick) * ticksToNanos);
            if (nextNanos >= targetNanos) {
                long remainingNanos = targetNanos - currentNanos;
                return lastTick + (long) (remainingNanos / ticksToNanos);
            }
            
            currentNanos = nextNanos;
            lastTick = t;

            var msg = ev.getMessage().getMessage();
            int status = msg[0] & 0xFF;
            if (status == 0xFF && msg.length >= 6 && (msg[1] & 0xFF) == 0x51) {
                int mspqn = ((msg[3] & 0xFF) << 16) | ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
                if (mspqn > 0) {
                    bpm = 60000000.0f / mspqn;
                    ticksToNanos = (60000000000.0 / (bpm * resolution));
                }
            }
        }
        return sequence.getTickLength();
    }

    private void playLoop() throws Exception {
        long lastTick = 0;
        int eventIndex = 0;
        long elapsedNanos = 0;
        long startTimeNanos = System.nanoTime();
        double ticksToNanos = (60000000000.0 / (currentBpm * currentSpeed * resolution));

        while (isPlaying && eventIndex < sortedEvents.size()) {
            // Check if an external seek was requested
            if (seekTarget != -1) {
                long target = seekTarget;
                seekTarget = -1; // Reset flag
                
                // 1. Panic: Stop all currently ringing notes
                provider.panic();
                
                // 2. Reset UI levels and tempo
                currentBpm = 120.0f;
                Arrays.fill(channelLevels, 0.0);
                
                // 3. Fast-forward & Chase state from tick 0 to target
                int newIndex = 0;
                long chaseNanos = 0;
                long chaseLastTick = 0;
                double chaseTicksToNanos = (60000000000.0 / (currentBpm * currentSpeed * resolution));
                
                for (MidiEvent ev : sortedEvents) {
                    if (ev.getTick() >= target) break;
                    long t = ev.getTick();
                    chaseNanos += (long) ((t - chaseLastTick) * chaseTicksToNanos);
                    chaseLastTick = t;
                    
                    processChaseEvent(ev);
                    
                    chaseTicksToNanos = (60000000000.0 / (currentBpm * currentSpeed * resolution));
                    newIndex++;
                }
                
                chaseNanos += (long) ((target - chaseLastTick) * chaseTicksToNanos);
                
                // 4. Resume playback from the new position
                currentTick = target;
                lastTick = target;
                eventIndex = newIndex;
                elapsedNanos = chaseNanos;
                currentMicroseconds = elapsedNanos / 1000;
                
                // Reset timing reference after seek
                ticksToNanos = (60000000000.0 / (currentBpm * currentSpeed * resolution));
                startTimeNanos = System.nanoTime() - elapsedNanos;
                continue;
            }

            var event = sortedEvents.get(eventIndex);
            long tick = event.getTick();
            
            if (tick > lastTick) {
                elapsedNanos += (long) ((tick - lastTick) * ticksToNanos);
                long targetNanos = startTimeNanos + elapsedNanos;
                
                // High-resolution delay
                long currentNanos = System.nanoTime();
                while (currentNanos < targetNanos) {
                    long remainingMs = (targetNanos - currentNanos) / 1000000;
                    if (remainingMs > 1) {
                        Thread.sleep(remainingMs - 1);
                    } else {
                        Thread.yield(); // Spin-wait for the last millisecond for accuracy
                    }
                    currentNanos = System.nanoTime();
                }
            }

            processEvent(event);
            lastTick = tick;
            currentTick = tick;
            currentMicroseconds = elapsedNanos / 1000;
            eventIndex++;
            
            // Recalculate timing ratio if BPM changed during processEvent
            ticksToNanos = (60000000000.0 / (currentBpm * currentSpeed * resolution));
        }
    }

    private void processChaseEvent(MidiEvent event) {
        var msg = event.getMessage();
        var raw = msg.getMessage();
        int status = raw[0] & 0xFF;

        // Meta Tempo
        if (status == 0xFF && raw.length >= 6 && (raw[1] & 0xFF) == 0x51) {
            int mspqn = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            if (mspqn > 0) {
                currentBpm = 60000000.0f / mspqn;
            }
            return;
        }

        if (status < 0xF0) {
            int cmd = status & 0xF0;
            // CHASE ONLY: Program Change(0xC0), Control Change(0xB0), Pitch Bend(0xE0)
            if (cmd == 0xC0 || cmd == 0xB0 || cmd == 0xE0) {
                // Apply volume scaling if it's CC 7
                if (cmd == 0xB0 && raw.length >= 3 && raw[1] == 7) {
                    int vol = (int) ((raw[2] & 0xFF) * volumeScale);
                    raw[2] = (byte) Math.max(0, Math.min(127, vol));
                }
                try {
                    provider.sendMessage(raw);
                } catch (Exception _) {}
            }
        }
    }

    private void processEvent(MidiEvent event) {
        var msg = event.getMessage();
        var raw = msg.getMessage();
        int status = raw[0] & 0xFF;

        if (status == 0xFF && raw.length >= 6 && (raw[1] & 0xFF) == 0x51) {
            int mspqn = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            if (mspqn > 0) {
                currentBpm = 60000000.0f / mspqn;
            }
            return;
        }

        if (status < 0xF0) {
            int cmd = status & 0xF0;
            int ch = status & 0x0F;

            // Transpose Note On (0x90) and Note Off (0x80), but skip channel 10 (drums, index 9)
            if (ch != 9 && (cmd == 0x90 || cmd == 0x80)) {
                int note = (raw[1] & 0xFF) + currentTranspose;
                raw[1] = (byte) Math.max(0, Math.min(127, note));
            }

            if (cmd == 0xB0 && raw.length >= 3 && raw[1] == 7) {
                int vol = (int) ((raw[2] & 0xFF) * volumeScale);
                raw[2] = (byte) Math.max(0, Math.min(127, vol));
            }

            if (cmd == 0x90 && raw.length >= 3 && (raw[2] & 0xFF) > 0) {
                channelLevels[ch] = Math.max(channelLevels[ch], (raw[2] & 0xFF) / 127.0);
            }

            try {
                provider.sendMessage(raw);
            } catch (Exception _) {}
        }
    }

    private void inputLoop() {
        var terminalIO = TerminalIO.CONTEXT.get();
        try {
            while (isPlaying) {
                var key = terminalIO.readKey();
                switch (key) {
                    case VOLUME_UP -> {
                        volumeScale = Math.min(1.0, volumeScale + 0.05);
                        applyVolumeInstantly();
                    }
                    case VOLUME_DOWN -> {
                        volumeScale = Math.max(0.0, volumeScale - 0.05);
                        applyVolumeInstantly();
                    }
                    case SPEED_UP -> {
                        currentSpeed = Math.min(5.0, currentSpeed + 0.1);
                    }
                    case SPEED_DOWN -> {
                        currentSpeed = Math.max(0.1, currentSpeed - 0.1);
                    }
                    case TRANSPOSE_UP -> {
                        provider.panic();
                        currentTranspose++;
                    }
                    case TRANSPOSE_DOWN -> {
                        provider.panic();
                        currentTranspose--;
                    }
                    case SEEK_FORWARD -> {
                        // Seek roughly +10 seconds based on current BPM
                        long ticksToSeekFwd = (long) ((10000.0 * currentBpm * resolution) / 60000.0);
                        long targetF = currentTick + ticksToSeekFwd;
                        seekTarget = Math.min(targetF, sequence.getTickLength());
                    }
                    case SEEK_BACKWARD -> {
                        // Seek roughly -10 seconds based on current BPM
                        long ticksToSeekBwd = (long) ((10000.0 * currentBpm * resolution) / 60000.0);
                        seekTarget = Math.max(0, currentTick - ticksToSeekBwd);
                    }
                    case NEXT_TRACK -> {
                        endStatus = PlaybackStatus.NEXT;
                        isPlaying = false;
                    }
                    case PREV_TRACK -> {
                        endStatus = PlaybackStatus.PREVIOUS;
                        isPlaying = false;
                    }
                    case QUIT -> {
                        endStatus = PlaybackStatus.QUIT_ALL;
                        isPlaying = false;
                    }
                    default -> {}
                }
            }
        } catch (IOException _) {}
    }

    private void applyVolumeInstantly() {
        int vol = (int) (100 * volumeScale);
        byte volByte = (byte) Math.max(0, Math.min(127, vol));
        
        IntStream.range(0, 16).forEach(ch -> {
            try {
                provider.sendMessage(new byte[]{(byte) (0xB0 | ch), 7, volByte});
            } catch (Exception _) {}
        });
    }

    private void uiLoop() {
        var terminalIO = TerminalIO.CONTEXT.get();
        if (!terminalIO.isInteractive()) {
            terminalIO.println("Playing (Interactive UI disabled)...");
            return; // UI 루프 종료, 시스템 자원 절약
        }

        long totalMicroseconds = sequence.getMicrosecondLength();
        boolean includeHours = (totalMicroseconds / 1000000) >= 3600;
        String totalTimeStr = formatTime(totalMicroseconds, includeHours);

        String[] blocks = {" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█"};
        terminalIO.print("\033[?25l"); // 커서 숨기기

        try {
            while (isPlaying) {
                var sb = new StringBuilder("\r[");
                for (int i = 0; i < 16; i++) {
                    int lv = (int) Math.round(channelLevels[i] * 8);
                    sb.append(blocks[Math.max(0, Math.min(8, lv))]);
                    channelLevels[i] = Math.max(0, channelLevels[i] - 0.1);
                }
                sb.append("] ");
                
                                        String currentTimeStr = formatTime(currentMicroseconds, includeHours);
                                        String transStr = currentTranspose == 0 ? "" : String.format(" %+d", currentTranspose);
                                        sb.append(String.format("%s/%s (BPM: %5.1f x%.1f, Vol: %3d%%%s) \033[K", 
                                            currentTimeStr, totalTimeStr, currentBpm, currentSpeed, (int)(volumeScale*100), transStr));
                                        terminalIO.print(sb.toString());
                                        try { Thread.sleep(50); } catch (InterruptedException _) {}
            }
        } finally {
            terminalIO.print("\033[?25h\n"); // 커서 보이기 및 줄바꿈
        }
    }

    private String formatTime(long microseconds, boolean includeHours) {
        long seconds = microseconds / 1000000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (includeHours) {
            return String.format("%02d:%02d:%02d", h, m, s);
        } else {
            return String.format("%02d:%02d", m, s);
        }
    }
}