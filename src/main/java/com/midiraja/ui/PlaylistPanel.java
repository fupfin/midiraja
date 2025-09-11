/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.midiraja.ui;

import com.midiraja.engine.PlaylistContext;
import org.jspecify.annotations.Nullable;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaylistPanel implements Panel {
    private LayoutConstraints constraints = new LayoutConstraints(80, 0, false, false);
    @Nullable private PlaylistContext context;
    private final Map<File, String> titleCache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds) {
        this.constraints = bounds;
    }

    public void updateContext(PlaylistContext context) {
        this.context = context;
        if (context != null) {
            // Asynchronously fetch titles for all files in the playlist to avoid blocking startup
            for (File file : context.files()) {
                if (!titleCache.containsKey(file)) {
                    var unused = executor.submit(() -> {
                        try {
                            Sequence seq = MidiSystem.getSequence(file);
                            String title = extractSequenceTitle(seq);
                            if (title != null && !title.isEmpty()) {
                                titleCache.put(file, title.trim());
                            } else {
                                titleCache.put(file, ""); // Empty string means no title
                            }
                        } catch (Exception ignored) {
                            titleCache.put(file, "");
                        }
                    });
                }
            }
        }
    }

    private @Nullable String extractSequenceTitle(Sequence sequence) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof javax.sound.midi.MetaMessage meta) {
                    if (meta.getType() == 0x03) { // Sequence/Track Name
                        byte[] data = meta.getData();
                        return new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
                    }
                }
            }
        }
        return null;
    }

    @Override public void onPlaybackStateChanged() {}
    @Override public void onTick(long currentMicroseconds) {}
    @Override public void onTempoChanged(float bpm) {}
    @Override public void onChannelActivity(int channel, int velocity) {}

    @Override
    public void render(StringBuilder sb) {
        if (constraints.height() <= 0 || context == null) return;
        
        int listSize = context.files().size();
        int idx = context.currentIndex();

        int maxItems = constraints.height();
        int half = maxItems / 2;
        int startIdx = Math.max(0, idx - half);
        int endIdx = Math.min(listSize - 1, startIdx + maxItems - 1);
        startIdx = Math.max(0, endIdx - maxItems + 1);

        for (int i = startIdx; i <= endIdx; i++) {
            File file = context.files().get(i);
            String fileName = file.getName();
            
            String fetchedTitle = titleCache.get(file);
            String displayName = (fetchedTitle != null && !fetchedTitle.isEmpty()) 
                ? fileName + " (" + fetchedTitle + ")" 
                : fileName;

            String status = (i == idx) ? "  (Playing)" : "";
            
            // Format the number or the active marker
            String numStr;
            String dotStr = ".";
            if (i == idx) {
                // Number length + 1 (for the dot)
                int numLen = String.valueOf(i + 1).length();
                numStr = ">".repeat(numLen);
                dotStr = ">"; // Replace the dot with another >
                
                // Color the active track Cyan!
                displayName = "\033[1;36m" + displayName + "\033[0m";
                status = "\033[1;36m" + status + "\033[0m";
                numStr = "\033[1;36m" + numStr;
                dotStr = dotStr + "\033[0m";
            } else {
                numStr = String.valueOf(i + 1);
            }

            int visibleDisplayNameLen = displayName.replaceAll("\\033\\[[;\\d]*m", "").length();
            int visibleStatusLen = status.replaceAll("\\033\\[[;\\d]*m", "").length();
            int baseLen = 6; // "  " + "99. " (approx 6 chars)
            
            if (visibleDisplayNameLen > constraints.width() - visibleStatusLen - baseLen) {
                int maxLen = Math.max(0, constraints.width() - visibleStatusLen - baseLen - 3);
                String stripped = displayName.replaceAll("\\033\\[[;\\d]*m", "");
                if (stripped.length() > maxLen) {
                     if (i == idx) {
                         displayName = "\033[1;36m" + stripped.substring(0, maxLen) + "...\033[0m";
                     } else {
                         displayName = stripped.substring(0, maxLen) + "...";
                     }
                }
            }
            
            sb.append(String.format("  %s%s %s%s\n", numStr, dotStr, displayName, status));
        }
        
        int printed = (endIdx - startIdx + 1);
        for (int i = printed; i < constraints.height(); i++) {
            sb.append("\n");
        }
    }
}
