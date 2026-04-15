/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import static java.lang.Math.*;
import static java.util.Locale.ROOT;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.sound.midi.*;

import com.fupfin.midiraja.dsp.SpectrumAnalyzerFilter;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.ui.PlaybackEventListener;
import com.fupfin.midiraja.ui.PlaybackUI;

/**
 * Orchestrates real-time MIDI playback, managing timing, user input, and UI updates. Utilizes
 * Java's Structured Concurrency to safely isolate asynchronous tasks.
 */
public class MidiPlaybackEngine implements PlaybackEngine
{
    private final Sequence sequence;
    private final MidiOutProvider provider;
    private final PlaybackPipeline pipeline;
    private final BooleanSupplier isShuttingDown;

    private final AtomicLong currentMicroseconds = new AtomicLong(0);
    private final AtomicLong seekTarget = new AtomicLong(-1);
    private final AtomicReference<Float> currentBpm = new AtomicReference<>(120.0f);
    private final AtomicReference<Double> currentSpeed = new AtomicReference<>(1.0);

    private static final int STARTUP_DELAY_MS = 500; // Skip quickly through tracks without noisy init
    private static final int STARTUP_POLL_MS = 10; // Poll interval during startup delay
    private static final int PLAYBACK_POLL_MS = 50; // Main playback loop sleep interval
    private static final int END_OF_TRACK_MS = 20; // Hold after last event so UI renders final frame
    private static final int RESET_SETTLE_MS = 50; // Give hardware synth time to process reset SysEx

    // Named SysEx reset payloads — avoids inline byte literals in the send method.
    // mt-32 is an alias for mt32 (both spellings accepted by the --reset flag).
    private static final Map<String, byte[]> SYSEX_RESETS = Map.of(
            "gm", new byte[] { (byte) 0xF0, 0x7E, 0x7F, 0x09, 0x01, (byte) 0xF7 },
            "gm2", new byte[] { (byte) 0xF0, 0x7E, 0x7F, 0x09, 0x03, (byte) 0xF7 },
            "gs", new byte[] { (byte) 0xF0, 0x41, 0x10, 0x42, 0x12, 0x40, 0x00, 0x7F, 0x00, 0x41,
                    (byte) 0xF7 },
            "xg", new byte[] { (byte) 0xF0, 0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, (byte) 0xF7 },
            "mt32", new byte[] { (byte) 0xF0, 0x41, 0x10, 0x16, 0x12, 0x7F, 0x00, 0x00, 0x00, 0x01,
                    (byte) 0xF7 },
            "mt-32", new byte[] { (byte) 0xF0, 0x41, 0x10, 0x16, 0x12, 0x7F, 0x00, 0x00, 0x00, 0x01,
                    (byte) 0xF7 });

    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    private volatile boolean bookmarked = false;
    @SuppressWarnings("NullAway")
    private volatile java.util.function.Consumer<Boolean> bookmarkCallback = null;

    private String filterDescription = "";
    private String portSuffix = "";

    @SuppressWarnings("NullAway")
    private volatile SpectrumAnalyzerFilter spectrumFilter = null;

    private volatile boolean loopEnabled = false;
    private volatile boolean shuffleEnabled = false;
    @SuppressWarnings("NullAway")
    private volatile java.util.function.Consumer<Boolean> shuffleCallback = null;

    private final AtomicBoolean holdAtEnd = new AtomicBoolean(false);
    private final AtomicReference<PlaybackEngine.PlaybackStatus> endStatus = new AtomicReference<>(
            PlaybackEngine.PlaybackStatus.FINISHED);
    private final AtomicBoolean playbackActuallyStarted = new AtomicBoolean(false);
    private Optional<String> initialResetType = Optional.empty();
    private final MidiClock clock;

    public void setInitiallyPaused()
    {
        this.isPaused.set(true);
    }

    public void setIgnoreSysex(boolean ignoreSysex)
    {
        pipeline.setIgnoreSysex(ignoreSysex);
    }

    public void setInitialResetType(Optional<String> resetType)
    {
        this.initialResetType = resetType;
    }

    public void setHoldAtEnd(boolean hold)
    {
        this.holdAtEnd.set(hold);
    }

    public void toggleLoop()
    {
        loopEnabled = !loopEnabled;
    }

