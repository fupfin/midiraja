/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;


import static java.lang.Math.*;
import static java.util.Locale.ROOT;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.MidirajaCommand;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiProcessor;
import com.fupfin.midiraja.midi.SysexFilter;
import com.fupfin.midiraja.midi.TransposeFilter;
import com.fupfin.midiraja.midi.VolumeFilter;
import com.fupfin.midiraja.ui.PlaybackEventListener;
import com.fupfin.midiraja.ui.PlaybackUI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import javax.sound.midi.*;

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
    private final MidiProcessor pipelineRoot;
    private final TransposeFilter transposeFilter;
    private final VolumeFilter volumeFilter;
    private final SysexFilter sysexFilter;

    private final AtomicLong currentMicroseconds = new AtomicLong(0);
    private final AtomicLong seekTarget = new AtomicLong(-1);
    private final AtomicReference<Float> currentBpm = new AtomicReference<>(120.0f);
    private final AtomicReference<Double> currentSpeed = new AtomicReference<>(1.0);


    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    private volatile boolean bookmarked = false;
    @SuppressWarnings("NullAway")
    private volatile java.util.function.Consumer<Boolean> bookmarkCallback = null;

    private final AtomicBoolean holdAtEnd = new AtomicBoolean(false);
    private final AtomicReference<PlaybackStatus> endStatus =
            new AtomicReference<>(PlaybackStatus.FINISHED);
    private final AtomicBoolean playbackActuallyStarted = new AtomicBoolean(false);
    private Optional<String> initialResetType = Optional.empty();

    public void setInitiallyPaused()
    {
        this.isPaused.set(true);
    }

    public void setIgnoreSysex(boolean ignoreSysex)
    {
        sysexFilter.setIgnoreSysex(ignoreSysex);
    }

    public void setInitialResetType(Optional<String> resetType)
    {
        this.initialResetType = resetType;
    }

    public void setHoldAtEnd(boolean hold)
    {
        this.holdAtEnd.set(hold);
    }

    private final double[] channelLevels = new double[16];
    private final List<MidiEvent> sortedEvents;
    private final int resolution;
    private final PlaylistContext context;
    private final int[] channelPrograms = new int[16];

    private final List<PlaybackEventListener> listeners =
            new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService notificationScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "midi-notify");
                t.setDaemon(true);
                return t;
            });

    public PlaybackEngine(Sequence sequence, MidiOutProvider provider, PlaylistContext context,
            int initialVolumePercent, double initialSpeed, Optional<String> startTimeStr,
            Optional<Integer> initialTranspose)
    {
        this.sequence = sequence;
        this.provider = provider;

        this.currentSpeed.set(initialSpeed);
        // If the provider owns a DSP output gain, volume is controlled there. VolumeFilter is
        // kept at 1.0 so MIDI CC 7 messages from the song pass through unscaled.
        double initVol = provider.outputGain().isPresent() ? 1.0
                : max(0, min(100, initialVolumePercent)) / 100.0;
        this.sysexFilter = new SysexFilter(provider, false);
        this.volumeFilter = new VolumeFilter(this.sysexFilter, initVol);
        this.transposeFilter = new TransposeFilter(this.volumeFilter,
                initialTranspose.orElse(0));
        this.pipelineRoot = this.transposeFilter;
        this.resolution = sequence.getResolution();
        this.context = context;

        this.sortedEvents = Arrays.stream(sequence.getTracks())
                .flatMap(track -> IntStream.range(0, track.size()).mapToObj(track::get))
                .sorted(Comparator.comparingLong(MidiEvent::getTick)).toList();

        if (startTimeStr.isPresent() && !startTimeStr.get().isBlank())
        {
            this.seekTarget.set(getTickForTime(parseTimeToMicroseconds(startTimeStr.get())));
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
    public PlaybackStatus start(PlaybackUI ui) throws Exception
    {
        isPlaying.set(true);
        playbackActuallyStarted.set(false);
        endStatus.set(PlaybackStatus.FINISHED);

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
                isPlaying.set(false);
                if (playbackActuallyStarted.get())
                {
                    provider.panic(); // Prevent dangling notes
                }
            }

            scope.join();
        }

        notificationScheduler.shutdown();
        PlaybackStatus status = endStatus.get();
        return status != null ? status : PlaybackStatus.FINISHED;
    }

    private long parseTimeToMicroseconds(String timeStr)
    {
        try
        {
            String[] parts = timeStr.trim().split(":", -1);
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
        @SuppressWarnings("NullAway")
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

    @SuppressWarnings("EmptyCatch")
    private void sendInitialReset()
    {
        if (initialResetType.isEmpty()) return;
        String type = initialResetType.get().trim().toLowerCase(ROOT);
        byte[] payload = switch (type)
        {
            case "gm"         -> new byte[] {(byte) 0xF0, 0x7E, 0x7F, 0x09, 0x01, (byte) 0xF7};
            case "gm2"        -> new byte[] {(byte) 0xF0, 0x7E, 0x7F, 0x09, 0x03, (byte) 0xF7};
            case "gs"         -> new byte[] {(byte) 0xF0, 0x41, 0x10, 0x42, 0x12, 0x40, 0x00,
                                             0x7F, 0x00, 0x41, (byte) 0xF7};
            case "xg"         -> new byte[] {(byte) 0xF0, 0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E,
                                             0x00, (byte) 0xF7};
            case "mt32", "mt-32" -> new byte[] {(byte) 0xF0, 0x41, 0x10, 0x16, 0x12, 0x7F, 0x00,
                                                0x00, 0x00, 0x01, (byte) 0xF7}; // 11-byte Roland SysEx reset
            default ->
            {
                if (type.matches("^[0-9a-fA-F]+$") && type.length() % 2 == 0)
                {
                    byte[] hex = new byte[type.length() / 2];
                    for (int i = 0; i < hex.length; i++)
                        hex[i] = (byte) Integer.parseInt(type.substring(i * 2, i * 2 + 2), 16);
                    yield hex;
                }
                yield null;
            }
        };

        if (payload != null)
        {
            try
            {
                pipelineRoot.sendMessage(payload);
                Thread.sleep(50); // Give the hardware synthesizer 50ms to process the
                                  // reset before slamming it with notes
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private void playLoop() throws Exception
    {
        // STARTUP DELAY (UX Improvement): Wait 500ms before actually starting playback.
        // This allows the user to quickly skip through tracks (Next/Prev)
        // without triggering heavy audio initialization and unwanted noise.
        long startupWaitEnd = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < startupWaitEnd)
        {
            if (!isPlaying.get())
            {
                // User hit next/prev/quit during the delay. Abort immediately.
                return;
            }
            Thread.sleep(10);
        }

        playbackActuallyStarted.set(true);

        // Clear any reverb tail / queued audio from a previous song before
        // starting. No-op for hardware MIDI ports; Munt overrides this to flush the
        // ring buffer.
        provider.prepareForNewTrack(sequence);

        // Signal the provider that playback is about to begin. Munt uses this to
        // resume its render thread (paused since prepareForNewTrack) with a fresh
        // timing reference, so the ring buffer starts filling with real audio
        // instead of silence.
        provider.onPlaybackStarted();

        sendInitialReset();

        long lastTick = 0;
        int eventIndex = 0;
        long elapsedNanos = 0;
        long startTimeNanos = System.nanoTime();
        @SuppressWarnings("NullAway")
        double ticksToNanos =
                (60000000000.0 / (currentBpm.get() * currentSpeed.get() * resolution));

        boolean endReached = false;
        while (isPlaying.get() && (eventIndex < sortedEvents.size() || holdAtEnd.get()))
        {
            // Check if an external seek was requested
            if (seekTarget.get() != -1)
            {
                long target = seekTarget.get();
                seekTarget.set(-1);

                provider.panic(); // Silence lingering notes

                currentBpm.set(120.0f);
                Arrays.fill(channelLevels, 0.0);

                // Fast-forward silently to accumulate correct program/control state up
                // to the target
                int newIndex = 0;
                long chaseNanos = 0;
                long chaseLastTick = 0;
                double chaseTicksToNanos =
                        (60000000000.0 / (currentBpm.get() * currentSpeed.get() * resolution));

                for (MidiEvent ev : sortedEvents)
                {
                    if (ev.getTick() >= target) break;
                    long t = ev.getTick();
                    chaseNanos += (long) ((t - chaseLastTick) * chaseTicksToNanos);
                    chaseLastTick = t;

                    processChaseEvent(ev);

                    chaseTicksToNanos =
                            (60000000000.0 / (currentBpm.get() * currentSpeed.get() * resolution));
                    newIndex++;
                }

                chaseNanos += (long) ((target - chaseLastTick) * chaseTicksToNanos);

                // 4. Resume playback from the new position

                lastTick = target;
                eventIndex = newIndex;
                elapsedNanos = chaseNanos;
                currentMicroseconds.set(elapsedNanos / 1000);

                // Reset timing reference after seek
                ticksToNanos =
                        (60000000000.0 / (currentBpm.get() * currentSpeed.get() * resolution));
                startTimeNanos = System.nanoTime() - elapsedNanos;
                try
                {
                    provider.onPlaybackStarted();
                }
                catch (Exception ignored)
                {
                    /* Safe to ignore: optional listener */ }
                continue;
            }

            if (eventIndex >= sortedEvents.size())
            {
                if (!endReached)
                {
                    endReached = true;
                    isPaused.set(true);
                    currentMicroseconds.set(getTotalMicroseconds());
                    listeners.forEach(l -> l.onTick(currentMicroseconds.get()));
                    provider.panic(); // Silence the output
                }

                // Wait for seek or quit
                while (isPlaying.get() && seekTarget.get() == -1
                        && endStatus.get() == PlaybackStatus.FINISHED)
                {
                    Thread.sleep(50);
                    // If the user presses next/prev, the UI might change endStatus.get() and set
                    // isPlaying.get()=false.
                    // But actually the UI calls next() which sets endStatus.get() = NEXT,
                    // isPlaying.get() = false.
                }

                if (seekTarget.get() != -1)
                {
                    endReached = false; // Reset so we can seek backward and play again
                    continue;
                }

                // If we break out, it means isPlaying.get() became false or endStatus.get() changed
                break;
            }

            while (isPaused.get() && isPlaying.get())
            {
                Thread.sleep(50); // Hold the playback thread
                // If user seeks while paused, break out to let the seek logic run
                if (seekTarget.get() != -1) break;
                // Keep pushing the startTime forward so we don't instantly "catch up"
                // when unpaused!
                startTimeNanos += 50_000_000;
            }

            var event = sortedEvents.get(eventIndex);
            long tick = event.getTick();

            if (tick > lastTick)
            {
                elapsedNanos += (long) ((tick - lastTick) * ticksToNanos);
                long targetNanos = startTimeNanos + elapsedNanos;

                // High-resolution delay. Cap each sleep at 50ms so seek/stop requests
                // are detected within 50ms regardless of the gap between MIDI events.
                long currentNanos = System.nanoTime();
                while (currentNanos < targetNanos)
                {
                    if (seekTarget.get() != -1 || !isPlaying.get()) break;
                    long remainingMs = (targetNanos - currentNanos) / 1_000_000L;
                    if (remainingMs > 1)
                    {
                        Thread.sleep(min(remainingMs - 1, 50));
                    }
                    else
                    {
                        Thread.onSpinWait(); // Spin-wait for the last millisecond for
                                             // accuracy
                    }
                    currentNanos = System.nanoTime();
                }
            }

            // If seek or stop was triggered mid-wait, skip this event and re-check at
            // loop top.
            if (seekTarget.get() != -1 || !isPlaying.get()) continue;

            processEvent(event);
            lastTick = tick;

            currentMicroseconds.set(elapsedNanos / 1000);

            long finalMicros = currentMicroseconds.get();
            listeners.forEach(l -> l.onTick(finalMicros));

            eventIndex++;

            // Recalculate timing ratio if BPM changed during processEvent
            ticksToNanos = (60000000000.0 / (currentBpm.get() * currentSpeed.get() * resolution));
        }

        // Force broadcast 100% completion state before natural exit
        if (isPlaying.get() && endStatus.get() == PlaybackStatus.FINISHED)
        {
            currentMicroseconds.set(getTotalMicroseconds());
            listeners.forEach(l -> l.onTick(currentMicroseconds.get()));
            try
            {
                Thread.sleep(20);
            }
            catch (Exception ignored)
            { /* Allow UI to render 100% frame */
            }
        }
    }

    private void processChaseEvent(MidiEvent event)
    {
        if (MidirajaCommand.SHUTTING_DOWN) return;
        var msg = event.getMessage();
        var raw = msg.getMessage();
        int status = raw[0] & 0xFF;

        // Meta Tempo
        if (status == 0xFF && raw.length >= 6 && (raw[1] & 0xFF) == 0x51)
        {
            int mspqn = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            if (mspqn > 0)
            {
                currentBpm.set(60000000.0f / mspqn);
                @SuppressWarnings("NullAway")
                float bpm = currentBpm.get();
                listeners.forEach(l -> l.onTempoChanged(bpm));
            }
            return;
        }

        // Forward SysEx during chase (e.g. MT-32 initialization at song start)
        if (status == 0xF0)
        {
            try
            {
                pipelineRoot.sendMessage(raw);
            }
            catch (Exception ignored)
            {
                /* Ignore */
            }
            return;
        }

        if (status < 0xF0)
        {
            int cmd = status & 0xF0;
            int ch = status & 0x0F;
            // CHASE ONLY: Program Change(0xC0), Control Change(0xB0), Pitch Bend(0xE0),
            // Channel Pressure(0xD0)
            if (cmd == 0xC0 || cmd == 0xB0 || cmd == 0xE0 || cmd == 0xD0)
            {
                if (cmd == 0xC0 && raw.length >= 2)
                {
                    channelPrograms[ch] = raw[1] & 0xFF;
                }
                try
                {
                    pipelineRoot.sendMessage(raw);
                }
                catch (Exception ignored)
                {
                    /* Ignore */
                }
            }
        }
    }

    private void processEvent(MidiEvent event)
    {
        if (MidirajaCommand.SHUTTING_DOWN) return;
        var msg = event.getMessage();



        var raw = msg.getMessage();
        int status = raw[0] & 0xFF;

        if (status == 0xFF && raw.length >= 6 && (raw[1] & 0xFF) == 0x51)
        {
            int mspqn = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            if (mspqn > 0)
            {
                currentBpm.set(60000000.0f / mspqn);
                @SuppressWarnings("NullAway")
                float bpm = currentBpm.get();
                listeners.forEach(l -> l.onTempoChanged(bpm));
            }
            return;
        }

        // Forward SysEx to the synthesizer (e.g. MT-32 patch/channel setup
        // messages)
        if (status == 0xF0)
        {
            try
            {
                pipelineRoot.sendMessage(raw);
            }
            catch (Exception e)
            {
                // ignore
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



            if (cmd == 0xB0 && raw.length >= 3 && raw[1] == 7)
            {
                // REMOVED
                // REMOVED
            }

            if (cmd == 0x90 && raw.length >= 3 && (raw[2] & 0xFF) > 0)
            {
                final int velocity = raw[2] & 0xFF;
                final int channel = ch;
                final long latencyNanos = provider.getAudioLatencyNanos();
                if (latencyNanos > 0)
                {
                    var unused = notificationScheduler.schedule(() -> {
                        channelLevels[channel] = max(channelLevels[channel], velocity / 127.0);
                        listeners.forEach(l -> l.onChannelActivity(channel, velocity));
                    }, latencyNanos, TimeUnit.NANOSECONDS);
                }
                else
                {
                    channelLevels[ch] = max(channelLevels[ch], velocity / 127.0);
                    listeners.forEach(l -> l.onChannelActivity(ch, velocity));
                }
            }

            try
            {
                pipelineRoot.sendMessage(raw);
            }
            catch (Exception e)
            {
                // err.println("MIDI Error: " + e.getMessage());
            }
        }
    }

    // --- Engine API (For UI and External Control) ---

    public PlaylistContext getContext()
    {
        return context;
    }

    public Sequence getSequence()
    {
        return sequence;
    }

    public long getCurrentMicroseconds()
    {
        return currentMicroseconds.get();
    }

    public long getTotalMicroseconds()
    {
        return sequence.getMicrosecondLength();
    }

    public double[] getChannelLevels()
    {
        return channelLevels;
    }

    public int[] getChannelPrograms()
    {
        return channelPrograms;
    }

    @SuppressWarnings("NullAway")
    public float getCurrentBpm()
    {
        return currentBpm.get();
    }

    @SuppressWarnings("NullAway")
    public double getCurrentSpeed()
    {
        return currentSpeed.get();
    }

    public int getCurrentTranspose()
    {
        return transposeFilter.getSemitones();
    }

    public double getVolumeScale()
    {
        return provider.outputGain()
                .map(g -> (double) g.getVolumeScale())
                .orElseGet(() -> volumeFilter.getVolumeScale());
    }

    public boolean isPlaying()
    {
        return isPlaying.get();
    }

    public void requestStop(PlaybackStatus status)
    {
        this.isPlaying.set(false);
        this.endStatus.set(status);
    }

    public void adjustVolume(double delta)
    {
        var og = provider.outputGain();
        if (og.isPresent())
        {
            double newScale = max(0.0, min(1.5, og.get().getVolumeScale() + delta));
            og.get().setVolumeScale((float) newScale);
        }
        else
        {
            volumeFilter.adjust(delta);
            for (int ch = 0; ch < 16; ch++)
            {
                byte[] msg = new byte[] {(byte) (0xB0 | ch), 7, (byte) 100};
                try
                {
                    pipelineRoot.sendMessage(msg);
                }
                catch (Exception ignored)
                { /* Ignore */
                }
            }
        }
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public void adjustSpeed(double delta)
    {
        Double cs = currentSpeed.get();
        currentSpeed.set(max(0.5, min(2.0, (cs != null ? cs : 1.0) + delta)));
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public void setBookmarked(boolean bookmarked)
    {
        this.bookmarked = bookmarked;
    }

    public boolean isBookmarked()
    {
        return bookmarked;
    }

    public void setBookmarkCallback(java.util.function.Consumer<Boolean> callback)
    {
        this.bookmarkCallback = callback;
    }

    public void fireBookmark()
    {
        bookmarked = !bookmarked;
        var cb = bookmarkCallback;
        if (cb != null) cb.accept(bookmarked);
        listeners.forEach(l -> l.onBookmarkChanged(bookmarked));
    }

    public void togglePause()
    {
        isPaused.set(!isPaused.get());
        if (isPaused.get())
        {
            try
            {
                provider.panic();
            }
            catch (Exception ignored)
            { /* Ignore */
            }
        }
        else
        {
            // Wake up audio engine ring buffers after a long pause
            try
            {
                provider.onPlaybackStarted();
            }
            catch (Exception ignored)
            {
                /* Safe to ignore: optional listener */ }
        }
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public boolean isPaused()
    {
        return isPaused.get();
    }

    public synchronized void adjustTranspose(int delta)
    {
        transposeFilter.adjust(delta);
        try
        {
            provider.panic();
        }
        catch (Exception ignored)
        { /* Ignore */
        }
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public void seekRelative(long microsecondsDelta)
    {
        if (seekTarget.get() == -1)
        {
            seekTarget.set(
                    getTickForTime(max(0, currentMicroseconds.get() + microsecondsDelta)));
        }
    }

    public void decayChannelLevels(double decayAmount)
    {
        for (int i = 0; i < 16; i++)
        {
            channelLevels[i] = max(0, channelLevels[i] - decayAmount);
        }
    }
}
