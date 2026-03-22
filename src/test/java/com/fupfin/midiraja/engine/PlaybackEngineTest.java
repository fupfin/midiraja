/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.DumbUI;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackEngineTest
{
    private Sequence mockSequence;
    private MockMidiProvider mockProvider;
    private MockTerminalIO mockIO;

    static class MockMidiProvider implements MidiOutProvider
    {
        @Override public List<MidiPort> getOutputPorts()
        {
            return new ArrayList<>();
        }

        @Override public void openPort(int portIndex) throws Exception
        {
        }

        @Override public void sendMessage(byte[] data) throws Exception
        {
        }

        @Override public void closePort()
        {
        }

        // Override to avoid the 200ms hardware-flush wait in the default implementation
        @Override public void panic()
        {
        }
    }

    static class RecordingMidiProvider extends MockMidiProvider
    {
        final List<byte[]> messages = new ArrayList<>();

        @Override public void sendMessage(byte[] data) throws Exception
        {
            messages.add(data.clone());
        }
    }

    /** Advances virtual nanos on sleepMillis; never actually sleeps. */
    static class FakeClock implements MidiClock {
        private long nanos = 0;

        @Override public long nanoTime() { return nanos; }

        @Override public void sleepMillis(long ms) throws InterruptedException {
            nanos += ms * 1_000_000L;
        }

        @Override public void onSpinWait() {
            nanos += 1;
        }
    }

    private PlaylistContext ctx()
    {
        return new PlaylistContext(List.of(new File("test.mid")), 0, new MidiPort(0, "Mock"), null, false, false);
    }

    @BeforeEach void setUp() throws Exception
    {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();
        mockProvider = new MockMidiProvider();
        mockIO = new MockTerminalIO();
    }

    @Test void testVolumeControl() throws Exception
    {
        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider,
            new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
            100, 1.0, java.util.Optional.empty(), java.util.Optional.empty());

        mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_DOWN);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(()
                               -> java.lang.ScopedValue.where(TerminalIO.CONTEXT, mockIO)
                                   .call(() -> engine.start(new com.fupfin.midiraja.ui.DumbUI())));
    }

    @Test void testSeekBeyondBoundary() throws Exception
    {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();

        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider,
            new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
            100, 1.0, java.util.Optional.empty(), java.util.Optional.empty());

        // Seek forward multiple times beyond end
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_FORWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_FORWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(()
                               -> java.lang.ScopedValue.where(TerminalIO.CONTEXT, mockIO)
                                   .call(() -> engine.start(new com.fupfin.midiraja.ui.DumbUI())));
    }

    @Test void testSeekBackwardToZero() throws Exception
    {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();

        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider,
            new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
            100, 1.0, java.util.Optional.empty(), java.util.Optional.empty());

        // Seek backward early in the song (should stay at 0)
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_BACKWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(()
                               -> java.lang.ScopedValue.where(TerminalIO.CONTEXT, mockIO)
                                   .call(() -> engine.start(new com.fupfin.midiraja.ui.DumbUI())));
    }

    @Test void testVolumeBoundaries() throws Exception
    {
        PlaybackEngine engine = new PlaybackEngine(mockSequence, mockProvider,
            new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
            100, 1.0, java.util.Optional.empty(), java.util.Optional.empty());

        // Volume down multiple times (should clamp at 0%)
        for (int i = 0; i < 30; i++) mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_DOWN);
        // Volume up multiple times (should clamp at 100%)
        for (int i = 0; i < 30; i++) mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_UP);

        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(()
                               -> java.lang.ScopedValue.where(TerminalIO.CONTEXT, mockIO)
                                   .call(() -> engine.start(new com.fupfin.midiraja.ui.DumbUI())));
    }

    @Test void testSpeedBoundaries()
    {
        PlaybackEngine engine = new PlaybackEngine(
            mockSequence, mockProvider, ctx(), 100, 1.0, Optional.empty(), Optional.empty());

        assertEquals(1.0, engine.getCurrentSpeed(), 1e-9);

        engine.adjustSpeed(10.0); // clamps to 2.0
        assertEquals(2.0, engine.getCurrentSpeed(), 1e-9);

        engine.adjustSpeed(-10.0); // clamps to 0.5
        assertEquals(0.5, engine.getCurrentSpeed(), 1e-9);

        engine.adjustSpeed(0.1); // 0.5 + 0.1 = 0.6
        assertEquals(0.6, engine.getCurrentSpeed(), 1e-6);
    }

    @Test void testTransposeAdjustment()
    {
        PlaybackEngine engine = new PlaybackEngine(
            mockSequence, mockProvider, ctx(), 100, 1.0, Optional.empty(), Optional.empty());

        assertEquals(0, engine.getCurrentTranspose());

        engine.adjustTranspose(2);
        assertEquals(2, engine.getCurrentTranspose());

        engine.adjustTranspose(-5);
        assertEquals(-3, engine.getCurrentTranspose());
    }

    @Test void testTogglePause()
    {
        PlaybackEngine engine = new PlaybackEngine(
            mockSequence, mockProvider, ctx(), 100, 1.0, Optional.empty(), Optional.empty());

        assertFalse(engine.isPaused());
        engine.togglePause();
        assertTrue(engine.isPaused());
        engine.togglePause();
        assertFalse(engine.isPaused());
    }

    @Test void testMidiEventDelivery() throws Exception
    {
        // Build a minimal sequence with a NoteOn and NoteOff at tick 0 (processed immediately)
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0L));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 0L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new PlaybackEngine(
            seq, recording, ctx(), 100, 1000.0, Optional.empty(), Optional.empty());

        ScopedValue.where(TerminalIO.CONTEXT, new MockTerminalIO()).call(() -> {
            engine.start(new DumbUI());
            return null;
        });

        boolean sawNoteOn = recording.messages.stream().anyMatch(
            m -> (m[0] & 0xF0) == 0x90 && (m[1] & 0xFF) == 60 && (m[2] & 0xFF) == 100);
        boolean sawNoteOff =
            recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0x80 && (m[1] & 0xFF) == 60);
        assertTrue(sawNoteOn, "NoteOn(ch0, key60, vel100) should reach the MIDI provider");
        assertTrue(sawNoteOff, "NoteOff(ch0, key60) should reach the MIDI provider");
    }

    @Test void testSeekForwardReplaysStateEvents() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0xC0, 0, 5, 0), 0L));      // PC ch0 prog=5
        track.add(new MidiEvent(new ShortMessage(0xB0, 0, 7, 100), 0L));    // CC ch0 vol=100
        track.add(new MidiEvent(new ShortMessage(0xE0, 0, 0, 64), 0L));     // PitchBend ch0
        track.add(new MidiEvent(new ShortMessage(0x90, 0, 60, 100), 0L));   // NoteOn ch0
        track.add(new MidiEvent(new ShortMessage(0x80, 0, 60, 0), 24L));    // NoteOff ch0
        // Extend sequence past 2s (96 ticks at 120BPM/PPQ=24) so seek target is ~96, not 24
        MetaMessage padding = new MetaMessage(0x01, new byte[]{}, 0);
        track.add(new MidiEvent(padding, 200L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new PlaybackEngine(
            seq, recording, ctx(), 100, 1000.0, Optional.of("0:02"), Optional.empty());

        ScopedValue.where(TerminalIO.CONTEXT, new MockTerminalIO()).call(() -> {
            engine.start(new DumbUI());
            return null;
        });

        boolean hasPC = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0xC0);
        boolean hasCC = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0xB0);
        boolean hasPB = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0xE0);
        boolean hasNoteOn = recording.messages.stream().anyMatch(
            m -> (m[0] & 0xF0) == 0x90 && m.length >= 3 && (m[2] & 0xFF) > 0);
        boolean hasNoteOff = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0x80);

        assertTrue(hasPC, "ProgramChange should be replayed during chase");
        assertTrue(hasCC, "ControlChange should be replayed during chase");
        assertTrue(hasPB, "PitchBend should be replayed during chase");
        assertFalse(hasNoteOn, "NoteOn should NOT be sent during chase");
        assertFalse(hasNoteOff, "NoteOff should NOT be sent during chase");
    }

    @Test void testChannelProgramsUpdatedAfterSeek() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0xC0, 0, 5, 0), 0L));   // PC ch0 prog=5
        track.add(new MidiEvent(new ShortMessage(0xC0, 3, 42, 0), 12L)); // PC ch3 prog=42
        // Extend sequence past 2s so seek target is ~96 ticks, not the sequence end
        MetaMessage padding = new MetaMessage(0x01, new byte[]{}, 0);
        track.add(new MidiEvent(padding, 200L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new PlaybackEngine(
            seq, recording, ctx(), 100, 1000.0, Optional.of("0:02"), Optional.empty());

        ScopedValue.where(TerminalIO.CONTEXT, new MockTerminalIO()).call(() -> {
            engine.start(new DumbUI());
            return null;
        });

        assertEquals(5, engine.getChannelPrograms()[0],
            "channelPrograms[0] should be 5 after seek past PC");
        assertEquals(42, engine.getChannelPrograms()[3],
            "channelPrograms[3] should be 42 after seek past PC");
    }

    @Test void testChannelPressureForwardedDuringChase() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0xD0, 0, 80, 0), 0L)); // ChannelPressure ch0
        // Extend sequence past 2s so seek target is ~96 ticks, not the sequence end
        MetaMessage padding = new MetaMessage(0x01, new byte[]{}, 0);
        track.add(new MidiEvent(padding, 200L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new PlaybackEngine(
            seq, recording, ctx(), 100, 1000.0, Optional.of("0:02"), Optional.empty());

        ScopedValue.where(TerminalIO.CONTEXT, new MockTerminalIO()).call(() -> {
            engine.start(new DumbUI());
            return null;
        });

        boolean hasCP = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0xD0);
        assertTrue(hasCP, "ChannelPressure should be forwarded during chase");
    }

    @Test void testToggleLoop() {
        PlaybackEngine engine = new PlaybackEngine(
            mockSequence, mockProvider, ctx(), 100, 1.0, Optional.empty(), Optional.empty());

        assertTrue(engine.isLoopEnabled() == false); // default: loop off
        engine.toggleLoop();
        assertTrue(engine.isLoopEnabled());
        engine.toggleLoop();
        assertFalse(engine.isLoopEnabled());
    }

    @Test void testLoopInitializedFromContext() {
        var ctxWithLoop = new PlaylistContext(
            List.of(new File("test.mid")), 0, new MidiPort(0, "Mock"), null, true, false);
        PlaybackEngine engine = new PlaybackEngine(
            mockSequence, mockProvider, ctxWithLoop, 100, 1.0, Optional.empty(), Optional.empty());
        assertTrue(engine.isLoopEnabled());
    }

    @Test void testToggleShuffle() {
        PlaybackEngine engine = new PlaybackEngine(
            mockSequence, mockProvider, ctx(), 100, 1.0, Optional.empty(), Optional.empty());

        assertFalse(engine.isShuffleEnabled());
        engine.toggleShuffle();
        assertTrue(engine.isShuffleEnabled());
    }

    @Test void testShuffleCallbackFired() {
        PlaybackEngine engine = new PlaybackEngine(
            mockSequence, mockProvider, ctx(), 100, 1.0, Optional.empty(), Optional.empty());

        List<Boolean> received = new ArrayList<>();
        engine.setShuffleCallback(received::add);

        engine.toggleShuffle(); // false → true
        engine.toggleShuffle(); // true → false

        assertEquals(List.of(true, false), received);
    }

    @Test void clock_injection_constructor_compiles() throws Exception {
        var clock = new FakeClock();
        var engine = new PlaybackEngine(mockSequence, mockProvider, ctx(),
                100, 1.0, Optional.empty(), Optional.empty(), clock);
        assertNotNull(engine);
    }
}