    public boolean isLoopEnabled()
    {
        return loopEnabled;
    }

    public void toggleShuffle()
    {
        shuffleEnabled = !shuffleEnabled;
        var cb = shuffleCallback;
        if (cb != null)
            cb.accept(shuffleEnabled);
    }

    public boolean isShuffleEnabled()
    {
        return shuffleEnabled;
    }

    public void setShuffleCallback(java.util.function.Consumer<Boolean> cb)
    {
        shuffleCallback = cb;
    }

    public void setSpectrumFilter(SpectrumAnalyzerFilter filter)
    {
        this.spectrumFilter = filter;
    }

    private void fireSpectrumLevels()
    {
        if (spectrumFilter == null)
            return;
        float[] lv = spectrumFilter.getLevels();
        listeners.forEach(l -> l.onSpectrumUpdate(lv));
    }

    public void setFilterDescription(String desc)
    {
        this.filterDescription = desc;
    }

    public String getFilterDescription()
    {
        return filterDescription;
    }

    public void setPortSuffix(String suffix)
    {
        this.portSuffix = suffix;
    }

    public String getPortSuffix()
    {
        return portSuffix;
    }

    public void firePlayOrderChanged(PlaylistContext ctx)
    {
        for (var listener : listeners)
            listener.onPlayOrderChanged(ctx);
    }

    private final List<MidiEvent> sortedEvents;
    private final int resolution;
    private final PlaylistContext context;
    private final int[] channelPrograms = new int[16];

    private final List<PlaybackEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService notificationScheduler = Executors
            .newSingleThreadScheduledExecutor(this::newNotifyThread);

    private Thread newNotifyThread(Runnable r)
    {
        Thread t = new Thread(r, "midi-notify");
        t.setDaemon(true);
        return t;
    }

    public MidiPlaybackEngine(Sequence sequence, MidiOutProvider provider, PlaylistContext context,
            PlaybackPipeline pipeline, BooleanSupplier isShuttingDown,
            double initialSpeed, Optional<Long> startTimeMicroseconds)
    {
        this(sequence, provider, context, pipeline, isShuttingDown,
                initialSpeed, startTimeMicroseconds, MidiClock.SYSTEM);
    }

