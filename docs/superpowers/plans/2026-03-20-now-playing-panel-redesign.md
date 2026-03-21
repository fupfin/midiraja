# NowPlayingPanel Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate Vol/Tempo/Trans into one line, show Copyright below Title when space allows, and display Loop/Shuffle icons next to the PLAYLIST panel title.

**Architecture:** Three independent layers: (1) data model (`PlaylistContext` gets `loop`/`shuffle`), (2) display logic (`NowPlayingPanel` layout restructure + copyright), (3) title update (`TitledPanel` gets a mutable title, `DashboardUI` sets it with icons). No new abstractions needed.

**Tech Stack:** Java 25, JSpecify (`@Nullable`), picocli, JLine3, JUnit 5, Gradle

---

## File Map

| File | Change |
|------|--------|
| `src/main/java/com/fupfin/midiraja/engine/PlaylistContext.java` | Add `loop`, `shuffle` fields |
| `src/main/java/com/fupfin/midiraja/cli/PlaybackRunner.java` | Pass `common.loop`, `common.shuffle` to `PlaylistContext` constructor |
| `src/main/java/com/fupfin/midiraja/ui/TitledPanel.java` | Add `setTitle(String)` mutator |
| `src/main/java/com/fupfin/midiraja/ui/NowPlayingPanel.java` | New layout + `setCopyright()` |
| `src/main/java/com/fupfin/midiraja/ui/DashboardUI.java` | Extract copyright, call `setCopyright()`, update PLAYLIST title |

---

## Task 1: Add `loop` and `shuffle` to `PlaylistContext`

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/engine/PlaylistContext.java`
- Modify: `src/main/java/com/fupfin/midiraja/cli/PlaybackRunner.java:435`

`PlaylistContext` is a one-liner record. Add two boolean fields at the end so existing positional construction stays readable.

- [ ] **Step 1: Update the record**

```java
public record PlaylistContext(
        List<File> files, int currentIndex, MidiPort targetPort,
        @Nullable String sequenceTitle,
        boolean loop, boolean shuffle) {}
```

- [ ] **Step 2: Fix the one call site in `PlaybackRunner.java` (line ~435)**

```java
var context = new PlaylistContext(playlist, currentTrackIdx, port, title,
        common.loop, common.shuffle);
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/engine/PlaylistContext.java \
        src/main/java/com/fupfin/midiraja/cli/PlaybackRunner.java
git commit -m "feat: add loop/shuffle to PlaylistContext"
```

---

## Task 2: Add `setTitle(String)` to `TitledPanel`

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/ui/TitledPanel.java`

`TitledPanel` currently takes `final String title`. Loop/Shuffle are known only when `DashboardUI.runRenderLoop()` starts (after the engine is running), so we need to mutate the title at that point.

- [ ] **Step 1: Change `final String title` to mutable, add setter**

Change field:
```java
private String title;
```

Add setter (after existing constructors):
```java
public void setTitle(String title) {
    this.title = title;
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/ui/TitledPanel.java
git commit -m "feat: add setTitle() to TitledPanel for dynamic titles"
```

---

## Task 3: Show Loop/Shuffle icons in PLAYLIST title

