# Resume Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `midra resume` subcommand that lets users re-launch previous playback sessions from an interactive history/bookmark list, and `*` key during playback to bookmark the current session.

**Architecture:** A new `SessionHistory` class owns JSON persistence (`~/.config/midiraja/history.json`). `PlaybackRunner.run()` receives `List<String> originalArgs` and auto-saves on start. `PlaybackEngine` gets a `bookmarkCallback` Runnable fired on `*` key. `ResumeCommand` reads the history and re-executes via a fresh `CommandLine`.

**Tech Stack:** Java 25, picocli 4.7.x, JLine3 (key binding), JUnit 5, hand-rolled JSON (no third-party library).

**Spec:** `docs/superpowers/specs/2026-03-19-resume-command-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `src/main/java/com/fupfin/midiraja/cli/SessionEntry.java` | Create | Record: `List<String> args`, `Instant savedAt` |
| `src/main/java/com/fupfin/midiraja/cli/SessionHistory.java` | Create | Read/write `history.json`, recordAuto, saveBookmark, delete, promote, getAll |
| `src/main/java/com/fupfin/midiraja/io/TerminalIO.java` | Modify | Add `BOOKMARK` to `TerminalKey` enum |
| `src/main/java/com/fupfin/midiraja/io/JLineTerminalIO.java` | Modify | Bind `*` → `TerminalKey.BOOKMARK` |
| `src/main/java/com/fupfin/midiraja/ui/InputHandler.java` | Modify | Add `case BOOKMARK -> engine.fireBookmark()` |
| `src/main/java/com/fupfin/midiraja/engine/PlaybackEngine.java` | Modify | Add `bookmarkCallback` field, `setBookmarkCallback()`, `fireBookmark()` |
| `src/main/java/com/fupfin/midiraja/cli/PlaybackRunner.java` | Modify | Add `originalArgs` param, auto-save, set bookmark callback |
| `src/main/java/com/fupfin/midiraja/cli/FmCommand.java` | Modify | Pass `originalArgs` to `runner.run()` |
| `src/main/java/com/fupfin/midiraja/cli/TsfCommand.java` | Modify | Same |
| `src/main/java/com/fupfin/midiraja/cli/PsgCommand.java` | Modify | Same |
| `src/main/java/com/fupfin/midiraja/cli/BeepCommand.java` | Modify | Same |
| `src/main/java/com/fupfin/midiraja/cli/GusCommand.java` | Modify | Same |
| `src/main/java/com/fupfin/midiraja/cli/MuntCommand.java` | Modify | Same |
| `src/main/java/com/fupfin/midiraja/cli/FluidCommand.java` | Modify | Same |
| `src/main/java/com/fupfin/midiraja/cli/DeviceCommand.java` | Modify | Same |
| `src/main/java/com/fupfin/midiraja/cli/TerminalSelector.java` | Modify | Add `D` (delete) and `B` (bookmark) actions in FULL/MINI modes |
| `src/main/java/com/fupfin/midiraja/cli/ResumeCommand.java` | Create | `midra resume` subcommand — show history list and re-execute |
| `src/main/java/com/fupfin/midiraja/MidirajaCommand.java` | Modify | Register `ResumeCommand` |
| `src/main/resources/META-INF/native-image/reachability-metadata.json` | Modify | Add `ResumeCommand` reflection entry |
| `src/test/java/com/fupfin/midiraja/cli/SessionHistoryTest.java` | Create | Unit tests for SessionHistory |

---

## Task 1: SessionEntry + SessionHistory

**Files:**
- Create: `src/main/java/com/fupfin/midiraja/cli/SessionEntry.java`
- Create: `src/main/java/com/fupfin/midiraja/cli/SessionHistory.java`
- Create: `src/test/java/com/fupfin/midiraja/cli/SessionHistoryTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/fupfin/midiraja/cli/SessionHistoryTest.java
package com.fupfin.midiraja.cli;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionHistoryTest {
    private Path tmpDir;
    private SessionHistory history;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("session-history-test");
        history = new SessionHistory(tmpDir.resolve("history.json"));
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile).forEach(File::delete);
    }

    @Test
    void recordAuto_savesEntry() {
        history.recordAuto(List.of("opl", "--retro", "amiga", "/midi/"));
        var all = history.getAll();
        assertEquals(1, all.size());
        assertEquals(List.of("opl", "--retro", "amiga", "/midi/"), all.get(0).args());
    }

    @Test
    void recordAuto_capsAt10() {
        for (int i = 0; i < 12; i++)
            history.recordAuto(List.of("opl", "/midi/" + i));
        assertEquals(10, history.getAll().size());
        // newest is first
        assertEquals(List.of("opl", "/midi/11"), history.getAll().get(0).args());
    }

    @Test
    void recordAuto_deduplicatesMovingToTop() {
        history.recordAuto(List.of("opl", "/a.mid"));
        history.recordAuto(List.of("opl", "/b.mid"));
        history.recordAuto(List.of("opl", "/a.mid")); // duplicate
        var all = history.getAll();
        assertEquals(2, all.size());
        assertEquals(List.of("opl", "/a.mid"), all.get(0).args());
    }

    @Test
    void recordAuto_skipsIfAlreadyBookmarked() {
        history.saveBookmark(List.of("opl", "/a.mid"));
        history.recordAuto(List.of("opl", "/a.mid")); // same args
        // Only 1 entry total (in bookmarks), not duplicated in auto
        var all = history.getAll();
        assertEquals(1, all.size());
    }

    @Test
    void saveBookmark_savesEntry() {
        history.saveBookmark(List.of("soundfont", "/song.mid"));
        var all = history.getAll();
        assertEquals(1, all.size());
        assertEquals(List.of("soundfont", "/song.mid"), all.get(0).args());
    }

    @Test
    void saveBookmark_capsAt50() {
        for (int i = 0; i < 52; i++)
            history.saveBookmark(List.of("opl", "/midi/" + i));
        // getAll returns auto first (0 here), then bookmarks
        assertEquals(50, history.getAll().size());
    }

    @Test
    void getAll_returnsAutoBeforeBookmarks() {
        history.recordAuto(List.of("opl", "/a.mid"));
        history.saveBookmark(List.of("soundfont", "/b.mid"));
        var all = history.getAll();
        assertEquals(2, all.size());
        assertEquals(List.of("opl", "/a.mid"), all.get(0).args());
        assertEquals(List.of("soundfont", "/b.mid"), all.get(1).args());
    }

    @Test
    void deleteAuto_removesEntry() {
        history.recordAuto(List.of("opl", "/a.mid"));
        history.recordAuto(List.of("opl", "/b.mid"));
        history.deleteAuto(0); // delete newest
        var all = history.getAll();
        assertEquals(1, all.size());
        assertEquals(List.of("opl", "/a.mid"), all.get(0).args());
    }

    @Test
    void deleteBookmark_removesEntry() {
        history.saveBookmark(List.of("opl", "/a.mid"));
        history.deleteBookmark(0);
        assertTrue(history.getAll().isEmpty());
    }

    @Test
    void promoteToBookmark_movesFromAutoToBookmarks() {
        history.recordAuto(List.of("opl", "/a.mid"));
        history.promoteToBookmark(0);
        var all = history.getAll();
        assertEquals(1, all.size());
        // Should be in bookmarks section (position after auto entries, which are now 0)
        // We verify by checking that the single entry is a bookmark
        // The simplest check: getAutoCount() == 0, getBookmarkCount() == 1
        assertEquals(0, history.getAutoCount());
        assertEquals(1, history.getBookmarkCount());
    }

    @Test
    void persistsAcrossInstances() {
        history.recordAuto(List.of("opl", "/a.mid"));
        var history2 = new SessionHistory(tmpDir.resolve("history.json"));
        assertEquals(1, history2.getAll().size());
    }

    @Test
    void corruptedFile_treatedAsEmpty() throws IOException {
        Files.writeString(tmpDir.resolve("history.json"), "not valid json {{{");
        var history2 = new SessionHistory(tmpDir.resolve("history.json"));
        assertTrue(history2.getAll().isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.fupfin.midiraja.cli.SessionHistoryTest" 2>&1 | tail -5
```
Expected: compilation error (classes don't exist yet).

- [ ] **Step 3: Create `SessionEntry`**

```java
// src/main/java/com/fupfin/midiraja/cli/SessionEntry.java
package com.fupfin.midiraja.cli;

import java.time.Instant;
import java.util.List;

record SessionEntry(List<String> args, Instant savedAt) {}
```

- [ ] **Step 4: Create `SessionHistory`**

```java
// src/main/java/com/fupfin/midiraja/cli/SessionHistory.java
package com.fupfin.midiraja.cli;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persists playback session history and bookmarks to ~/.config/midiraja/history.json.
 * Auto entries: newest-first, capped at 10.
 * Bookmarks: newest-first, capped at 50.
 */
public class SessionHistory {
    private static final Logger log = Logger.getLogger(SessionHistory.class.getName());
    private static final int AUTO_LIMIT = 10;
    private static final int BOOKMARK_LIMIT = 50;

    private final Path filePath;
    private List<SessionEntry> auto = new ArrayList<>();
    private List<SessionEntry> bookmarks = new ArrayList<>();

    /** Production constructor: uses platform-appropriate config directory. */
    public SessionHistory() {
        this(defaultPath());
    }

    /** Test constructor: uses the given path. */
    public SessionHistory(Path filePath) {
        this.filePath = filePath;
        load();
    }

    public static Path defaultPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        } else {
            String xdgConfig = System.getenv("XDG_CONFIG_HOME");
            base = xdgConfig != null ? Path.of(xdgConfig)
                    : Path.of(System.getProperty("user.home"), ".config");
        }
        return base.resolve("midiraja").resolve("history.json");
    }

    public void recordAuto(List<String> args) {
        if (bookmarks.stream().anyMatch(e -> e.args().equals(args))) return;
        auto.removeIf(e -> e.args().equals(args));
        auto.add(0, new SessionEntry(List.copyOf(args), Instant.now()));
        if (auto.size() > AUTO_LIMIT) auto = new ArrayList<>(auto.subList(0, AUTO_LIMIT));
        save();
    }

    public void saveBookmark(List<String> args) {
        bookmarks.removeIf(e -> e.args().equals(args));
        bookmarks.add(0, new SessionEntry(List.copyOf(args), Instant.now()));
        if (bookmarks.size() > BOOKMARK_LIMIT)
            bookmarks = new ArrayList<>(bookmarks.subList(0, BOOKMARK_LIMIT));
        save();
    }

    public void deleteAuto(int index) {
        if (index >= 0 && index < auto.size()) { auto.remove(index); save(); }
    }

    public void deleteBookmark(int index) {
        if (index >= 0 && index < bookmarks.size()) { bookmarks.remove(index); save(); }
    }

    public void promoteToBookmark(int index) {
        if (index < 0 || index >= auto.size()) return;
        var entry = auto.remove(index);
        bookmarks.add(0, new SessionEntry(entry.args(), Instant.now()));
        if (bookmarks.size() > BOOKMARK_LIMIT)
            bookmarks = new ArrayList<>(bookmarks.subList(0, BOOKMARK_LIMIT));
        save();
    }

    public List<SessionEntry> getAll() {
        var result = new ArrayList<SessionEntry>(auto.size() + bookmarks.size());
        result.addAll(auto);
        result.addAll(bookmarks);
        return Collections.unmodifiableList(result);
    }

    public int getAutoCount()     { return auto.size(); }
    public int getBookmarkCount() { return bookmarks.size(); }

    // ── JSON serialization ────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            String json = Files.readString(filePath);
            auto      = parseEntries(json, "auto");
            bookmarks = parseEntries(json, "bookmarks");
        } catch (Exception e) {
            log.warning("history.json unreadable, treating as empty: " + e.getMessage());
            auto = new ArrayList<>();
            bookmarks = new ArrayList<>();
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            String json = toJson();
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Failed to save history: " + e.getMessage());
        }
    }

    /** Minimal hand-rolled JSON writer — no third-party dependency. */
    private String toJson() {
        var sb = new StringBuilder("{\n");
        sb.append("  \"auto\": [\n");
        appendEntries(sb, auto);
        sb.append("  ],\n  \"bookmarks\": [\n");
        appendEntries(sb, bookmarks);
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private void appendEntries(StringBuilder sb, List<SessionEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            sb.append("    {\"args\":[");
            for (int j = 0; j < e.args().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(jsonEscape(e.args().get(j))).append('"');
            }
            sb.append("],\"savedAt\":\"").append(e.savedAt()).append("\"}");
            if (i < entries.size() - 1) sb.append(',');
            sb.append('\n');
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Minimal hand-rolled JSON parser for the two known array fields. */
    private static List<SessionEntry> parseEntries(String json, String field) {
        List<SessionEntry> result = new ArrayList<>();
        String marker = "\"" + field + "\"";
        int start = json.indexOf(marker);
        if (start < 0) return result;
        int arrStart = json.indexOf('[', start);
        int arrEnd   = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return result;

        String section = json.substring(arrStart + 1, arrEnd);
        // Each entry: {"args":[...],"savedAt":"..."}
        int pos = 0;
        while (pos < section.length()) {
            int objStart = section.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = section.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = section.substring(objStart, objEnd + 1);
            try {
                List<String> args = parseStringArray(obj, "args");
                String savedAtStr = parseStringValue(obj, "savedAt");
                Instant savedAt = savedAtStr != null ? Instant.parse(savedAtStr) : Instant.now();
                if (args != null) result.add(new SessionEntry(args, savedAt));
            } catch (Exception ignored) {}
            pos = objEnd + 1;
        }
        return result;
    }

    private static List<String> parseStringArray(String obj, String field) {
        String marker = "\"" + field + "\":[";
        int start = obj.indexOf(marker);
        if (start < 0) return null;
        int arrStart = start + marker.length();
        int arrEnd   = obj.indexOf(']', arrStart);
        if (arrEnd < 0) return null;
        String content = obj.substring(arrStart, arrEnd);
        List<String> items = new ArrayList<>();
        int pos = 0;
        while (pos < content.length()) {
            int q1 = content.indexOf('"', pos);
            if (q1 < 0) break;
            int q2 = q1 + 1;
            while (q2 < content.length()) {
                if (content.charAt(q2) == '"' && content.charAt(q2 - 1) != '\\') break;
                q2++;
            }
            if (q2 >= content.length()) break;
            items.add(content.substring(q1 + 1, q2)
                    .replace("\\\"", "\"").replace("\\\\", "\\"));
            pos = q2 + 1;
        }
        return items;
    }

    private static String parseStringValue(String obj, String field) {
        String marker = "\"" + field + "\":\"";
        int start = obj.indexOf(marker);
        if (start < 0) return null;
        int valStart = start + marker.length();
        int valEnd   = obj.indexOf('"', valStart);
        if (valEnd < 0) return null;
        return obj.substring(valStart, valEnd);
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests "com.fupfin.midiraja.cli.SessionHistoryTest" 2>&1 | tail -8
```
Expected: all 11 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/cli/SessionEntry.java \
        src/main/java/com/fupfin/midiraja/cli/SessionHistory.java \
        src/test/java/com/fupfin/midiraja/cli/SessionHistoryTest.java
git commit -m "feat: add SessionHistory and SessionEntry for playback session persistence"
```

---

## Task 2: `TerminalKey.BOOKMARK` + key binding + `InputHandler`

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/io/TerminalIO.java`
- Modify: `src/main/java/com/fupfin/midiraja/io/JLineTerminalIO.java`
- Modify: `src/main/java/com/fupfin/midiraja/ui/InputHandler.java`

- [ ] **Step 1: Add `BOOKMARK` to `TerminalKey` enum in `TerminalIO.java`**

Find the line:
```java
NONE, SEEK_FORWARD, SEEK_BACKWARD, VOLUME_UP, VOLUME_DOWN, SPEED_UP, SPEED_DOWN, TRANSPOSE_UP, TRANSPOSE_DOWN, NEXT_TRACK, PREV_TRACK, QUIT, PAUSE
```
Replace with:
```java
NONE, SEEK_FORWARD, SEEK_BACKWARD, VOLUME_UP, VOLUME_DOWN, SPEED_UP, SPEED_DOWN, TRANSPOSE_UP, TRANSPOSE_DOWN, NEXT_TRACK, PREV_TRACK, QUIT, PAUSE, BOOKMARK
```

- [ ] **Step 2: Bind `*` → `BOOKMARK` in `JLineTerminalIO.java`**

In `buildKeyMap()`, after the existing single-character bindings block, add:
```java
km.bind(TerminalKey.BOOKMARK, "*");
```

- [ ] **Step 3: Handle `BOOKMARK` in `InputHandler.java`**

In `handleCommonInput()`, add after the `case PAUSE` line:
```java
case BOOKMARK -> engine.fireBookmark();
```
Note: `engine.fireBookmark()` will be added in Task 3. The code won't compile until then — that's fine; both changes will be committed together in Task 3.

- [ ] **Step 4: Build to check for errors (except missing `fireBookmark`)**

```bash
./gradlew compileJava 2>&1 | grep -v "fireBookmark\|cannot find symbol" | grep -i "error" | head -10
```
Expected: only "cannot find symbol: fireBookmark" errors. No other compile errors.

---

## Task 3: `PlaybackEngine` bookmark callback

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/engine/PlaybackEngine.java`

- [ ] **Step 1: Add `bookmarkCallback` field and methods to `PlaybackEngine`**

Add near the other field declarations:
```java
private volatile Runnable bookmarkCallback = null;
```

Add new public methods (after `togglePause()` or similar):
```java
public void setBookmarkCallback(Runnable callback) {
    this.bookmarkCallback = callback;
}

public void fireBookmark() {
    Runnable cb = bookmarkCallback;
    if (cb != null) cb.run();
}
```

- [ ] **Step 2: Build and run all tests**

```bash
./gradlew test 2>&1 | grep -E "tests completed|FAILED|BUILD" | tail -5
```
Expected: same test results as before (no regressions). The previously failing `CommonOptionsTest` entries remain but no new failures.

- [ ] **Step 3: Commit Tasks 2 and 3 together**

```bash
git add src/main/java/com/fupfin/midiraja/io/TerminalIO.java \
        src/main/java/com/fupfin/midiraja/io/JLineTerminalIO.java \
        src/main/java/com/fupfin/midiraja/ui/InputHandler.java \
        src/main/java/com/fupfin/midiraja/engine/PlaybackEngine.java
git commit -m "feat: add BOOKMARK key (* key) wiring through TerminalKey, InputHandler, PlaybackEngine"
```

---

## Task 4: `PlaybackRunner` — originalArgs + auto-save + bookmark wiring

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/cli/PlaybackRunner.java`

The `run()` method signature changes from:
```java
public int run(MidiOutProvider provider, boolean isSoftSynth, Optional<String> portQuery,
        Optional<String> soundbankArg, List<File> rawFiles, CommonOptions common)
```
to:
```java
public int run(MidiOutProvider provider, boolean isSoftSynth, Optional<String> portQuery,
        Optional<String> soundbankArg, List<File> rawFiles, CommonOptions common,
        List<String> originalArgs)
```

- [ ] **Step 1: Update `run()` signature and add auto-save**

Add `List<String> originalArgs` as the last parameter to `run()`.

After the `playlist.isEmpty()` guard (around line 129), add:
```java
// Auto-save session to history
if (!originalArgs.isEmpty()) {
    new SessionHistory().recordAuto(originalArgs);
}
```

- [ ] **Step 2: Wire bookmark callback**

In `playPlaylistLoop()`, add `List<String> originalArgs` as a parameter and thread it through from `run()`.

After `var engine = new PlaybackEngine(...)` and before `engine.start(ui)`, add:
```java
engine.setBookmarkCallback(() -> {
    new SessionHistory().saveBookmark(originalArgs);
    err.println("[Bookmarked]");
});
```

- [ ] **Step 3: Fix compilation — callers pass `List.of()` as placeholder**

All call sites in subcommands will now fail to compile. Temporarily pass `List.of()` as `originalArgs` in every `runner.run(...)` call to restore compilation (these are replaced properly in Task 5):

```bash
# Check which files need fixing
grep -rn "runner.run(" src/main/java/com/fupfin/midiraja/cli/ | grep -v "DemoCommand"
```

Update each caller (FmCommand, TsfCommand, PsgCommand, BeepCommand, GusCommand, MuntCommand, FluidCommand, DeviceCommand) to add `List.of()` as the last argument. Example for `TsfCommand.java`:
```java
return runner.run(provider, true, Optional.empty(), Optional.of(sfPath), files(), common, List.of());
```

- [ ] **Step 4: Build and run tests**

```bash
./gradlew test 2>&1 | grep -E "tests completed|FAILED|BUILD" | tail -5
```
Expected: no new failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/cli/PlaybackRunner.java
git commit -m "feat: PlaybackRunner accepts originalArgs, auto-saves session and wires bookmark callback"
```

---

## Task 5: Subcommand `originalArgs` propagation

**Files:**
- Modify: `FmCommand.java`, `TsfCommand.java`, `PsgCommand.java`, `BeepCommand.java`, `GusCommand.java`, `MuntCommand.java`, `FluidCommand.java`, `DeviceCommand.java`

For each subcommand, the pattern is:
1. Ensure `@Spec CommandSpec spec;` is present (FmCommand already has it)
2. In `call()`, extract originalArgs from picocli parse result
3. Convert file/directory token to absolute paths
4. Pass to `runner.run()`

**Pattern for extracting and absolutizing args:**

```java
private List<String> originalArgs() {
    var rawArgs = spec.commandLine().getParseResult().originalArgs();
    return rawArgs.stream().map(token -> {
        if (!token.startsWith("-")) {
            var f = new java.io.File(token);
            if (f.exists()) return f.getAbsolutePath();
        }
        return token;
    }).collect(java.util.stream.Collectors.toList());
}
```

Add this helper method to each subcommand and pass `originalArgs()` as the last arg to `runner.run()`.

- [ ] **Step 1: Update `FmCommand.java`** (already has `@Spec` at line 69)

Add `originalArgs()` helper, update both `callOpl()` and `callOpn()` signatures to accept `List<String> originalArgs`, pass `originalArgs()` from `call()`, pass through to `runner.run()`.

- [ ] **Step 2: Update `TsfCommand.java`**

Add `@Spec CommandSpec spec;` field if not present, add `originalArgs()` helper, update `runner.run()` call.

- [ ] **Step 3: Update `PsgCommand.java`**

Same pattern.

- [ ] **Step 4: Update `BeepCommand.java`**

Same pattern.

- [ ] **Step 5: Update `GusCommand.java`**

Same pattern.

- [ ] **Step 6: Update `MuntCommand.java`**

Same pattern.

- [ ] **Step 7: Update `FluidCommand.java`**

Same pattern.

- [ ] **Step 8: Update `DeviceCommand.java`**

Same pattern.

- [ ] **Step 9: Build and run all tests**

```bash
./gradlew test 2>&1 | grep -E "tests completed|FAILED|BUILD" | tail -5
```
Expected: no new failures.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/cli/
git commit -m "feat: propagate originalArgs through all subcommands to PlaybackRunner"
```

---

## Task 6: `TerminalSelector` D and B actions

**Files:**
- Modify: `src/main/java/com/fupfin/midiraja/cli/TerminalSelector.java`

The selector needs two new action values and the ability for the caller to handle them. The current API returns the selected item's value (an `Integer`). We need to also signal which action was taken (select, delete, bookmark-promote).

- [ ] **Step 1: Read `TerminalSelector.java` to understand the current API**

```bash
cat src/main/java/com/fupfin/midiraja/cli/TerminalSelector.java
```

- [ ] **Step 2: Add `SelectAction` sealed interface or record to represent results**

Add inside `TerminalSelector`:
```java
public sealed interface SelectResult permits SelectResult.Chosen, SelectResult.Delete, SelectResult.Promote, SelectResult.Cancelled {
    record Chosen(int value) implements SelectResult {}
    record Delete(int value) implements SelectResult {} // value = index in displayed list
    record Promote(int value) implements SelectResult {} // value = index in displayed list
    record Cancelled() implements SelectResult {}
}
```

Add a new `selectWithActions()` method alongside the existing `select()` method. The existing `select()` is unchanged so callers (port selection, engine selection) are unaffected.

- [ ] **Step 3: Implement `selectWithActions()` in FULL and MINI modes**

Bind `D` → `Delete`, `B` → `Promote` in the nav key map used only by the new method. In the action switch, handle them by returning the new result types. CLASSIC mode: numbered input only, D/B unavailable (return `Chosen` only).

- [ ] **Step 4: Build**

```bash
./gradlew compileJava 2>&1 | grep "error" | head -10
```
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/cli/TerminalSelector.java
git commit -m "feat: add selectWithActions() to TerminalSelector for delete and promote actions"
```

---

## Task 7: `ResumeCommand` + registration + metadata

**Files:**
- Create: `src/main/java/com/fupfin/midiraja/cli/ResumeCommand.java`
- Modify: `src/main/java/com/fupfin/midiraja/MidirajaCommand.java`
- Modify: `src/main/resources/META-INF/native-image/reachability-metadata.json`

- [ ] **Step 1: Write a failing integration test**

```java
// src/test/java/com/fupfin/midiraja/cli/ResumeCommandTest.java
package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ResumeCommandTest {

    @Test
    void resumeWithEmptyHistory_printsNoHistory(@TempDir Path tmpDir) {
        // Override history path via system property (ResumeCommand must respect this in tests)
        System.setProperty("midiraja.history.path", tmpDir.resolve("history.json").toString());
        try {
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            var cmd = new CommandLine(new MidirajaCommand())
                    .setOut(new PrintWriter(out))
                    .setErr(new PrintWriter(err));
            int exitCode = cmd.execute("resume", "--non-interactive");
            var output = err.toString() + out.toString();
            assertTrue(output.contains("No session history"),
                    "Expected 'No session history' message, got: " + output);
        } finally {
            System.clearProperty("midiraja.history.path");
        }
    }
}
```

Note: `ResumeCommand` must support a `--non-interactive` flag for testing (skips TerminalSelector, prints history as text, exits).

- [ ] **Step 2: Run test to verify failure**

```bash
./gradlew test --tests "com.fupfin.midiraja.cli.ResumeCommandTest" 2>&1 | tail -5
```
Expected: compilation error.

- [ ] **Step 3: Create `ResumeCommand.java`**

```java
// src/main/java/com/fupfin/midiraja/cli/ResumeCommand.java
package com.fupfin.midiraja.cli;

import com.fupfin.midiraja.MidirajaCommand;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "resume",
        mixinStandardHelpOptions = true,
        description = "Select and re-launch a previous playback session.")
public class ResumeCommand implements Callable<Integer> {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @ParentCommand
    private MidirajaCommand parent;

    @Option(names = {"--non-interactive"}, hidden = true,
            description = "Skip interactive selector (for testing).")
    boolean nonInteractive;

    @Override
    public Integer call() {
        var histPath = System.getProperty("midiraja.history.path");
        var history = histPath != null
                ? new SessionHistory(Path.of(histPath))
                : new SessionHistory();

        var all = history.getAll();
        if (all.isEmpty()) {
            System.err.println("No session history.");
            return 0;
        }

        if (nonInteractive) {
            printList(all, history.getAutoCount());
            return 0;
        }

        return runInteractive(all, history);
    }

    private void printList(List<SessionEntry> all, int autoCount) {
        for (int i = 0; i < all.size(); i++) {
            var e = all.get(i);
            boolean isBookmark = i >= autoCount;
            String marker = isBookmark ? " ★" : "";
            System.out.printf("[%d] %s  [%s]%s%n",
                    i + 1,
                    String.join(" ", e.args()),
                    FMT.format(e.savedAt()),
                    marker);
        }
    }

    private int runInteractive(List<SessionEntry> all, SessionHistory history) {
        int autoCount = history.getAutoCount();

        var items = new java.util.ArrayList<TerminalSelector.Item>();
        for (int i = 0; i < all.size(); i++) {
            var e = all.get(i);
            boolean isBookmark = i >= autoCount;
            String label = String.join(" ", e.args());
            String detail = "[" + FMT.format(e.savedAt()) + "]" + (isBookmark ? "  ★" : "");
            items.add(TerminalSelector.Item.of(i, label, detail));
        }

        var config = new TerminalSelector.FullScreenConfig(" RESUME SESSION ", 60, 60);
        boolean fullMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.fullMode;
        boolean miniMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.miniMode;
        boolean classicMode = parent != null && parent.getCommonOptions() != null
                && parent.getCommonOptions().uiOptions.classicMode;

        while (true) {
            var result = TerminalSelector.selectWithActions(items, config, fullMode, miniMode,
                    classicMode, System.err);
            if (result == null || result instanceof TerminalSelector.SelectResult.Cancelled) return 0;

            if (result instanceof TerminalSelector.SelectResult.Chosen chosen) {
                var entry = all.get(chosen.value());
                return new CommandLine(new MidirajaCommand())
                        .execute(entry.args().toArray(new String[0]));
            }

            if (result instanceof TerminalSelector.SelectResult.Delete del) {
                int idx = del.value();
                if (idx < autoCount) history.deleteAuto(idx);
                else history.deleteBookmark(idx - autoCount);
                all = history.getAll();
                autoCount = history.getAutoCount();
                if (all.isEmpty()) { System.err.println("No session history."); return 0; }
                // Rebuild items and loop
                items = buildItems(all, autoCount);
                continue;
            }

            if (result instanceof TerminalSelector.SelectResult.Promote promote) {
                int idx = promote.value();
                if (idx < autoCount) {
                    history.promoteToBookmark(idx);
                    all = history.getAll();
                    autoCount = history.getAutoCount();
                    items = buildItems(all, autoCount);
                }
                continue;
            }
        }
    }

    private java.util.ArrayList<TerminalSelector.Item> buildItems(
            List<SessionEntry> all, int autoCount) {
        var items = new java.util.ArrayList<TerminalSelector.Item>();
        for (int i = 0; i < all.size(); i++) {
            var e = all.get(i);
            boolean isBookmark = i >= autoCount;
            String label = String.join(" ", e.args());
            String detail = "[" + FMT.format(e.savedAt()) + "]" + (isBookmark ? "  ★" : "");
            items.add(TerminalSelector.Item.of(i, label, detail));
        }
        return items;
    }
}
```

- [ ] **Step 4: Register `ResumeCommand` in `MidirajaCommand.java`**

Find the `@Command(subcommands = {...})` annotation and add `ResumeCommand.class` to the list.

Also add a `getCommonOptions()` accessor if not present:
```java
public CommonOptions getCommonOptions() { return common; }
```

- [ ] **Step 5: Add `ResumeCommand` to `reachability-metadata.json`**

Find the pattern for existing CLI command entries (e.g. `OplCommand`) and add the same structure for `ResumeCommand`:
```json
{
  "type": "com.fupfin.midiraja.cli.ResumeCommand",
  "allDeclaredFields": true,
  "allDeclaredMethods": true,
  "allDeclaredConstructors": true
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew test --tests "com.fupfin.midiraja.cli.ResumeCommandTest" 2>&1 | tail -8
./gradlew test 2>&1 | grep -E "tests completed|FAILED|BUILD" | tail -5
```
Expected: `ResumeCommandTest` passes; no new failures overall.

- [ ] **Step 7: Run `NativeMetadataConsistencyTest`**

```bash
./gradlew test --tests "*.NativeMetadataConsistencyTest" 2>&1 | tail -8
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/fupfin/midiraja/cli/ResumeCommand.java \
        src/main/java/com/fupfin/midiraja/MidirajaCommand.java \
        src/test/java/com/fupfin/midiraja/cli/ResumeCommandTest.java \
        src/main/resources/META-INF/native-image/reachability-metadata.json
git commit -m "feat: add midra resume subcommand with interactive session history and bookmark support"
```

---

## Final Verification

- [ ] Run full test suite

```bash
./gradlew test 2>&1 | grep -E "tests completed|FAILED|BUILD" | tail -5
```
Expected: same 2 pre-existing failures (`CommonOptionsTest`), no new failures.

- [ ] Manual smoke test

```bash
# Play something to create history entry
./scripts/run.sh opl --retro amiga test.mid

# Verify history file created
cat ~/.config/midiraja/history.json

# Run resume command
./scripts/run.sh resume
```