    public MidiPlaybackEngine(Sequence sequence, MidiOutProvider provider, PlaylistContext context,
            PlaybackPipeline pipeline, BooleanSupplier isShuttingDown,
            double initialSpeed, Optional<Long> startTimeMicroseconds, MidiClock clock)
    {
        this.clock = clock;
        this.sequence = sequence;
        this.provider = provider;
        this.pipeline = pipeline;
        this.isShuttingDown = isShuttingDown;
        this.currentSpeed.set(initialSpeed);
        this.resolution = sequence.getResolution();
        this.context = context;
        this.loopEnabled = context.loop();
        this.shuffleEnabled = context.shuffle();
        this.sortedEvents = Arrays.stream(sequence.getTracks())
                .flatMap(track -> IntStream.range(0, track.size()).mapToObj(track::get))
                .sorted(Comparator.comparingLong(MidiEvent::getTick))
                .toList();
        startTimeMicroseconds.ifPresent(us -> this.seekTarget.set(getTickForTime(us)));
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
    @SuppressWarnings({ "ThreadPriorityCheck", "NonAtomicVolatileUpdate" })
    public PlaybackEngine.PlaybackStatus start(PlaybackUI ui) throws Exception
    {
        isPlaying.set(true);
        playbackActuallyStarted.set(false);
        endStatus.set(PlaybackEngine.PlaybackStatus.FINISHED);

        try (var scope = StructuredTaskScope.open())
        {
            scope.fork(() -> runRenderLoopTask(ui));
            scope.fork(() -> runInputLoopTask(ui));

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
        PlaybackEngine.PlaybackStatus status = endStatus.get();
        return status != null ? status : PlaybackEngine.PlaybackStatus.FINISHED;
    }

    private Boolean runRenderLoopTask(PlaybackUI ui)
    {
        ui.runRenderLoop(this);
        return Boolean.TRUE;
    }

    private Boolean runInputLoopTask(PlaybackUI ui)
    {
        ui.runInputLoop(this);
        return Boolean.TRUE;
    }

    @SuppressWarnings("EmptyCatch")
    private void sendInitialReset()
    {
        if (initialResetType.isEmpty())
            return;
        String type = initialResetType.get().trim().toLowerCase(ROOT);
        byte[] payload = resolveResetPayload(type);
        if (payload.length > 0)
        {
            try
            {
                pipeline.sendMessage(payload);
                clock.sleepMillis(RESET_SETTLE_MS); // Give hardware synth time to process reset
            }
            catch (Exception e)
            {}
        }
    }

    private static byte[] resolveResetPayload(String type)
    {
        byte[] known = SYSEX_RESETS.get(type);
        if (known != null)
            return known;
        if (type.matches("^[0-9a-fA-F]+$") && type.length() % 2 == 0)
        {
            byte[] hex = new byte[type.length() / 2];
            for (int i = 0; i < hex.length; i++)
                hex[i] = (byte) Integer.parseInt(type.substring(i * 2, i * 2 + 2), 16);
            return hex;
        }
        return new byte[0];
    }

    /** Carries updated playback position state out of {@link #performSeekChase}. */
    private record SeekState(int eventIndex, long lastTick, long elapsedNanos, long startTimeNanos,
            double ticksToNanos)
    {
    }

    private void playLoop() throws Exception
    {
        if (!awaitStartupDelay())
            return;

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
        long startTimeNanos = clock.nanoTime();
        @SuppressWarnings("NullAway")
        double ticksToNanos = tickDurationNanos(currentBpm.get(), currentSpeed.get(), resolution);

        boolean endReached = false;
        while (isPlaying.get() && (eventIndex < sortedEvents.size() || holdAtEnd.get()))
        {
            if (seekTarget.get() != -1)
            {
                long target = seekTarget.getAndSet(-1);
                SeekState s = performSeekChase(target);
                lastTick = s.lastTick();
                eventIndex = s.eventIndex();
                elapsedNanos = s.elapsedNanos();
                startTimeNanos = s.startTimeNanos();
                ticksToNanos = s.ticksToNanos();
                endReached = false;
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
                        && endStatus.get() == PlaybackEngine.PlaybackStatus.FINISHED)
                {
                    clock.sleepMillis(PLAYBACK_POLL_MS);
                }

                if (seekTarget.get() != -1)
                    continue;

                break;
            }

            while (isPaused.get() && isPlaying.get())
            {
                clock.sleepMillis(PLAYBACK_POLL_MS); // Hold the playback thread
                // If user seeks while paused, break out to let the seek logic run
                if (seekTarget.get() != -1)
                    break;
                // Keep pushing the startTime forward so we don't instantly "catch up"
                // when unpaused!
                startTimeNanos += 50_000_000;
            }

            var event = sortedEvents.get(eventIndex);
            long tick = event.getTick();

            if (tick > lastTick)
            {
                elapsedNanos += (long) ((tick - lastTick) * ticksToNanos);
                boolean interrupted = waitForEventTime(startTimeNanos + elapsedNanos, startTimeNanos);
                if (interrupted)
                    continue;
            }

            if (seekTarget.get() != -1 || !isPlaying.get())
                continue;

            processEvent(event);
            lastTick = tick;

            currentMicroseconds.set(elapsedNanos / 1000);

            long finalMicros = currentMicroseconds.get();
            listeners.forEach(l -> l.onTick(finalMicros));
            fireSpectrumLevels();

            eventIndex++;

            // Recalculate timing ratio if BPM changed during processEvent
            ticksToNanos = tickDurationNanos(currentBpm.get(), currentSpeed.get(), resolution);
        }

        // Force broadcast 100% completion state before natural exit
        if (isPlaying.get() && endStatus.get() == PlaybackEngine.PlaybackStatus.FINISHED)
        {
            currentMicroseconds.set(getTotalMicroseconds());
            listeners.forEach(l -> l.onTick(currentMicroseconds.get()));
            try
            {
                clock.sleepMillis(END_OF_TRACK_MS);
            }
            catch (Exception e)
            { /* Allow UI to render 100% frame */
            }
        }
    }

    /**
     * Waits 500ms before starting playback so rapid next/prev skips don't trigger audio init.
     *
     * @return true if playback should continue, false if cancelled during the delay
     */
    private boolean awaitStartupDelay() throws InterruptedException
    {
        long endNanos = clock.nanoTime() + STARTUP_DELAY_MS * 1_000_000L;
        while (clock.nanoTime() < endNanos)
        {
            if (!isPlaying.get())
                return false;
            clock.sleepMillis(STARTUP_POLL_MS);
        }
        return true;
    }

    /**
     * Fast-forwards silently to {@code target} tick, accumulating correct program/control state,
     * and returns the updated playback position state to resume from.
     */
    @SuppressWarnings("NullAway")
    private SeekState performSeekChase(long target) throws Exception
    {
        provider.panic(); // Silence lingering notes
        currentBpm.set(120.0f);

        int newIndex = 0;
        long chaseNanos = 0;
        long chaseLastTick = 0;
        double chaseTicksToNanos = tickDurationNanos(currentBpm.get(), currentSpeed.get(), resolution);

        for (MidiEvent ev : sortedEvents)
        {
            if (ev.getTick() >= target)
                break;
            long t = ev.getTick();
            chaseNanos += (long) ((t - chaseLastTick) * chaseTicksToNanos);
            chaseLastTick = t;
            processChaseEvent(ev);
            chaseTicksToNanos = tickDurationNanos(currentBpm.get(), currentSpeed.get(), resolution);
            newIndex++;
        }

        chaseNanos += (long) ((target - chaseLastTick) * chaseTicksToNanos);
        currentMicroseconds.set(chaseNanos / 1000);

        try
        {
            provider.onPlaybackStarted();
        }
        catch (Exception e)
        {
            /* Safe to ignore: optional listener */
        }

        double ticksToNanos = tickDurationNanos(currentBpm.get(), currentSpeed.get(), resolution);
        long startTimeNanos = clock.nanoTime() - chaseNanos;
        return new SeekState(newIndex, target, chaseNanos, startTimeNanos, ticksToNanos);
    }

    /**
     * High-resolution wait until {@code targetNanos}. Caps each sleep at 50ms so seek/stop
     * requests are detected promptly, and updates the UI clock during long inter-event gaps.
     *
     * @return true if the wait was interrupted by a seek or stop request
     */
    private boolean waitForEventTime(long targetNanos, long startTimeNanos)
            throws InterruptedException
    {
        long currentNanos = clock.nanoTime();
        while (currentNanos < targetNanos)
        {
            if (seekTarget.get() != -1 || !isPlaying.get())
                return true;
            long nowMicros = (currentNanos - startTimeNanos) / 1000;
            currentMicroseconds.set(nowMicros);
            listeners.forEach(l -> l.onTick(nowMicros));
            fireSpectrumLevels();
            long remainingMs = (targetNanos - currentNanos) / 1_000_000L;
            if (remainingMs > 1)
                clock.sleepMillis(min(remainingMs - 1, PLAYBACK_POLL_MS));
            else
                clock.onSpinWait(); // Spin-wait for the last millisecond for accuracy
            currentNanos = clock.nanoTime();
        }
        return false;
    }

    private void processChaseEvent(MidiEvent event)
    {
        if (isShuttingDown.getAsBoolean())
            return;
        var raw = event.getMessage().getMessage();
        if (handleCommonEvent(raw))
            return;

        int status = raw[0] & 0xFF;
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
                    pipeline.sendMessage(raw);
                }
                catch (Exception e)
                {
                    /* Ignore */
                }
            }
        }
    }

    /**
     * Handles tempo meta and SysEx events common to processEvent and processChaseEvent.
     *
     * @return true if the event was fully handled (caller should return)
     */
    private boolean handleCommonEvent(byte[] raw)
    {
        int mspqn = extractTempoMspqn(raw);
        if (mspqn > 0)
        {
            currentBpm.set(60_000_000.0f / mspqn);
            @SuppressWarnings("NullAway")
            float bpm = currentBpm.get();
            listeners.forEach(l -> l.onTempoChanged(bpm));
            return true;
        }
        if ((raw[0] & 0xFF) == 0xF0)
        {
            try
            {
                pipeline.sendMessage(raw);
            }
            catch (Exception e)
            {
                // ignore
            }
            return true;
        }
        return false;
    }

    private void processEvent(MidiEvent event)
    {
        if (isShuttingDown.getAsBoolean())
            return;
        var raw = event.getMessage().getMessage();
        if (handleCommonEvent(raw))
            return;

        int status = raw[0] & 0xFF;
        if (status < 0xF0)
        {
            int cmd = status & 0xF0;
            int ch = status & 0x0F;

            if (cmd == 0xC0 && raw.length >= 2)
            {
                channelPrograms[ch] = raw[1] & 0xFF;
            }

            if (cmd == 0x90 && raw.length >= 3 && (raw[2] & 0xFF) > 0)
            {
                final int velocity = raw[2] & 0xFF;
                final int channel = ch;
                final long latencyNanos = provider.getAudioLatencyNanos();
                if (latencyNanos > 0)
                {
                    var unused = notificationScheduler.schedule(
                            () -> listeners.forEach(l -> l.onChannelActivity(channel, velocity)),
                            latencyNanos, TimeUnit.NANOSECONDS);
                }
                else
                {
                    listeners.forEach(l -> l.onChannelActivity(ch, velocity));
                }
            }

            try
            {
                pipeline.sendMessage(raw);
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
        return pipeline.getCurrentTranspose();
    }

    public double getVolumeScale()
    {
        return pipeline.getVolumeScale();
    }

    public boolean isPlaying()
    {
        return isPlaying.get();
    }

    public void requestStop(PlaybackEngine.PlaybackStatus status)
    {
        this.isPlaying.set(false);
        this.endStatus.set(status);
    }

    public void adjustVolume(double delta)
    {
        pipeline.adjustVolume(delta);
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
        if (cb != null)
            cb.accept(bookmarked);
        listeners.forEach(l -> l.onBookmarkChanged(bookmarked));
    }

    public void togglePause()
    {
        // Atomically flip isPaused and capture the new state in a single CAS loop,
        // so rapid key presses from different threads cannot observe a stale value.
        boolean prev;
        do
        {
            prev = isPaused.get();
        }
        while (!isPaused.compareAndSet(prev, !prev));
        boolean nowPaused = !prev;
        if (nowPaused)
        {
            try
            {
                provider.softPause();
            }
            catch (Exception e)
            {
                /* Ignore */
            }
        }
        else
        {
            // Wake up audio engine ring buffers after a long pause
            try
            {
                provider.onPlaybackStarted();
            }
            catch (Exception e)
            {
                /* Safe to ignore: optional listener */
            }
        }
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public boolean isPaused()
    {
        return isPaused.get();
    }

    public synchronized void adjustTranspose(int delta)
    {
        pipeline.adjustTranspose(delta);
        listeners.forEach(PlaybackEventListener::onPlaybackStateChanged);
    }

    public void seekRelative(long microsecondsDelta)
    {
        if (seekTarget.get() == -1)
        {
            seekTarget.set(getTickForTime(max(0, currentMicroseconds.get() + microsecondsDelta)));
        }
    }

    private static double tickDurationNanos(float bpm, double speed, int resolution)
    {
        return 60_000_000_000.0 / (bpm * speed * resolution);
    }

    /**
     * Returns the microseconds-per-quarter-note value from a MIDI Set Tempo meta-event,
     * or -1 if {@code msg} is not a tempo event or has mspqn == 0.
     */
    static int extractTempoMspqn(byte[] msg)
    {
        if (msg[0] != (byte) 0xFF || msg.length < 6 || (msg[1] & 0xFF) != 0x51)
            return -1;
        int mspqn = ((msg[3] & 0xFF) << 16) | ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        return mspqn > 0 ? mspqn : -1;
    }

    private long getTickForTime(long targetMicroseconds)
    {
        if (targetMicroseconds <= 0)
            return 0;
        long targetNanos = targetMicroseconds * 1000;
        long currentNanos = 0;
        long lastTick = 0;
        double ticksToNanos = tickDurationNanos(120.0f, 1.0, resolution);

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
            int mspqn = extractTempoMspqn(msg);
            if (mspqn > 0)
            {
                ticksToNanos = tickDurationNanos(60_000_000.0f / mspqn, 1.0, resolution);
            }
        }
        return sequence.getTickLength();
    }

}
