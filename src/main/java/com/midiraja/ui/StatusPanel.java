/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaylistContext;
import org.jspecify.annotations.Nullable;

/**
 * Responsive status panel showing tempo, progress, transpose, and volume.
 */
public class StatusPanel implements Panel
{
    private LayoutConstraints constraints = new LayoutConstraints(80, 1, false, false);
    private long currentMicros = 0;
    private long totalMicros = 0;
    private float bpm = 120.0f;
    private double speed = 1.0;
    private double volumeScale = 1.0;
    private int transpose = 0;
    private boolean isPaused = false;
    @Nullable private PlaylistContext context;

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds)
    {
        this.constraints = bounds;
    }

    @Override
    public void onPlaybackStateChanged() {} // Handled by polling properties for now, or we can add specific setters

    public void updateState(long currentMicros, long totalMicros, float bpm, double speed, double volumeScale, int transpose, boolean isPaused, PlaylistContext context) {
        this.isPaused = isPaused;
        this.currentMicros = currentMicros;
        this.totalMicros = totalMicros;
        this.bpm = bpm;
        this.speed = speed;
        this.volumeScale = volumeScale;
        this.transpose = transpose;
        this.context = context;
    }

    @Override
    public void onTick(long currentMicroseconds)
    {
        this.currentMicros = currentMicroseconds;
    }

    @Override
    public void onTempoChanged(float bpm)
    {
        this.bpm = bpm;
    }

    @Override
    public void onChannelActivity(int channel, int velocity) {}

    private String formatTime(long microseconds, boolean includeHours)
    {
        long totalSeconds = microseconds / 1000000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (includeHours) return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void render(StringBuilder sb)
    {
        if (constraints.height() <= 0) return;
        boolean incHrs = (totalMicros / 1000000) >= 3600;
        String totStr = formatTime(totalMicros, incHrs);
        String curStr = formatTime(currentMicros, incHrs);

        int percent = (int) (totalMicros > 0 ? (currentMicros * 100 / totalMicros) : 0);
        percent = Math.min(100, Math.max(0, percent));
        
        int barWidth = Math.max(10, constraints.width() - 40);
        int filled = (int) ((percent / 100.0) * barWidth);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barWidth; i++) {
            if (i < filled) bar.append("=");
            else if (i == filled) bar.append(">");
            else bar.append("-");
        }
        bar.append("]");

        String pauseIndicator = isPaused ? "\033[1;33m[PAUSED]\033[0m " : "";
        if (constraints.height() == 1) {
            String s = String.format("    %s%s / %s %s %3d%%  Vol:%d%% Spd:%.1fx Tr:%+d", pauseIndicator, curStr, totStr, bar, percent, (int) (volumeScale * 100), speed, transpose);
            sb.append(truncate(s, constraints.width())).append("\n");
        } else if (constraints.height() >= 2 && constraints.height() <= 4) {
            String line1 = String.format("    Time: %s%s / %s  %s  %3d%%", pauseIndicator, curStr, totStr, bar, percent);
            String line2 = String.format("    Tempo: %3.0f BPM (%.1fx) | Vol: %d%% | Trans: %+d", bpm, speed, (int) (volumeScale * 100), transpose);
            sb.append(truncate(line1, constraints.width() + (isPaused ? 11 : 0))).append("\n"); // +11 for ANSI escape code length
            sb.append(truncate(line2, constraints.width())).append("\n");
            for (int i = 2; i < constraints.height(); i++) sb.append("\n");
        } else {
            sb.append(String.format("    Tempo:     %3.0f BPM  (Speed: %3.1fx)\n", bpm, speed));
            sb.append(String.format("    Time:      %s%s / %s  %s  %3d%%\n", pauseIndicator, curStr, totStr, bar, percent));
            sb.append(String.format("    Transpose: %+d\n", transpose));
            sb.append(String.format("    Volume:    %d%%\n", (int) (volumeScale * 100)));
            String portInfo = context != null ? String.format("[%d] %s", context.targetPort().index(), context.targetPort().name()) : "Unknown";
            sb.append(String.format("    Port:      %s\n", portInfo));
        }
    }
}
