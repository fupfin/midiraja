package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
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

import com.fupfin.midiraja.engine.PlaybackEngine;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaybackEngineFactory;
import com.fupfin.midiraja.engine.PlaybackPipeline;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.media.NoOpMediaIntegration;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.DumbUI;

class PlaylistPlayerTest {

    private MockTerminalIO mockIO;
    private CommonOptions common;
    private PrintStream errStream;

    static class MockMidiProvider implements MidiOutProvider {
        @Override public List<MidiPort> getOutputPorts() { return List.of(new MidiPort(0, "Mock")); }
        @Override public void openPort(int portIndex) {}
        @Override public void closePort() {}
        @Override public void sendMessage(byte[] data) {}
        @Override public void panic() {}
        @Override public long getAudioLatencyNanos() { return 0; }
        @Override public void onPlaybackStarted() {}
        @Override public void prepareForNewTrack(Sequence sequence) {}
    }

    static class MockPlaybackEngine implements PlaybackEngine {
        private final PlaylistContext ctx;
        private final PlaybackPipeline pipeline;
        private final double speed;
        private final PlaybackStatus exitStatus;
        MockPlaybackEngine(Sequence seq, MidiOutProvider p, PlaylistContext ctx,
                           PlaybackPipeline pipeline, java.util.function.BooleanSupplier isShuttingDown,
                           double speed, Optional<Long> start, PlaybackStatus exitStatus) {
            this.ctx = ctx;
            this.pipeline = pipeline;
            this.speed = speed;
            this.exitStatus = exitStatus;
        }
        @Override public PlaybackStatus start(com.fupfin.midiraja.ui.PlaybackUI ui) {
            return exitStatus;
        }
        // PlaybackState — return meaningful values so PlaylistPlayer state propagates correctly
        @Override public PlaylistContext getContext() { return ctx; }
        @Override public Sequence getSequence() { return null; }
        @Override public long getCurrentMicroseconds() { return 0; }
        @Override public long getTotalMicroseconds() { return 0; }
        @Override public int[] getChannelPrograms() { return new int[0]; }
        @Override public float getCurrentBpm() { return 120f; }
        @Override public double getCurrentSpeed() { return speed; }
        @Override public int getCurrentTranspose() { return pipeline.getCurrentTranspose(); }
        @Override public double getVolumeScale() { return pipeline.getVolumeScale(); }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isPaused() { return false; }
        @Override public boolean isLoopEnabled() { return ctx.loop(); }
        @Override public boolean isShuffleEnabled() { return ctx.shuffle(); }
        @Override public boolean isBookmarked() { return false; }
        @Override public String getFilterDescription() { return ""; }
        @Override public String getPortSuffix() { return ""; }
        @Override public void addPlaybackEventListener(com.fupfin.midiraja.ui.PlaybackEventListener listener) {}
        // PlaybackCommands no-ops
        @Override public void requestStop(PlaybackStatus status) {}
        @Override public void adjustVolume(double delta) {}
        @Override public void adjustSpeed(double delta) {}
        @Override public void adjustTranspose(int delta) {}
        @Override public void seekRelative(long microsecondsDelta) {}
        @Override public void togglePause() {}
        @Override public void toggleLoop() {}
        @Override public void toggleShuffle() {}
        @Override public void fireBookmark() {}
        @Override public void firePlayOrderChanged(PlaylistContext ctx) {}
        // PlaybackEngine configuration no-ops
        @Override public void setHoldAtEnd(boolean hold) {}
        @Override public void setIgnoreSysex(boolean ignoreSysex) {}
        @Override public void setInitialResetType(Optional<String> resetType) {}
        @Override public void setInitiallyPaused() {}
        @Override public void setFilterDescription(String desc) {}
        @Override public void setPortSuffix(String suffix) {}
        @Override public void setBookmarked(boolean bookmarked) {}
        @Override public void setBookmarkCallback(java.util.function.Consumer<Boolean> callback) {}
        @Override public void setShuffleCallback(java.util.function.Consumer<Boolean> callback) {}
    }

