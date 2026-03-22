package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.engine.PlaybackEngine;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaybackEngineFactory;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.io.TerminalIO.TerminalKey;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.DumbUI;
import com.fupfin.midiraja.ui.PlaybackUI;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaybackRunnerTest {

    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;
    private MockTerminalIO mockIO;
    private CommonOptions common;

    static class MockMidiProvider implements MidiOutProvider {
        boolean isOpen = false;
        boolean isClosed = false;

        @Override public List<MidiPort> getOutputPorts() { return List.of(new MidiPort(0, "MockPort")); }
        @Override public void openPort(int portIndex) { isOpen = true; }
        @Override public void closePort() { isClosed = true; }
        @Override public void sendMessage(byte[] data) {}
        @Override public void panic() {}
        @Override public long getAudioLatencyNanos() { return 0; }
        @Override public void onPlaybackStarted() {}
        @Override public void prepareForNewTrack(Sequence sequence) {}
    }

    static class MockPlaybackEngine extends PlaybackEngine {
        private final PlaybackStatus exitStatus;

        // NOTE: super() calls sequence.getResolution() and sequence.getTracks(), so `seq`
        // must be a real non-null Sequence object. Always use createTestMidi() to obtain one.
        // Passing null will throw NullPointerException before start() is ever reached.
        MockPlaybackEngine(Sequence seq, MidiOutProvider p, PlaylistContext ctx,
                           int vol, double speed, Optional<String> start,
                           Optional<Integer> transpose, PlaybackStatus exitStatus) {
            super(seq, p, ctx, vol, speed, start, transpose);
            this.exitStatus = exitStatus;
        }

        @Override
        public PlaybackStatus start(PlaybackUI ui) throws Exception {
            return exitStatus;
        }
    }

    @BeforeEach
    void setUp() {
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        mockIO = new MockTerminalIO();
        common = new CommonOptions();
    }

    private File createTestMidi(Path tempDir, String name) throws Exception {
        File midiFile = tempDir.resolve(name).toFile();
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 2400));
        try (FileOutputStream fos = new FileOutputStream(midiFile)) {
            MidiSystem.write(seq, 1, fos);
        }
        return midiFile;
    }

    /** Backward-compat overload used by existing tests. */
    private File createTestMidi(Path tempDir) throws Exception {
        return createTestMidi(tempDir, "test.mid");
    }

    @Test
    void testRunnerQuitsOnQKey(@TempDir Path tempDir) throws Exception {
        File midiFile = createTestMidi(tempDir);
        MockMidiProvider provider = new MockMidiProvider();
        PlaybackRunner runner = new PlaybackRunner(new PrintStream(outBytes), new PrintStream(errBytes), mockIO, true);

        // Inject the 'q' key to force the loop to exit early
        mockIO.injectKey(TerminalKey.QUIT);

        // Run as SoftSynth (skips port selection)
        int exitCode = runner.run(provider, true, Optional.empty(), Optional.empty(), List.of(midiFile), common, List.of());

        assertEquals(0, exitCode, "Exit code should be 0 on normal quit");
        assertTrue(provider.isOpen, "Provider should have been opened");
        
        // Ensure that the output stream was used (meaning UI or at least init text was written)
        // Test passed if it reached here without hanging
    }
    
    @Test
    void testRunnerHandlesNoFiles() throws Exception {
        MockMidiProvider provider = new MockMidiProvider();
        PlaybackRunner runner = new PlaybackRunner(new PrintStream(outBytes), new PrintStream(errBytes), mockIO, true);

        int exitCode = runner.run(provider, true, Optional.empty(), Optional.empty(), new ArrayList<>(), common, List.of());

        assertEquals(1, exitCode, "Exit code should be 1 if no files are found");
        String errOutput = errBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(errOutput.contains("No MIDI files specified"), "Should print error about missing files");
    }

    @Test void buildPlayOrder_notShuffled_isSequential() {
        int[] order = PlaybackRunner.buildPlayOrder(4, false);
        assertArrayEquals(new int[]{0, 1, 2, 3}, order);
    }

    @Test void buildPlayOrder_shuffled_containsAllIndices() {
        int[] order = PlaybackRunner.buildPlayOrder(5, true);
        assertEquals(5, order.length);
        // All indices 0-4 present
        int sum = 0;
        for (int v : order) sum += v;
        assertEquals(0 + 1 + 2 + 3 + 4, sum);
    }

    @Test void buildPlayOrder_sizeZero_returnsEmpty() {
        assertArrayEquals(new int[0], PlaybackRunner.buildPlayOrder(0, false));
        assertArrayEquals(new int[0], PlaybackRunner.buildPlayOrder(0, true));
    }

    @Test void buildPlayOrder_sizeOne_returnsSingleElement() {
        assertArrayEquals(new int[]{0}, PlaybackRunner.buildPlayOrder(1, true));
    }

    @Test void reshuffleRemaining_shuffleOn_fullArrayShuffled() {
        // Large array: entire array (except currentIdx=2) should be shuffled
        int[] order = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        PlaybackRunner.reshuffleRemaining(order, 2, true);
        // Currently-playing song stays at currentIdx
        assertEquals(2, order[2]);
        // All values 0-9 still present
        int sum = 0;
        for (int v : order) sum += v;
        assertEquals(0+1+2+3+4+5+6+7+8+9, sum);
        // At least one element is out of original order (with overwhelming probability for 9 others)
        boolean stillSorted = true;
        for (int i = 0; i < order.length - 1; i++) {
            if (order[i] > order[i + 1]) { stillSorted = false; break; }
        }
        assertFalse(stillSorted, "Full array shuffle should not remain sorted");
    }

    @Test void reshuffleRemaining_shuffleOff_restoresAscendingOrder() {
        int[] order = {3, 4, 0, 1, 2}; // currentIdx=2, song=0; others unsorted
        PlaybackRunner.reshuffleRemaining(order, 2, false);
        // After sort, 0 is at index 0; swap with currentIdx=2 → {1, 2, 0, 3, 4}
        assertEquals(0, order[2]); // current song stays at currentIdx
        // All values still present
        int sum = 0;
        for (int v : order) sum += v;
        assertEquals(0+1+2+3+4, sum);
    }

    @Test void reshuffleRemaining_atLastTrack_currentSongStaysAtEnd() {
        // Even at last track, current song must remain at currentIdx=2
        int[] order = {0, 1, 2};
        PlaybackRunner.reshuffleRemaining(order, 2, true);
        assertEquals(2, order[2]);
    }

    @Test void reshuffleRemaining_idempotentSortOff() {
        int[] order = {0, 1, 2, 3}; // already sorted, currentIdx=0
        PlaybackRunner.reshuffleRemaining(order, 0, false);
        assertArrayEquals(new int[]{0, 1, 2, 3}, order);
    }

    @Test
    void quitAll_exitsLoopAfterOneEngineCall(@TempDir Path tempDir) throws Exception {
        File f1 = createTestMidi(tempDir, "a.mid");
        File f2 = createTestMidi(tempDir, "b.mid");
        File f3 = createTestMidi(tempDir, "c.mid");

        AtomicInteger callCount = new AtomicInteger(0);
        PlaybackEngineFactory factory = (seq, p, ctx, vol, speed, start, transpose) -> {
            callCount.incrementAndGet();
            return new MockPlaybackEngine(seq, p, ctx, vol, speed, start, transpose,
                    PlaybackStatus.QUIT_ALL);
        };

        MockMidiProvider provider = new MockMidiProvider();
        // 5-parameter constructor — does not exist yet, will fail to compile
        PlaybackRunner runner = new PlaybackRunner(
                new PrintStream(outBytes), new PrintStream(errBytes), mockIO, true, factory);

        runner.run(provider, true, Optional.empty(), Optional.empty(),
                List.of(f1, f2, f3), common, List.of());

        assertEquals(1, callCount.get(), "Factory must be called exactly once on QUIT_ALL");
    }
}
