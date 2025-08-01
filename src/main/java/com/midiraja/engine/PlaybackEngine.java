package com.midiraja.engine;

import com.midiraja.io.TerminalIO;
import com.midiraja.midi.MidiOutProvider;

import javax.sound.midi.*;
import java.io.IOException;
import java.util.*;

public class PlaybackEngine {
    private final Sequence sequence;
    private final MidiOutProvider provider;
    private final TerminalIO terminalIO;
    
    private volatile long currentTick = 0;
    private volatile float currentBpm = 120.0f;
    private volatile double volumeScale = 1.0;
    private volatile boolean isPlaying = false;
    
    private final double[] channelLevels = new double[16];
    private final List<MidiEvent> sortedEvents;
    private final int resolution;

    public PlaybackEngine(Sequence sequence, MidiOutProvider provider, TerminalIO terminalIO, int initialVolumePercent) {
        this.sequence = sequence;
        this.provider = provider;
        this.terminalIO = terminalIO;
        this.volumeScale = initialVolumePercent / 100.0;
        this.resolution = sequence.getResolution();
        
        List<MidiEvent> events = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) events.add(track.get(i));
        }
        events.sort(Comparator.comparingLong(MidiEvent::getTick));
        this.sortedEvents = Collections.unmodifiableList(events);
    }

    public void start() throws Exception {
        isPlaying = true;
        
        // Start UI Thread (30 FPS)
        Thread uiThread = new Thread(this::uiLoop);
        uiThread.setDaemon(true);
        uiThread.start();

        // Start Input Thread (Async IoC)
        Thread inputThread = new Thread(this::inputLoop);
        inputThread.setDaemon(true);
        inputThread.start();

        playLoop();
        
        isPlaying = false;
        uiThread.join(500);
    }

    private void playLoop() throws Exception {
        long lastTick = 0;
        int eventIndex = 0;

        while (isPlaying && eventIndex < sortedEvents.size()) {
            MidiEvent event = sortedEvents.get(eventIndex);
            long tick = event.getTick();
            
            // Seeking check: If currentTick was jumped externally
            if (Math.abs(currentTick - lastTick) > 10) { // Allow small jitter, but detect large jumps
                eventIndex = findEventIndexAt(currentTick);
                lastTick = currentTick;
                // Note: Chasing logic will be added in Phase 3
                continue;
            }

            if (tick > lastTick) {
                long sleepMs = (long) ((tick - lastTick) * (60000.0 / (currentBpm * resolution)));
                if (sleepMs > 0) Thread.sleep(sleepMs);
            }

            processEvent(event);
            lastTick = tick;
            currentTick = tick;
            eventIndex++;
        }
    }

    private int findEventIndexAt(long tick) {
        for (int i = 0; i < sortedEvents.size(); i++) {
            if (sortedEvents.get(i).getTick() >= tick) return i;
        }
        return sortedEvents.size();
    }

    private void processEvent(MidiEvent event) {
        MidiMessage msg = event.getMessage();
        byte[] raw = msg.getMessage();
        int status = raw[0] & 0xFF;

        if (status == 0xFF && raw.length >= 6 && (raw[1] & 0xFF) == 0x51) {
            int mspqn = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
            currentBpm = 60000000.0f / mspqn;
            return;
        }

        if (status < 0xF0) {
            int cmd = status & 0xF0;
            int ch = status & 0x0F;

            if (cmd == 0xB0 && raw.length >= 3 && raw[1] == 7) {
                int vol = (int) ((raw[2] & 0xFF) * volumeScale);
                raw[2] = (byte) Math.max(0, Math.min(127, vol));
            }

            if (cmd == 0x90 && raw.length >= 3 && (raw[2] & 0xFF) > 0) {
                channelLevels[ch] = Math.max(channelLevels[ch], (raw[2] & 0xFF) / 127.0);
            }

            try {
                provider.sendMessage(raw);
            } catch (Exception ignored) {}
        }
    }

    private void inputLoop() {
        try {
            while (isPlaying) {
                TerminalIO.TerminalKey key = terminalIO.readKey();
                switch (key) {
                    case VOLUME_UP:
                        volumeScale = Math.min(1.0, volumeScale + 0.05);
                        break;
                    case VOLUME_DOWN:
                        volumeScale = Math.max(0.0, volumeScale - 0.05);
                        break;
                    case SEEK_FORWARD:
                        currentTick += (long)resolution * 4;
                        break;
                    case SEEK_BACKWARD:
                        currentTick = Math.max(0, currentTick - (long)resolution * 4);
                        break;
                    case QUIT:
                        isPlaying = false;
                        break;
                }
            }
        } catch (IOException ignored) {}
    }

    private void uiLoop() {
        String[] blocks = {" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█"};
        while (isPlaying) {
            StringBuilder sb = new StringBuilder("\rVol:[");
            for (int i = 0; i < 16; i++) {
                int lv = (int) Math.round(channelLevels[i] * 8);
                sb.append(blocks[Math.max(0, Math.min(8, lv))]);
                channelLevels[i] = Math.max(0, channelLevels[i] - 0.1);
            }
            sb.append("] ");
            double pct = sequence.getTickLength() > 0 ? (double) currentTick / sequence.getTickLength() : 0;
            sb.append(String.format("%3d%% (BPM: %5.1f, Vol: %3d%%) ", 
                (int)(pct*100), currentBpm, (int)(volumeScale*100)));
            terminalIO.print(sb.toString());
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        terminalIO.println("");
    }
}