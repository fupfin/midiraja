/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import static com.fupfin.midiraja.ui.UIUtils.formatTime;
import static java.lang.Math.*;

import com.fupfin.midiraja.engine.PlaylistContext;
import org.jspecify.annotations.Nullable;

public class NowPlayingPanel implements Panel
{
    private LayoutConstraints constraints = new LayoutConstraints(80, 0, false, false);

    private long currentMicros = 0;
    private long totalMicros = 0;
    private float bpm = 120.0f;
    private double speed = 1.0;
    private double volumeScale = 1.0;
    private int transpose = 0;
    private boolean isPaused = false;
    private volatile boolean bookmarked = false;
    @Nullable
    private PlaylistContext context;
    @Nullable
    private String copyright = null;

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds)
    {
        this.constraints = bounds;
    }

    public void updateState(long currentMicros, long totalMicros, float bpm, double speed,
            double volumeScale, int transpose, boolean isPaused, PlaylistContext context)
    {
        this.currentMicros = currentMicros;
        this.totalMicros = totalMicros;
        this.bpm = bpm;
        this.speed = speed;
        this.volumeScale = volumeScale;
        this.transpose = transpose;
        this.isPaused = isPaused;
        this.context = context;
    }

    public void setCopyright(@Nullable String copyright)
    {
        this.copyright = copyright;
    }

    @Override
    public void onPlaybackStateChanged()
    {}

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
    public void onChannelActivity(int channel, int velocity)
    {}

    @Override
    public void onBookmarkChanged(boolean bookmarked)
    {
        this.bookmarked = bookmarked;
    }

    @Override
    public void render(ScreenBuffer buffer)
    {
        if (constraints.height() <= 0 || context == null) return;

        String title = context.sequenceTitle() != null ? context.sequenceTitle() : "";
        String fileName = context.files().get(context.currentIndex()).getName();
        String rawTitle = title.isEmpty() ? fileName : title + " (" + fileName + ")";

        boolean incHrs = (totalMicros / 1000000) >= 3600;
        String totStr = formatTime(totalMicros, incHrs);
        String curStr = formatTime(currentMicros, incHrs);
        int percent = (int) (totalMicros > 0 ? (currentMicros * 100 / totalMicros) : 0);
        percent = min(100, max(0, percent));

        String pauseIndicator = isPaused ? "\033[1;33m[PAUSED]\033[0m " : "";
        int visiblePauseLen = isPaused ? 9 : 0;

        String timeStr = curStr + " / " + totStr;
        int timeLen = timeStr.length();

        // Fixed visual length without bar:
        // "Time:      " (11) + pauseIndicator + "00:00 / 00:00" + "  " + bar + "  " + "100%"
        // Total fixed = 23 + visiblePauseLen + timeLen
        int barWidth = max(10, constraints.width() - 23 - visiblePauseLen - timeLen);

        int filled = (int) ((percent / 100.0) * barWidth);
        String bar = ProgressBar.render(filled, barWidth, ProgressBar.Style.SOLID_BACKGROUND, true);

        String portInfo =
                String.format("[%d] %s", context.targetPort().index(), context.targetPort().name());

        int h = constraints.height();

        // Consistent label alignment (10 chars padding)
        String fmtTitle = "%-10s %s";
        String fmtTime  = "%-10s %s%s / %s  %s  %3d%%";
        String fmtPort  = "%-10s %s";

        String titleLine = String.format(fmtTitle, "Title:",
                truncate(rawTitle, max(10, constraints.width() - 11)));
        String portLine = String.format(fmtPort, "Port:",
                truncate(portInfo, constraints.width() - 15));
        String settingsLine = String.format("Vol: %d%% | Tempo: %3.0f BPM (%.1fx) | Trans: %+d",
                (int) (volumeScale * 100), bpm, speed, transpose);

        if (h <= 2)
        {
            buffer.append(titleLine).append("\n");
            buffer.append(
                    String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent))
                    .append("\n");
        }
        else if (h == 3)
        {
            buffer.append(titleLine).append("\n");
            buffer.append(
                    String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent))
                    .append("\n");
            String packed = String.format("Port: %s | %s", portInfo, settingsLine);
            buffer.append(truncate(packed, constraints.width())).append("\n");
        }
        else if (h == 4 || (h >= 5 && copyright == null))
        {
            buffer.append(titleLine).append("\n");
            buffer.append(
                    String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent))
                    .append("\n");
            buffer.append(portLine).append("\n");
            buffer.append(truncate(settingsLine, constraints.width())).append("\n");
            for (int i = 4; i < h; i++) buffer.append("\n");
        }
        else
        {
            // h >= 5 && copyright != null
            String copyrightText = java.util.Objects.requireNonNull(copyright);
            String copyrightLine = String.format(fmtTitle, "Copyright:",
                    truncate(copyrightText, max(10, constraints.width() - 11)));
            buffer.append(titleLine).append("\n");
            buffer.append(copyrightLine).append("\n");
            buffer.append(
                    String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent))
                    .append("\n");
            buffer.append(portLine).append("\n");
            buffer.append(truncate(settingsLine, constraints.width())).append("\n");
            for (int i = 5; i < h; i++) buffer.append("\n");
        }
    }
}
