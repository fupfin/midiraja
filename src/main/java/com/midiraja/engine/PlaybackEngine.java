/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.engine;

import java.util.Optional;

import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;

import javax.sound.midi.*;
import java.io.IOException;
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
    private final PlaylistContext context;
    private final int[] channelPrograms = new int[16];

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

    /**
     * Commences playback. The method blocks until the track finishes or is interrupted. Guaranteed
     * to tear down virtual threads and silence notes upon exit.
     *
     * @return the terminal state indicating what the user requested next (e.g., NEXT, QUIT_ALL)
     */
    @SuppressWarnings({"ThreadPriorityCheck", "NonAtomicVolatileUpdate"})
    public PlaybackStatus start() throws Exception
    {
        isPlaying = true;
        endStatus = PlaybackStatus.FINISHED;

        try (var scope = StructuredTaskScope.open())
        {
            scope.fork(() -> {
                uiLoop();
                return Boolean.TRUE;
            });
            scope.fork(() -> {
                inputLoop();
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
            currentTick = tick;
            currentMicroseconds = elapsedNanos / 1000;
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
                channelLevels[ch] = Math.max(channelLevels[ch], (raw[2] & 0xFF) / 127.0);
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

    private void inputLoop()
    {
        var terminalIO = TerminalIO.CONTEXT.get();
        try
        {
            while (isPlaying)
            {
                var key = terminalIO.readKey();
                switch (key)
                {
                    case VOLUME_UP ->
                    {
                        volumeScale = Math.min(1.0, volumeScale + 0.05);
                        applyVolumeInstantly();
                    }
                    case VOLUME_DOWN ->
                    {
                        volumeScale = Math.max(0.0, volumeScale - 0.05);
                        applyVolumeInstantly();
                    }
                    case SPEED_UP -> currentSpeed = Math.min(2.0, currentSpeed + 0.1);
                    case SPEED_DOWN -> currentSpeed = Math.max(0.5, currentSpeed - 0.1);
                    case TRANSPOSE_UP ->
                    {
                        currentTranspose++;
                        provider.panic();
                    }
                    case TRANSPOSE_DOWN ->
                    {
                        currentTranspose--;
                        provider.panic();
                    }
                    case SEEK_FORWARD ->
                    {
                        if (seekTarget == -1)
                        { // Avoid overlapping seeks
                            seekTarget = getTickForTime(currentMicroseconds + 10000000L); // +10 sec
                        }
                    }
                    case SEEK_BACKWARD ->
                    {
                        if (seekTarget == -1)
                        {
                            seekTarget =
                                    getTickForTime(Math.max(0, currentMicroseconds - 10000000L)); // -10 sec
                        }
                    }
                    case QUIT ->
                    {
                        isPlaying = false;
                        endStatus = PlaybackStatus.QUIT_ALL;
                    }
                    case NEXT_TRACK ->
                    {
                        isPlaying = false;
                        endStatus = PlaybackStatus.NEXT;
                    }
                    case PREV_TRACK ->
                    {
                        isPlaying = false;
                        endStatus = PlaybackStatus.PREVIOUS;
                    }
                    case NONE ->
                    {
                        // non-blocking
                        Thread.sleep(50);
                    }
                }
            }
        }
        catch (IOException | InterruptedException _)
        {
            isPlaying = false;
        }
    }

    private void applyVolumeInstantly()
    {
        for (int ch = 0; ch < 16; ch++)
        {
            byte[] msg = new byte[] {(byte) (0xB0 | ch), 7, (byte) (100 * volumeScale)};
            try
            {
                provider.sendMessage(msg);
            }
            catch (Exception _)
            {
            }
        }
    }

    private static final String[] GM_FAMILIES = {
            "Piano", "Chrom Perc", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass",
            "Reed", "Pipe", "Synth Lead", "Synth Pad", "Synth FX", "Ethnic", "Percussive", "SFX"
    };

    private String getChannelName(int ch)
    {
        if (ch == 9) return "Drums";
        int family = channelPrograms[ch] / 8;
        if (family >= 0 && family < GM_FAMILIES.length) return GM_FAMILIES[family];
        return "Unknown";
    }

    private String truncate(String text, int maxLength)
    {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private void uiLoop()
    {
        var term = TerminalIO.CONTEXT.get();
        if (!term.isInteractive())
        {
            term.println("Playing (Interactive UI disabled)...");
            return;
        }

        long totalMicroseconds = sequence.getMicrosecondLength();
        boolean includeHours = (totalMicroseconds / 1000000) >= 3600;
        String totalTimeStr = formatTime(totalMicroseconds, includeHours);

        final int MIN_WIDTH = 80;
        final int MIN_HEIGHT = 28;

        try
        {
            while (isPlaying)
            {
                int termWidth = term.getWidth();
                int termHeight = term.getHeight();

                StringBuilder sb = new StringBuilder();
                sb.append("\033[H"); // Cursor Home

                if (termWidth < MIN_WIDTH || termHeight < MIN_HEIGHT)
                {
                    sb.append("\033[J"); // Clear screen
                    sb.append(String.format("Terminal too small. Minimum size: %dx%d. Current: %dx%d\n", MIN_WIDTH, MIN_HEIGHT, termWidth, termHeight));
                    sb.append("Please resize the terminal window.\n");
                    term.print(sb.toString());
                    Thread.sleep(500);
                    continue;
                }

                // Decay channel levels
                for (int i = 0; i < 16; i++) {
                    channelLevels[i] = Math.max(0, channelLevels[i] - 0.05); // decay
                }

                long currentMicros = currentMicroseconds;
                String currentTimeStr = formatTime(currentMicros, includeHours);
                int percent = (int) (totalMicroseconds > 0 ? (currentMicros * 100 / totalMicroseconds) : 0);
                percent = Math.min(100, Math.max(0, percent));
                
                // Dynamic Bar Width
                int barWidth = Math.max(10, termWidth - 40); 
                int filled = (int) ((percent / 100.0) * barWidth);
                StringBuilder bar = new StringBuilder("[");
                for (int i = 0; i < barWidth; i++) {
                    if (i < filled) bar.append("=");
                    else if (i == filled) bar.append(">");
                    else bar.append("-");
                }
                bar.append("]");

                String horizontalDoubleLine = "=".repeat(termWidth);
                String horizontalSingleLine = "-".repeat(termWidth);

                sb.append(horizontalDoubleLine).append("\n");
                sb.append("  Midiraja v").append(com.midiraja.Version.VERSION).append(" - Java 25 Native MIDI Player\n");
                sb.append(horizontalDoubleLine).append("\n\n");
                
                // [NOW PLAYING] Expanded vertical spacing
                sb.append("  [NOW PLAYING]\n\n");
                String rawTitle = context.sequenceTitle() != null ? context.sequenceTitle() : context.files().get(context.currentIndex()).getName();
                String title = truncate(rawTitle, termWidth - 15);
                sb.append(String.format("    Title:     %s\n\n", title));
                sb.append(String.format("    Tempo:     %3.0f BPM  (Speed: %3.1fx)\n", currentBpm, currentSpeed));
                sb.append(String.format("    Time:      %s / %s  %s  %3d%%\n", currentTimeStr, totalTimeStr, bar, percent));
                sb.append(String.format("    Transpose: %+d\n", currentTranspose));
                sb.append(String.format("    Volume:    %d%%\n", (int)(volumeScale * 100)));
                sb.append(String.format("    Port:      [%d] %s\n\n", context.targetPort().index(), context.targetPort().name()));
                
                sb.append(horizontalSingleLine).append("\n");

                // TWO-COLUMN LAYOUT PREPARATION
                int leftColWidth = Math.max(35, termWidth / 2);
                int rightColWidth = termWidth - leftColWidth;
                
                // Header row for columns
                String leftHeader = " [MIDI CHANNELS ACTIVITY]";
                String rightHeader = " [PLAYLIST]";
                sb.append(String.format("%-" + leftColWidth + "s%s\n\n", leftHeader, rightHeader));

                // Dynamic Meter Width (fitted to left column)
                int maxMeterLength = Math.max(5, leftColWidth - 26);
                
                // Playlist logic
                int listSize = context.files().size();
                int idx = context.currentIndex();
                int availablePlaylistLines = 16; // Fix playlist height to match the 16 channels
                
                int half = availablePlaylistLines / 2;
                int startIdx = Math.max(0, idx - half);
                int endIdx = Math.min(listSize - 1, startIdx + availablePlaylistLines - 1);
                startIdx = Math.max(0, endIdx - availablePlaylistLines + 1);

                // Print the 16 rows (Left: Channels, Right: Playlist)
                for (int i = 0; i < 16; i++) {
                    // LEFT COLUMN (Channels)
                    int meterLength = (int)(channelLevels[i] * maxMeterLength);
                    String meter = "█".repeat(meterLength) + " ".repeat(maxMeterLength - meterLength);
                    String chName = getChannelName(i);
                    String leftStr = String.format("  CH %02d %-11s : %s", i + 1, "(" + chName + ")", meter);
                    
                    // Pad leftStr precisely to leftColWidth to ensure right column aligns perfectly
                    if (leftStr.length() > leftColWidth) leftStr = leftStr.substring(0, leftColWidth);
                    else leftStr = leftStr + " ".repeat(leftColWidth - leftStr.length());
                    sb.append(leftStr);

                    // RIGHT COLUMN (Playlist)
                    int pIdx = startIdx + i;
                    if (pIdx >= 0 && pIdx <= endIdx && pIdx < listSize) {
                        String marker = (pIdx == idx) ? ">" : " ";
                        String name = context.files().get(pIdx).getName();
                        String status = (pIdx == idx) ? " (Playing)" : "";
                        name = truncate(name, rightColWidth - status.length() - 8);
                        sb.append(String.format(" %s %d. %s%s", marker, pIdx + 1, name, status));
                    }
                    sb.append("\n");
                }
                sb.append("\n");

                // Add empty padding lines to push controls to the bottom
                int usedLines = 12 + 16 + 5; // Approx header + 16 rows + controls
                int paddingLines = Math.max(0, termHeight - usedLines - 2); // leave room for safety
                sb.append("\n".repeat(paddingLines));

                // Bottom Controls
                sb.append(horizontalSingleLine).append("\n");
                sb.append(" [CONTROLS]\n");
                sb.append("  [Space] Pause/Resume  |  [<] [>] Prev/Next Track  |  [+] [-] Transpose\n");
                sb.append("  [Up] [Down] Volume    |  [Q] Quit                 |\n");
                sb.append(horizontalDoubleLine).append("\n");
                
                // Clear the rest of the screen to prevent ghosting/flickering below the UI
                sb.append("\033[J");

                term.print(sb.toString());
                Thread.sleep(50); // 20 FPS
            }
        }
        catch (InterruptedException _)
        {
            // normal exit
        }
    }

    private String formatTime(long microseconds, boolean includeHours)
    {
        long totalSeconds = microseconds / 1000000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (includeHours)
        {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        else
        {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
}
