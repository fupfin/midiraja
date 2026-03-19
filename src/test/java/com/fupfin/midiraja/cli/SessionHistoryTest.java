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