> **Prerequisite:** Task 1 must be complete. `ctx.loop()` and `ctx.shuffle()` do not exist until `PlaylistContext` is updated in Task 1. Attempting this task before Task 1 will cause a compile error.

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/ui/DashboardUI.java`

In `runRenderLoop()`, right after `rawPlaylistPanel.updateContext(engine.getContext())`, compute the playlist panel title with icons and call `titledPlaylistPanel.setTitle(...)`. Loop/shuffle are CLI flags that never change during playback, so calling `setTitle` once at startup (before the render loop) is correct and intentional.

Icons: `↩` (U+21A9) for loop, `⇄` (U+21C4) for shuffle. Show only active icons. Examples:
- Neither: `"PLAYLIST"`
- Loop only: `"PLAYLIST ↩"`
- Both: `"PLAYLIST ↩ ⇄"`

- [ ] **Step 1: Write a test for the title-building logic**

This logic is a pure string computation — extract it as a package-private static helper `playlistTitle(boolean loop, boolean shuffle)` in `DashboardUI`, then test it.

Add to `src/test/java/com/fupfin/midiraja/ui/DashboardUITest.java` (create if missing):

```java
package com.fupfin.midiraja.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardUITest {
    @Test void playlistTitle_noneActive() {
        assertEquals("PLAYLIST", DashboardUI.playlistTitle(false, false));
    }
    @Test void playlistTitle_loopOnly() {
        assertEquals("PLAYLIST \u21A9", DashboardUI.playlistTitle(true, false));
    }
    @Test void playlistTitle_shuffleOnly() {
        assertEquals("PLAYLIST \u21C4", DashboardUI.playlistTitle(false, true));
    }
    @Test void playlistTitle_both() {
        assertEquals("PLAYLIST \u21A9 \u21C4", DashboardUI.playlistTitle(true, true));
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (method missing)**

```bash
./gradlew test --tests "com.fupfin.midiraja.ui.DashboardUITest" 2>&1 | tail -10
```

- [ ] **Step 3: Add the static helper and wire it up in `runRenderLoop()`**

Add to `DashboardUI`:
```java
static String playlistTitle(boolean loop, boolean shuffle) {
    var sb = new StringBuilder("PLAYLIST");
    if (loop)    sb.append(" \u21A9");
    if (shuffle) sb.append(" \u21C4");
    return sb.toString();
}
```

In `runRenderLoop()`, after the existing line `rawPlaylistPanel.updateContext(engine.getContext())`:
```java
var ctx = engine.getContext();
titledPlaylistPanel.setTitle(playlistTitle(ctx.loop(), ctx.shuffle()));
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./gradlew test --tests "com.fupfin.midiraja.ui.DashboardUITest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/ui/DashboardUI.java \
        src/test/java/com/fupfin/midiraja/ui/DashboardUITest.java
git commit -m "feat: show loop/shuffle icons in PLAYLIST panel title"
```

---

## Task 4: Restructure `NowPlayingPanel` layout

> **Prerequisite:** Task 1 must be complete. The test uses the 6-arg `PlaylistContext(files, index, port, title, loop, shuffle)` constructor added in Task 1. Compiling Task 4's test before Task 1 is done will fail.

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/ui/NowPlayingPanel.java`

### New layout (max 4 lines, +1 optional copyright):

| h | Lines rendered |
|---|----------------|
| ≥ 5 | Title / Copyright (if present) / Time / Port / Settings |
| 4 | Title / Time / Port / Settings |
| 3 | Title / Time / Port+Settings (packed) |
| ≤ 2 | Title / Time |

**Settings line** = `Vol: 80% | Tempo: 120 BPM (1.0x) | Trans: +0`

**Copyright line** = `%-10s %s` aligned like Title, e.g. `Copyright  © 1988 Falcom`

### Changes to `NowPlayingPanel`:
1. Add `@Nullable String copyright` field and `setCopyright(@Nullable String)` setter
2. Rewrite `render()` with new height tiers
3. Remove `extraMetadata` field and `setExtraMetadata()` — it was never rendered; delete dead code
4. `speed` field is kept (used in Settings line as `(%.1fx)`)
5. `bookmarked` field and `onBookmarkChanged()` are **kept** but `bookmarked` is **not used in `render()`** — the bookmark indicator was moved to the DashboardUI banner (`[Saved]`) in a prior session. Do not re-add it to `titleLine`.

- [ ] **Step 1: Write tests for the new layout**

Add to `src/test/java/com/fupfin/midiraja/ui/NowPlayingPanelTest.java` (create if missing):

```java
package com.fupfin.midiraja.ui;

import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.LayoutListener.LayoutConstraints;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NowPlayingPanelTest {
    private NowPlayingPanel panel;
    private PlaylistContext ctx;

    @BeforeEach void setUp() {
        panel = new NowPlayingPanel();
        ctx = new PlaylistContext(
            List.of(new File("track.mid")), 0,
            new MidiPort(0, "Test Port"), "Test Song", false, false);
        panel.updateState(30_000_000L, 120_000_000L, 120f, 1.0, 1.0, 0, false, ctx);
    }

    @Test void height4_renders4Lines() {
        panel.onLayoutUpdated(new LayoutConstraints(80, 4, false, false));
        String out = render(panel);
        String[] lines = out.split("\n");
        assertEquals(4, countNonEmpty(lines));
        assertTrue(lines[0].contains("Title:"));
        assertTrue(lines[1].contains("Time:"));
        assertTrue(lines[2].contains("Port:"));
        assertTrue(lines[3].contains("Vol:"));
        assertTrue(lines[3].contains("Tempo:"));
        assertTrue(lines[3].contains("Trans:"));
    }

    @Test void height5_withCopyright_renders5Lines() {
        panel.setCopyright("© 1988 Falcom");
        panel.onLayoutUpdated(new LayoutConstraints(80, 5, false, false));
        String out = render(panel);
        String[] lines = out.split("\n");
        assertEquals(5, countNonEmpty(lines));
        assertTrue(lines[1].contains("1988 Falcom"));
    }

    @Test void height5_withoutCopyright_noCopyrightLineShown() {
        panel.onLayoutUpdated(new LayoutConstraints(80, 5, false, false));
        String out = render(panel);
        // no copyright set → copyright row must not appear; only 4 content lines + 1 blank
        assertFalse(out.contains("Copyright:"));
        assertEquals(4, countNonEmpty(out.split("\n")));
    }

    @Test void settingsLine_formatsCorrectly() {
        panel.onLayoutUpdated(new LayoutConstraints(80, 4, false, false));
        String out = render(panel);
        assertTrue(out.contains("Vol:"));
        assertTrue(out.contains("BPM"));
        assertTrue(out.contains("Trans:"));
    }

    private String render(NowPlayingPanel p) {
        var buf = new ScreenBuffer(1024);
        p.render(buf);
        return buf.toString();
    }

    private int countNonEmpty(String[] lines) {
        int count = 0;
        for (String l : lines) if (!l.isBlank()) count++;
        return count;
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
./gradlew test --tests "com.fupfin.midiraja.ui.NowPlayingPanelTest" 2>&1 | tail -10
```

- [ ] **Step 3: Rewrite `NowPlayingPanel`**

Key changes to `render()`:

```java
// Settings line (shared across all heights)
String settingsLine = String.format("Vol: %d%% | Tempo: %3.0f BPM (%.1fx) | Trans: %+d",
        (int)(volumeScale * 100), bpm, speed, transpose);

String portLine = String.format(fmtPort, "Port:",
        truncate(portInfo, constraints.width() - 15));

if (h <= 2) {
    buffer.append(titleLine).append("\n");
    buffer.append(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent)).append("\n");
} else if (h == 3) {
    buffer.append(titleLine).append("\n");
    buffer.append(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent)).append("\n");
    // Pack port + settings into one line
    String packed = String.format("Port: %s | %s", portInfo, settingsLine);
    buffer.append(truncate(packed, constraints.width())).append("\n");
} else if (h == 4 || (h >= 5 && copyright == null)) {
    buffer.append(titleLine).append("\n");
    buffer.append(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent)).append("\n");
    buffer.append(portLine).append("\n");
    buffer.append(truncate(settingsLine, constraints.width())).append("\n");
    // Fill remaining with blank lines
    for (int i = 4; i < h; i++) buffer.append("\n");
} else {
    // h >= 5 && copyright != null
    String copyrightLine = String.format(fmtTitle, "Copyright:", truncate(copyright, max(10, constraints.width() - 11)));
    buffer.append(titleLine).append("\n");
    buffer.append(copyrightLine).append("\n");
    buffer.append(String.format(fmtTime, "Time:", pauseIndicator, curStr, totStr, bar, percent)).append("\n");
    buffer.append(portLine).append("\n");
    buffer.append(truncate(settingsLine, constraints.width())).append("\n");
    // Fill remaining with blank lines
    for (int i = 5; i < h; i++) buffer.append("\n");
}
```

Also:
- Add field: `@Nullable private String copyright = null;`
- Add method: `public void setCopyright(@Nullable String copyright) { this.copyright = copyright; }`
- Remove `extraMetadata` field and `setExtraMetadata()` method

- [ ] **Step 4: Run tests — expect PASS**

```bash
./gradlew test --tests "com.fupfin.midiraja.ui.NowPlayingPanelTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/ui/NowPlayingPanel.java \
        src/test/java/com/fupfin/midiraja/ui/NowPlayingPanelTest.java
git commit -m "feat: consolidate Vol/Tempo/Trans line, add copyright row in NowPlayingPanel"
```

---

## Task 5: Wire copyright from MIDI file to `NowPlayingPanel`

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/ui/DashboardUI.java`

`DashboardUI` already calls `nowPlayingPanel.setExtraMetadata(extractExtraMetadata(...))`. Since `setExtraMetadata()` is now removed (Task 4), replace it with `setCopyright(extractCopyright(...))`.

`extractCopyright()` reads only MetaMessage type `0x02` (Copyright), returns the first one found or `null`. `runRenderLoop()` is called once per track (one `PlaybackEngine` per track), so copyright is always freshly extracted for the current track — no stale state issue.

- [ ] **Step 1: Replace `extractExtraMetadata()` with `extractCopyright()`**

Remove the old method. Add:
```java
private @Nullable String extractCopyright(Sequence seq) {
    for (Track track : seq.getTracks()) {
        for (int i = 0; i < track.size(); i++) {
            MidiMessage msg = track.get(i).getMessage();
            if (msg instanceof MetaMessage m && m.getType() == 0x02) {
                String text = new String(m.getData(), StandardCharsets.US_ASCII).trim();
                if (!text.isEmpty() && text.chars().allMatch(c -> c >= 32 && c < 127))
                    return text;
            }
        }
    }
    return null;
}
```

- [ ] **Step 2: Update the call site in `runRenderLoop()`**

Replace:
```java
nowPlayingPanel.setExtraMetadata(extractExtraMetadata(engine.getSequence()));
```
With:
```java
nowPlayingPanel.setCopyright(extractCopyright(engine.getSequence()));
```

- [ ] **Step 3: Run all tests**

```bash
./gradlew test 2>&1 | tail -15
```
Expected: same pass/fail count as before (only pre-existing `CommonOptionsTest` failures).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/ui/DashboardUI.java
git commit -m "feat: extract and display MIDI copyright in NowPlayingPanel"
```

---

## Final Verification

```bash
./gradlew test 2>&1 | grep -E "tests completed|FAILED"
# Manual smoke test (if MIDI files available):
./scripts/run.sh --loop --shuffle path/to/midi/files/
# Verify: PLAYLIST title shows ↩ ⇄ icons
# Verify: Copyright line appears when file has copyright meta
# Verify: Vol/Tempo/Trans are on a single line
```
