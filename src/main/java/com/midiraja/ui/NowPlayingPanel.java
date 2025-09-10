/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaylistContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class NowPlayingPanel implements Panel {
    private LayoutConstraints constraints = new LayoutConstraints(80, 0, false, false);
    
    private long currentMicros = 0;
    private long totalMicros = 0;
    private float bpm = 120.0f;
    private double speed = 1.0;
    private double volumeScale = 1.0;
    private int transpose = 0;
    private boolean isPaused = false;
    @Nullable private PlaylistContext context;
    private final List<String> extraMetadata = new ArrayList<>();

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds) {
        this.constraints = bounds;
    }

    public void updateState(long currentMicros, long totalMicros, float bpm, double speed, double volumeScale, int transpose, boolean isPaused, PlaylistContext context) {
        this.currentMicros = currentMicros;
        this.totalMicros = totalMicros;
        this.bpm = bpm;
        this.speed = speed;
        this.volumeScale = volumeScale;
        this.transpose = transpose;
        this.isPaused = isPaused;
        this.context = context;
    }
    
    public void setExtraMetadata(List<String> metadata) {
        this.extraMetadata.clear();
        this.extraMetadata.addAll(metadata);
    }

    @Override public void onPlaybackStateChanged() {}
    @Override public void onTick(long currentMicroseconds) { this.currentMicros = currentMicroseconds; }
    @Override public void onTempoChanged(float bpm) { this.bpm = bpm; }
    @Override public void onChannelActivity(int channel, int velocity) {}

    private String formatTime(long microseconds, boolean includeHours) {
        long totalSeconds = microseconds / 1000000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (includeHours) return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }

    private String buildProgressBar(int percent, int barWidth) {
        // barWidth passed from caller
        int filled = (int) ((percent / 100.0) * barWidth);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barWidth; i++) {
            if (i < filled - 1) bar.append("=");
            else if (i == filled - 1) bar.append(">");
            else bar.append("-");
        }
        bar.append("]");
        return bar.toString();
    }

    @Override
    public void render(StringBuilder sb) {
        if (constraints.height() <= 0 || context == null) return;
        
        String title = context.sequenceTitle() != null ? context.sequenceTitle() : "";
        String fileName = context.files().get(context.currentIndex()).getName();
        String displayTitle = title.isEmpty() ? fileName : title + " (" + fileName + ")";
        
        boolean incHrs = (totalMicros / 1000000) >= 3600;
        String totStr = formatTime(totalMicros, incHrs);
        String curStr = formatTime(currentMicros, incHrs);
        int percent = (int) (totalMicros > 0 ? (currentMicros * 100 / totalMicros) : 0);
        percent = Math.min(100, Math.max(0, percent));
        
        String pauseIndicator = isPaused ? "\033[1;33m[PAUSED]\033[0m " : "";
        int visiblePauseLen = isPaused ? 9 : 0;
        
        String timeStr = curStr + " / " + totStr;
        int timeLen = timeStr.length();
        
        // Fixed visual length without bar:
        // "    " (4)
        // "Time:     " (10)
        // " " (1)
        // "[PAUSED] " (visiblePauseLen)
        // "00:00 / 00:00" (timeLen)
        // "  " (2)
        // "  " (2)
        // "100%" (4)
        // Total = 23 + visiblePauseLen + timeLen
        // Increase safety margin by 2 to completely avoid any edge-case string overflow truncations.
        int barWidth = Math.max(10, constraints.width() - 25 - visiblePauseLen - timeLen);
        String bar = buildProgressBar(percent, barWidth);
        String portInfo = String.format("[%d] %s", context.targetPort().index(), context.targetPort().name());
        
        int h = constraints.height();
        
        // Consistent Alignment Prefix
        String p1 = "    ";
        
        // Consistent Alignment Formats (10 chars padding for label)
        String fmtTitle = "    %-10s %s";
        String fmtTime  = "    %-10s %s%s / %s  %s  %3d%%";
        String fmtVol   = "    %-10s %d%%";
        String fmtPort  = "    %-10s %s";
        String fmtTempo = "    %-10s %3.0f BPM (%.1fx)";
        String fmtTrans = "    %-10s %+d";
        
        if (h <= 2) {
            // Extreme minimum: Just Title and Time
            sb.append(String.format(fmtTitle, "Title:", truncate(displayTitle, constraints.width() - 15)));
            sb.append(truncate(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent), constraints.width())).append("\n");
        }
        else if (h == 3) {
            sb.append(String.format(fmtTitle, "Title:", truncate(displayTitle, constraints.width() - 15)));
            sb.append(truncate(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent), constraints.width()));
            
            // Pack all into 1 line
            String packed = String.format("    Vol: %d%% | Port: %s | Spd: %.1fx | Tr: %+d", 
                (int)(volumeScale * 100), portInfo, speed, transpose);
            sb.append(truncate(packed, constraints.width())).append("\n");
        } 
        else if (h == 4) {
            sb.append(String.format(fmtTitle, "Title:", truncate(displayTitle, constraints.width() - 15)));
            sb.append(truncate(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent), constraints.width()));
            
            // Pack Volume/Port and Tempo/Trans
            sb.append(truncate(String.format("    %-10s %d%% | Port: %s", "Volume:", (int)(volumeScale * 100), portInfo), constraints.width())).append("\n");
            sb.append(truncate(String.format("    %-10s %3.0f BPM (%.1fx) | Trans: %+d", "Tempo:", bpm, speed, transpose), constraints.width())).append("\n");
        }
        else if (h == 5) {
            sb.append(String.format(fmtTitle, "Title:", truncate(displayTitle, constraints.width() - 15)));
            sb.append(truncate(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent), constraints.width()));
            sb.append(String.format(fmtVol, "Volume:", (int)(volumeScale * 100)));
            sb.append(String.format(fmtPort, "Port:", truncate(portInfo, constraints.width() - 15)));
            sb.append(truncate(String.format("    %-10s %3.0f BPM (%.1fx) | Trans: %+d", "Tempo:", bpm, speed, transpose), constraints.width())).append("\n");
        }
        else {
            // h >= 6 (Fully Unpacked)
            sb.append(String.format(fmtTitle, "Title:", truncate(displayTitle, constraints.width() - 15)));
            sb.append(truncate(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent), constraints.width()));
            sb.append(String.format(fmtVol, "Volume:", (int)(volumeScale * 100)));
            sb.append(String.format(fmtPort, "Port:", truncate(portInfo, constraints.width() - 15)));
            sb.append(String.format(fmtTempo, "Tempo:", bpm, speed));
            sb.append(String.format(fmtTrans, "Transpose:", transpose));
            
            // Fill any remaining space with blank lines
            for (int i = 6; i < h; i++) {
                sb.append("\n");
            }
        }
    }
}
