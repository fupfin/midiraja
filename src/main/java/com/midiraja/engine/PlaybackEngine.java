/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.engine;

import java.util.Optional;

import com.midiraja.midi.MidiOutProvider;
import com.midiraja.ui.PlaybackEventListener;

import javax.sound.midi.*;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.IntStream;

/**
 * Orchestrates real-time MIDI playback, managing timing, user input, and UI updates. Utilizes
 * Java's Structured Concurrency to safely isolate asynchronous tasks.
 */
public class PlaybackEngine
{
    public enum PlaybackStatus
    {
        FINISHED, NEXT, PREVIOUS, QUIT_ALL
    }

    private final Sequence sequence;
    private final MidiOutProvider provider;

    private volatile long currentMicroseconds = 0;
    private volatile long seekTarget = -1;
    private volatile float currentBpm = 120.0f;
    private volatile double currentSpeed = 1.0;
    private volatile int currentTranspose = 0;
    private volatile double volumeScale = 1.0;
    private volatile boolean isPlaying = false;
    private volatile boolean isPaused = false;
    private volatile PlaybackStatus endStatus = PlaybackStatus.FINISHED;

    private final double[] channelLevels = new double[16];
    private final List<MidiEvent> sortedEvents;
    private final int resolution;
    private final PlaylistContext context;
    private final int[] channelPrograms = new int[16];

    private final List<PlaybackEventListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PlaybackEngine(Sequence sequence, MidiOutProvider provider, PlaylistContext context, int initialVolumePercent,
            double initialSpeed, Optional<String> startTimeStr, Optional<Integer> initialTranspose)
    {
        this.sequence = sequence;
        this.provider = provider;
        this.volumeScale = initialVolumePercent / 100.0;
        this.currentSpeed = initialSpeed;
        this.currentTranspose = initialTranspose.orElse(0);
        this.resolution = sequence.getResolution();
        this.context = context;

        this.sortedEvents = Arrays.stream(sequence.getTracks())
                .flatMap(track -> IntStream.range(0, track.size()).mapToObj(track::get))
                .sorted(Comparator.comparingLong(MidiEvent::getTick)).toList();

        if (startTimeStr.isPresent() && !startTimeStr.get().isBlank())
        {
            this.seekTarget = getTickForTime(parseTimeToMicroseconds(startTimeStr.get()));
        }
    }