    @BeforeEach void setUp() {
        mockIO = new MockTerminalIO();
        common = new CommonOptions();
        errStream = new PrintStream(new ByteArrayOutputStream());
    }

    private File createTestMidi(Path tempDir, String name) throws Exception {
        File f = tempDir.resolve(name).toFile();
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 2400));
        try (var fos = new FileOutputStream(f)) { MidiSystem.write(seq, 1, fos); }
        return f;
    }

    private PlaylistPlayer player(PlaybackEngineFactory factory) {
        return new PlaylistPlayer(factory, null, false, false, false, errStream,
                NoOpMediaIntegration.INSTANCE);
    }

    // ── static helpers ────────────────────────────────────────────────────────

    @Test void buildPlayOrder_notShuffled_isSequential() {
        assertArrayEquals(new int[]{0, 1, 2, 3}, PlaylistPlayer.buildPlayOrder(4, false));
    }

    @Test void buildPlayOrder_shuffled_containsAllIndices() {
        int[] order = PlaylistPlayer.buildPlayOrder(5, true);
        assertEquals(5, order.length);
        int sum = 0; for (int v : order) sum += v;
        assertEquals(0 + 1 + 2 + 3 + 4, sum);
    }

    @Test void buildPlayOrder_sizeZero_returnsEmpty() {
        assertArrayEquals(new int[0], PlaylistPlayer.buildPlayOrder(0, false));
        assertArrayEquals(new int[0], PlaylistPlayer.buildPlayOrder(0, true));
    }

    @Test void buildPlayOrder_sizeOne_returnsSingleElement() {
        assertArrayEquals(new int[]{0}, PlaylistPlayer.buildPlayOrder(1, true));
    }

    @Test void reshuffleRemaining_shuffleOn_fullArrayShuffled() {
        int[] order = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        PlaylistPlayer.reshuffleRemaining(order, 2, true);
        assertEquals(2, order[2]);
        int sum = 0; for (int v : order) sum += v;
        assertEquals(0+1+2+3+4+5+6+7+8+9, sum);
        boolean stillSorted = true;
        for (int i = 0; i < order.length - 1; i++) {
            if (order[i] > order[i + 1]) { stillSorted = false; break; }
        }
        assertFalse(stillSorted);
    }

    @Test void reshuffleRemaining_shuffleOff_restoresAscendingOrder() {
        int[] order = {3, 4, 0, 1, 2};
        PlaylistPlayer.reshuffleRemaining(order, 2, false);
        assertEquals(0, order[2]);
        int sum = 0; for (int v : order) sum += v;
        assertEquals(0+1+2+3+4, sum);
    }

    @Test void reshuffleRemaining_atLastTrack_currentSongStaysAtEnd() {
        int[] order = {0, 1, 2};
        PlaylistPlayer.reshuffleRemaining(order, 2, true);
        assertEquals(2, order[2]);
    }

    @Test void reshuffleRemaining_idempotentSortOff() {
        int[] order = {0, 1, 2, 3};
        PlaylistPlayer.reshuffleRemaining(order, 0, false);
        assertArrayEquals(new int[]{0, 1, 2, 3}, order);
    }

    // ── loop-behavior tests ───────────────────────────────────────────────────

    @Test void quitAll_exitsLoopAfterOneEngineCall(@TempDir Path tempDir) throws Exception {
        var f1 = createTestMidi(tempDir, "a.mid");
        var f2 = createTestMidi(tempDir, "b.mid");
        var callCount = new AtomicInteger(0);
        PlaybackEngineFactory factory = (seq, p, ctx, pipeline, isShuttingDown, speed, start) -> {
            callCount.incrementAndGet();
            return new MockPlaybackEngine(seq, p, ctx, pipeline, isShuttingDown, speed, start, PlaybackStatus.QUIT_ALL);
        };
        player(factory).play(List.of(f1, f2), new MockMidiProvider(),
                new MidiPort(0, "Mock"), common, new DumbUI(), mockIO,
                Optional.empty(), List.of());
        assertEquals(1, callCount.get());
    }

    @Test void finishedStatus_advancesThroughAllTracks(@TempDir Path tempDir) throws Exception {
        var f1 = createTestMidi(tempDir, "a.mid");
        var f2 = createTestMidi(tempDir, "b.mid");
        var f3 = createTestMidi(tempDir, "c.mid");
        var callCount = new AtomicInteger(0);
        PlaybackEngineFactory factory = (seq, p, ctx, pipeline, isShuttingDown, speed, start) -> {
            callCount.incrementAndGet();
            return new MockPlaybackEngine(seq, p, ctx, pipeline, isShuttingDown, speed, start, PlaybackStatus.FINISHED);
        };
        player(factory).play(List.of(f1, f2, f3), new MockMidiProvider(),
                new MidiPort(0, "Mock"), common, new DumbUI(), mockIO,
                Optional.empty(), List.of());
        assertEquals(3, callCount.get());
    }

    @Test void nextStatus_exitsAtNavBoundary(@TempDir Path tempDir) throws Exception {
        var f1 = createTestMidi(tempDir, "a.mid");
        var f2 = createTestMidi(tempDir, "b.mid");
        var f3 = createTestMidi(tempDir, "c.mid");
        var callCount = new AtomicInteger(0);
        PlaybackEngineFactory factory = (seq, p, ctx, pipeline, isShuttingDown, speed, start) -> {
            callCount.incrementAndGet();
            return new MockPlaybackEngine(seq, p, ctx, pipeline, isShuttingDown, speed, start, PlaybackStatus.NEXT);
        };
        var p = new PlaylistPlayer(factory, null, false, false, true, errStream,
                NoOpMediaIntegration.INSTANCE);
        p.play(List.of(f1, f2, f3), new MockMidiProvider(),
                new MidiPort(0, "Mock"), common, new DumbUI(), mockIO,
                Optional.empty(), List.of());
        assertEquals(3, callCount.get());
    }

    @Test void loopEnabled_wrapsBackToFirstTrack(@TempDir Path tempDir) throws Exception {
        var f1 = createTestMidi(tempDir, "a.mid");
        var f2 = createTestMidi(tempDir, "b.mid");
        var f3 = createTestMidi(tempDir, "c.mid");
        var callCount = new AtomicInteger(0);
        PlaybackEngineFactory factory = (seq, p, ctx, pipeline, isShuttingDown, speed, start) -> {
            int call = callCount.incrementAndGet();
            PlaybackStatus status = call < 4 ? PlaybackStatus.FINISHED : PlaybackStatus.QUIT_ALL;
            return new MockPlaybackEngine(seq, p, ctx, pipeline, isShuttingDown, speed, start, status);
        };
        common.loop = true;
        player(factory).play(List.of(f1, f2, f3), new MockMidiProvider(),
                new MidiPort(0, "Mock"), common, new DumbUI(), mockIO,
                Optional.empty(), List.of());
        assertEquals(4, callCount.get());
    }

    @Test void previousStatus_goesBackOneTrack(@TempDir Path tempDir) throws Exception {
        var f1 = createTestMidi(tempDir, "a.mid");
        var f2 = createTestMidi(tempDir, "b.mid");
        var f3 = createTestMidi(tempDir, "c.mid");
        var callCount = new AtomicInteger(0);
        PlaybackEngineFactory factory = (seq, p, ctx, pipeline, isShuttingDown, speed, start) -> {
            int call = callCount.incrementAndGet();
            PlaybackStatus status = switch (call) {
                case 1 -> PlaybackStatus.NEXT;
                case 2 -> PlaybackStatus.PREVIOUS;
                default -> PlaybackStatus.QUIT_ALL;
            };
            return new MockPlaybackEngine(seq, p, ctx, pipeline, isShuttingDown, speed, start, status);
        };
        player(factory).play(List.of(f1, f2, f3), new MockMidiProvider(),
                new MidiPort(0, "Mock"), common, new DumbUI(), mockIO,
                Optional.empty(), List.of());
        assertEquals(3, callCount.get());
    }
}