    public void addPlaybackEventListener(PlaybackEventListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Commences playback. The method blocks until the track finishes or is interrupted. Guaranteed
     * to tear down virtual threads and silence notes upon exit.
     *
     * @return the terminal state indicating what the user requested next (e.g., NEXT, QUIT_ALL)
     */
    @SuppressWarnings({"ThreadPriorityCheck", "NonAtomicVolatileUpdate"})
    public PlaybackStatus start(com.midiraja.ui.PlaybackUI ui) throws Exception
    {
        isPlaying = true;
        endStatus = PlaybackStatus.FINISHED;

        try (var scope = StructuredTaskScope.open())
        {
            scope.fork(() -> {
                ui.runRenderLoop(this);
                return Boolean.TRUE;
            });
            scope.fork(() -> {
                ui.runInputLoop(this);
                return Boolean.TRUE;
            });

            try
            {
                playLoop();
            }
            finally
            {
                isPlaying = false;
                provider.panic(); // Prevent dangling notes
            }

            scope.join();
        }

        return endStatus;
    }

    private long parseTimeToMicroseconds(String timeStr)
    {
        try
        {
            String[] parts = timeStr.trim().split(":");
            long seconds = 0;
            for (String part : parts)
            {
                seconds = seconds * 60 + Long.parseLong(part);
            }
            return seconds * 1000000L;
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private long getTickForTime(long targetMicroseconds)
    {
        if (targetMicroseconds <= 0) return -1;
        long targetNanos = targetMicroseconds * 1000;
        long currentNanos = 0;
        long lastTick = 0;
        float bpm = 120.0f;
        double ticksToNanos = (60000000000.0 / (bpm * resolution)); // Absolute time logic ignores
                                                                    // speed multiplier

        for (MidiEvent ev : sortedEvents)
        {
            long t = ev.getTick();
            long nextNanos = currentNanos + (long) ((t - lastTick) * ticksToNanos);
            if (nextNanos >= targetNanos)
            {
                long remainingNanos = targetNanos - currentNanos;
                return lastTick + (long) (remainingNanos / ticksToNanos);
            }

            currentNanos = nextNanos;
            lastTick = t;

            var msg = ev.getMessage().getMessage();
            int status = msg[0] & 0xFF;

            if (status == 0xFF && msg.length >= 6 && (msg[1] & 0xFF) == 0x51)
            {
                int mspqn = ((msg[3] & 0xFF) << 16) | ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
                if (mspqn > 0)
                {
                    bpm = 60000000.0f / mspqn;
                    ticksToNanos = (60000000000.0 / (bpm * resolution));
                }
            }
        }
        return sequence.getTickLength();
    }

    private void playLoop() throws Exception
    {
        long lastTick = 0;
        int eventIndex = 0;
        long elapsedNanos = 0;
        long startTimeNanos = System.nanoTime();
        double ticksToNanos = (60000000000.0 / (currentBpm * currentSpeed * resolution));

        while (isPlaying && eventIndex < sortedEvents.size())
        {
            // Check if an external seek was requested
            if (seekTarget != -1)
            {
                long target = seekTarget;
                seekTarget = -1;

                provider.panic(); // Silence lingering notes

                currentBpm = 120.0f;
                Arrays.fill(channelLevels, 0.0);

                // Fast-forward silently to accumulate correct program/control state up to the
                // target
                int newIndex = 0;
                long chaseNanos = 0;
                long chaseLastTick = 0;
                double chaseTicksToNanos =
                        (60000000000.0 / (currentBpm * currentSpeed * resolution));

                for (MidiEvent ev : sortedEvents)
                {
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

            if (tick > lastTick)
            {
                elapsedNanos += (long) ((tick - lastTick) * ticksToNanos);
                long targetNanos = startTimeNanos + elapsedNanos;

                // High-resolution delay
                long currentNanos = System.nanoTime();
                while (currentNanos < targetNanos)
                {
                    long remainingMs = (targetNanos - currentNanos) / 1000000;
                    if (remainingMs > 1)
                    {
                        Thread.sleep(remainingMs - 1);
                    }
                    else
                    {
                        Thread.yield(); // Spin-wait for the last millisecond for accuracy
                    }
                    currentNanos = System.nanoTime();
                }
            }

            processEvent(event);
            lastTick = tick;
            
            currentMicroseconds = elapsedNanos / 1000;
            
            long finalMicros = currentMicroseconds;
            listeners.forEach(l -> l.onTick(finalMicros));
            
            eventIndex++;

            // Recalculate timing ratio if BPM changed during processEvent
            ticksToNanos = (60000000000.0 / (currentBpm * currentSpeed * resolution));
        }
    }

    private void processChaseEvent(MidiEvent event)
    {
        var msg = event.getMessage();
        var raw = msg.getMessage();
        int status = raw[0] & 0xFF;

        // Meta Tempo
        if (status == 0xFF && raw.length >= 6 && (raw[1] & 0xFF) == 0x51)
        {
            int mspqn = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            if (mspqn > 0)
            {
                currentBpm = 60000000.0f / mspqn;
                listeners.forEach(l -> l.onTempoChanged(currentBpm));
            }
            return;
        }

        if (status < 0xF0)
        {
            int cmd = status & 0xF0;
            // CHASE ONLY: Program Change(0xC0), Control Change(0xB0), Pitch Bend(0xE0)
            if (cmd == 0xC0 || cmd == 0xB0 || cmd == 0xE0)
            {
                // Apply volume scaling if it's CC 7
                if (cmd == 0xB0 && raw.length >= 3 && raw[1] == 7)
                {
                    int vol = (int) ((raw[2] & 0xFF) * volumeScale);
                    raw[2] = (byte) Math.max(0, Math.min(127, vol));
                }
                try
                {
                    provider.sendMessage(raw);
                }
                catch (Exception _)
                {
                }
            }
        }
    }

    private void processEvent(MidiEvent event)
    {
        var msg = event.getMessage();
        var raw = msg.getMessage();
        int status = raw[0] & 0xFF;

        if (status == 0xFF && raw.length >= 6 && (raw[1] & 0xFF) == 0x51)
        {
            int mspqn = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            if (mspqn > 0)
            {
                currentBpm = 60000000.0f / mspqn;
                listeners.forEach(l -> l.onTempoChanged(currentBpm));
            }
            return;
        }

        if (status < 0xF0)
        {
            int cmd = status & 0xF0;
            int ch = status & 0x0F;

            if (cmd == 0xC0 && raw.length >= 2)
            {
                channelPrograms[ch] = raw[1] & 0xFF;
            }

            // Transpose Note On (0x90) and Note Off (0x80), but skip channel 10 (drums, index 9)
            if (ch != 9 && (cmd == 0x90 || cmd == 0x80))
            {
                int note = (raw[1] & 0xFF) + currentTranspose;
                raw[1] = (byte) Math.max(0, Math.min(127, note));
            }

            if (cmd == 0xB0 && raw.length >= 3 && raw[1] == 7)
            {
                int vol = (int) ((raw[2] & 0xFF) * volumeScale);
                raw[2] = (byte) Math.max(0, Math.min(127, vol));
            }

            if (cmd == 0x90 && raw.length >= 3 && (raw[2] & 0xFF) > 0)
            {
                int velocity = raw[2] & 0xFF;
                channelLevels[ch] = Math.max(channelLevels[ch], velocity / 127.0);
                listeners.forEach(l -> l.onChannelActivity(ch, velocity));
            }

            try
            {
                provider.sendMessage(raw);
            }
            catch (Exception e)
            {
                // System.err.println("MIDI Error: " + e.getMessage());
            }
        }
    }

    // --- Engine API (For UI and External Control) ---

    public PlaylistContext getContext() { return context; }
    
    public long getCurrentMicroseconds() { return currentMicroseconds; }
    
    public long getTotalMicroseconds() { return sequence.getMicrosecondLength(); }
    
    public double[] getChannelLevels() { return channelLevels; }
    
    public int[] getChannelPrograms() { return channelPrograms; }
    
    public float getCurrentBpm() { return currentBpm; }
    
    public double getCurrentSpeed() { return currentSpeed; }
    
    public int getCurrentTranspose() { return currentTranspose; }
    
    public double getVolumeScale() { return volumeScale; }
    
    public boolean isPlaying() { return isPlaying; }

    public void requestStop(PlaybackStatus status) {
        this.isPlaying = false;
        this.endStatus = status;
    }

    public void adjustVolume(double delta) {
        volumeScale = Math.max(0.0, Math.min(1.0, volumeScale + delta));
        for (int ch = 0; ch < 16; ch++) {
            byte[] msg = new byte[] {(byte) (0xB0 | ch), 7, (byte) (100 * volumeScale)};
            try { provider.sendMessage(msg); } catch (Exception _) {}
        }
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public void adjustSpeed(double delta) {
        currentSpeed = Math.max(0.5, Math.min(2.0, currentSpeed + delta));
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public void togglePause() {
        isPaused = !isPaused;
        if (isPaused) {
            try { provider.panic(); } catch (Exception _) {}
        }
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }
    
    public boolean isPaused() {
        return isPaused;
    }

    public void adjustTranspose(int delta) {
        currentTranspose += delta;
        try { provider.panic(); } catch (Exception _) {}
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public void seekRelative(long microsecondsDelta) {
        if (seekTarget == -1) {
            seekTarget = getTickForTime(Math.max(0, currentMicroseconds + microsecondsDelta));
        }
    }

    public void decayChannelLevels(double decayAmount) {
        for (int i = 0; i < 16; i++) {
            channelLevels[i] = Math.max(0, channelLevels[i] - decayAmount);
        }
    }
}
