/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.midi.SysexFilter;
import com.fupfin.midiraja.midi.TransposeFilter;
import com.fupfin.midiraja.midi.VolumeFilter;
import com.fupfin.midiraja.ui.DumbUI;
import com.fupfin.midiraja.ui.PlaybackEventListener;

class PlaybackEngineTest
{
    private Sequence mockSequence;
    private MockMidiProvider mockProvider;
    private MockTerminalIO mockIO;

    static class MockMidiProvider implements MidiOutProvider
    {
        @Override
        public List<MidiPort> getOutputPorts()
        {
            return new ArrayList<>();
        }

        @Override
        public void openPort(int portIndex) throws Exception
        {
        }

        @Override
        public void sendMessage(byte[] data) throws Exception
        {
        }

        @Override
        public void closePort()
        {
        }

        // Override to avoid the 200ms hardware-flush wait in the default implementation
        @Override
        public void panic()
        {
        }
    }

    static class RecordingMidiProvider extends MockMidiProvider
    {
        final List<byte[]> messages = new ArrayList<>();

        @Override
        public void sendMessage(byte[] data) throws Exception
        {
            messages.add(data.clone());
        }
    }

    /** Advances virtual nanos on sleepMillis; never actually sleeps. */
    static class FakeClock implements MidiClock
    {
        private long nanos = 0;

        @Override
        public long nanoTime()
        {
            return nanos;
        }

        @Override
        public void sleepMillis(long ms) throws InterruptedException
        {
            nanos += ms * 1_000_000L;
        }

        @Override
        public void onSpinWait()
        {
            nanos += 1;
        }
    }

    private PlaylistContext ctx()
    {
        return new PlaylistContext(List.of(new File("test.mid")), 0, new MidiPort(0, "Mock"), null, false, false);
    }

    private static PlaybackPipeline testPipeline(MidiOutProvider provider)
    {
        var sysexFilter = new SysexFilter(provider, false);
        var volumeFilter = new VolumeFilter(sysexFilter, 1.0);
        var transposeFilter = new TransposeFilter(volumeFilter, 0);
        return new PlaybackPipeline()
        {
            @Override
            public void sendMessage(byte[] d) throws Exception
            {
                transposeFilter.sendMessage(d);
            }

            @Override
            public void adjustVolume(double d)
            {
                volumeFilter.adjust(d);
            }

            @Override
            public double getVolumeScale()
            {
                return volumeFilter.getVolumeScale();
            }

            @Override
            public void adjustTranspose(int s)
            {
                transposeFilter.adjust(s);
                try
                {
                    provider.panic();
                }
                catch (Exception ignored)
                {}
            }

            @Override
            public int getCurrentTranspose()
            {
                return transposeFilter.getSemitones();
            }

            @Override
            public void setIgnoreSysex(boolean i)
            {
                sysexFilter.setIgnoreSysex(i);
            }
        };
    }

    private static PlaybackEngine.PlaybackStatus runEngine(PlaybackEngine engine, TerminalIO io) throws Exception
    {
        return ScopedValue.where(TerminalIO.CONTEXT, io).call(() -> engine.start(new DumbUI()));
    }

    @BeforeEach
    void setUp() throws Exception
    {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();
        mockProvider = new MockMidiProvider();
        mockIO = new MockTerminalIO();
    }

    @Test
    void testVolumeControl() throws Exception
    {
        PlaybackEngine engine = new MidiPlaybackEngine(mockSequence, mockProvider,
                new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                        new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
                testPipeline(mockProvider), () -> false,
                1.0, java.util.Optional.empty());

        mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_DOWN);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(() -> runEngine(engine, mockIO));
    }

    @Test
    void testSeekBeyondBoundary() throws Exception
    {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();

        PlaybackEngine engine = new MidiPlaybackEngine(mockSequence, mockProvider,
                new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                        new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
                testPipeline(mockProvider), () -> false,
                1.0, java.util.Optional.empty());

        // Seek forward multiple times beyond end
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_FORWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_FORWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(() -> runEngine(engine, mockIO));
    }

    @Test
    void testSeekBackwardToZero() throws Exception
    {
        mockSequence = new Sequence(Sequence.PPQ, 24);
        mockSequence.createTrack();

        PlaybackEngine engine = new MidiPlaybackEngine(mockSequence, mockProvider,
                new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                        new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
                testPipeline(mockProvider), () -> false,
                1.0, java.util.Optional.empty());

        // Seek backward early in the song (should stay at 0)
        mockIO.injectKey(TerminalIO.TerminalKey.SEEK_BACKWARD);
        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(() -> runEngine(engine, mockIO));
    }

    @Test
    void testVolumeBoundaries() throws Exception
    {
        PlaybackEngine engine = new MidiPlaybackEngine(mockSequence, mockProvider,
                new PlaylistContext(java.util.List.of(new java.io.File("test.mid")), 0,
                        new com.fupfin.midiraja.midi.MidiPort(0, "Mock"), null, false, false),
                testPipeline(mockProvider), () -> false,
                1.0, java.util.Optional.empty());

        // Volume down multiple times (should clamp at 0%)
        for (int i = 0; i < 30; i++)
            mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_DOWN);
        // Volume up multiple times (should clamp at 100%)
        for (int i = 0; i < 30; i++)
            mockIO.injectKey(TerminalIO.TerminalKey.VOLUME_UP);

        mockIO.injectKey(TerminalIO.TerminalKey.QUIT);

        assertDoesNotThrow(() -> runEngine(engine, mockIO));
    }

    @Test
    void testSpeedBoundaries()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        assertEquals(1.0, engine.getCurrentSpeed(), 1e-9);

        engine.adjustSpeed(10.0); // clamps to 2.0
        assertEquals(2.0, engine.getCurrentSpeed(), 1e-9);

        engine.adjustSpeed(-10.0); // clamps to 0.5
        assertEquals(0.5, engine.getCurrentSpeed(), 1e-9);

        engine.adjustSpeed(0.1); // 0.5 + 0.1 = 0.6
        assertEquals(0.6, engine.getCurrentSpeed(), 1e-6);
    }

    @Test
    void testTransposeAdjustment()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        assertEquals(0, engine.getCurrentTranspose());

        engine.adjustTranspose(2);
        assertEquals(2, engine.getCurrentTranspose());

        engine.adjustTranspose(-5);
        assertEquals(-3, engine.getCurrentTranspose());
    }

    @Test
    void testTogglePause()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        assertFalse(engine.isPaused());
        engine.togglePause();
        assertTrue(engine.isPaused());
        engine.togglePause();
        assertFalse(engine.isPaused());
    }

    @Test
    void testMidiEventDelivery() throws Exception
    {
        // Build a minimal sequence with a NoteOn and NoteOff at tick 0 (processed immediately)
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0L));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 0L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, recording, ctx(), testPipeline(recording), () -> false,
                1000.0, Optional.empty());

        runEngine(engine, new MockTerminalIO());

        boolean sawNoteOn = recording.messages.stream().anyMatch(
                m -> (m[0] & 0xF0) == 0x90 && (m[1] & 0xFF) == 60 && (m[2] & 0xFF) == 100);
        boolean sawNoteOff = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0x80 && (m[1] & 0xFF) == 60);
        assertTrue(sawNoteOn, "NoteOn(ch0, key60, vel100) should reach the MIDI provider");
        assertTrue(sawNoteOff, "NoteOff(ch0, key60) should reach the MIDI provider");
    }

    @Test
    void testSeekForwardReplaysStateEvents() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0xC0, 0, 5, 0), 0L)); // PC ch0 prog=5
        track.add(new MidiEvent(new ShortMessage(0xB0, 0, 7, 100), 0L)); // CC ch0 vol=100
        track.add(new MidiEvent(new ShortMessage(0xE0, 0, 0, 64), 0L)); // PitchBend ch0
        track.add(new MidiEvent(new ShortMessage(0x90, 0, 60, 100), 0L)); // NoteOn ch0
        track.add(new MidiEvent(new ShortMessage(0x80, 0, 60, 0), 24L)); // NoteOff ch0
        // Extend sequence past 2s (96 ticks at 120BPM/PPQ=24) so seek target is ~96, not 24
        MetaMessage padding = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(padding, 200L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, recording, ctx(), testPipeline(recording), () -> false,
                1000.0, Optional.of(2_000_000L));

        runEngine(engine, new MockTerminalIO());

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

    @Test
    void testChannelProgramsUpdatedAfterSeek() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0xC0, 0, 5, 0), 0L)); // PC ch0 prog=5
        track.add(new MidiEvent(new ShortMessage(0xC0, 3, 42, 0), 12L)); // PC ch3 prog=42
        // Extend sequence past 2s so seek target is ~96 ticks, not the sequence end
        MetaMessage padding = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(padding, 200L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, recording, ctx(), testPipeline(recording), () -> false,
                1000.0, Optional.of(2_000_000L));

        runEngine(engine, new MockTerminalIO());

        assertEquals(5, engine.getChannelPrograms()[0],
                "channelPrograms[0] should be 5 after seek past PC");
        assertEquals(42, engine.getChannelPrograms()[3],
                "channelPrograms[3] should be 42 after seek past PC");
    }

    @Test
    void testChannelPressureForwardedDuringChase() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0xD0, 0, 80, 0), 0L)); // ChannelPressure ch0
        // Extend sequence past 2s so seek target is ~96 ticks, not the sequence end
        MetaMessage padding = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(padding, 200L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, recording, ctx(), testPipeline(recording), () -> false,
                1000.0, Optional.of(2_000_000L));

        runEngine(engine, new MockTerminalIO());

        boolean hasCP = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0xD0);
        assertTrue(hasCP, "ChannelPressure should be forwarded during chase");
    }

    @Test
    void testToggleLoop()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        assertTrue(engine.isLoopEnabled() == false); // default: loop off
        engine.toggleLoop();
        assertTrue(engine.isLoopEnabled());
        engine.toggleLoop();
        assertFalse(engine.isLoopEnabled());
    }

    @Test
    void testLoopInitializedFromContext()
    {
        var ctxWithLoop = new PlaylistContext(
                List.of(new File("test.mid")), 0, new MidiPort(0, "Mock"), null, true, false);
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctxWithLoop, testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());
        assertTrue(engine.isLoopEnabled());
    }

    @Test
    void testToggleShuffle()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        assertFalse(engine.isShuffleEnabled());
        engine.toggleShuffle();
        assertTrue(engine.isShuffleEnabled());
    }

    @Test
    void testShuffleCallbackFired()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        List<Boolean> received = new ArrayList<>();
        engine.setShuffleCallback(received::add);

        engine.toggleShuffle(); // false → true
        engine.toggleShuffle(); // true → false

        assertEquals(List.of(true, false), received);
    }

    @Test
    void clock_injection_constructor_compiles() throws Exception
    {
        var clock = new FakeClock();
        var engine = new MidiPlaybackEngine(mockSequence, mockProvider, ctx(),
                testPipeline(mockProvider), () -> false, 1.0, Optional.empty(), clock);
        assertNotNull(engine);
    }

    @Test
    void fakeClock_nanoTime_advancesWithSleep() throws InterruptedException
    {
        var clock = new FakeClock();
        long t0 = clock.nanoTime();
        clock.sleepMillis(100);
        long t1 = clock.nanoTime();
        clock.onSpinWait();
        long t2 = clock.nanoTime();

        assertEquals(100_000_000L, t1 - t0, "sleepMillis(100) should advance by 100ms in nanos");
        assertTrue(t2 > t1, "onSpinWait() should advance nanos by at least 1");
    }

    /**
     * After the last event, playLoop() sleeps END_OF_TRACK_MS (20 ms) before returning.
     * FakeClock confirms the delay is observed without real wall-clock time passing.
     *
     * Code path: empty track → sortedEvents has one End-of-Track meta at tick 0 →
     * outer while loop exits after processing it (eventIndex == sortedEvents.size()) →
     * falls through to the END_OF_TRACK_MS sleep.
     */
    @Test
    void endOfTrack_delay_isObserved() throws Exception
    {
        var clock = new FakeClock();
        // createTrack() inserts a single End-of-Track meta at tick 0.
        var singleEventSeq = new Sequence(Sequence.PPQ, 480);
        singleEventSeq.createTrack();

        var engine = new MidiPlaybackEngine(singleEventSeq, mockProvider, ctx(),
                testPipeline(mockProvider), () -> false, 1.0, Optional.empty(), clock);

        ScopedValue.where(TerminalIO.CONTEXT, mockIO)
                .call(() -> engine.start(new DumbUI()));

        // Startup delay: 50 × sleepMillis(10) = 500ms fake.
        // End-of-track delay: sleepMillis(20) = 20ms fake.
        // Total minimum = 520ms = 520,000,000 ns.
        assertTrue(clock.nanoTime() >= 520_000_000L,
                "Expected startup + end-of-track delay >= 520ms, got " + clock.nanoTime() + " ns");
    }

    // -------------------------------------------------------------------------
    // A1. ignoreSysex_true_blocksAllSysexMessages
    // -------------------------------------------------------------------------
    @Test
    void ignoreSysex_true_blocksAllSysexMessages() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new SysexMessage(new byte[] { (byte) 0xF0, 0x00, (byte) 0xF7 }, 3), 0L));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(seq, recording, ctx(),
                testPipeline(recording), () -> false, 1000.0, Optional.empty(), clock);
        engine.setIgnoreSysex(true);

        runEngine(engine, new MockTerminalIO());

        boolean sawNoteOn = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0x90);
        boolean sawSysex = recording.messages.stream().anyMatch(m -> (m[0] & 0xFF) == 0xF0);
        assertTrue(sawNoteOn, "NoteOn should be delivered when ignoreSysex=true");
        assertFalse(sawSysex, "SysEx should be blocked when ignoreSysex=true");
    }

    // -------------------------------------------------------------------------
    // A2. ignoreSysex_false_forwardsSysexMessages
    // -------------------------------------------------------------------------
    @Test
    void ignoreSysex_false_forwardsSysexMessages() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new SysexMessage(new byte[] { (byte) 0xF0, 0x00, (byte) 0xF7 }, 3), 0L));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(seq, recording, ctx(),
                testPipeline(recording), () -> false, 1000.0, Optional.empty(), clock);
        engine.setIgnoreSysex(false); // default — explicit here for clarity

        runEngine(engine, new MockTerminalIO());

        boolean sawNoteOn = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0x90);
        boolean sawSysex = recording.messages.stream().anyMatch(m -> (m[0] & 0xFF) == 0xF0);
        assertTrue(sawNoteOn, "NoteOn should be delivered when ignoreSysex=false");
        assertTrue(sawSysex, "SysEx should be delivered when ignoreSysex=false");
    }

    // -------------------------------------------------------------------------
    // A3. initialResetType_gm_sendsExpectedBytes
    // -------------------------------------------------------------------------
    @Test
    void initialResetType_gm_sendsExpectedBytes() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        seq.createTrack(); // empty track (just End-of-Track)

        RecordingMidiProvider recording = new RecordingMidiProvider();
        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(seq, recording, ctx(),
                testPipeline(recording), () -> false, 1000.0, Optional.empty(), clock);
        engine.setInitialResetType(Optional.of("gm"));

        runEngine(engine, new MockTerminalIO());

        byte[] expected = new byte[] { (byte) 0xF0, 0x7E, 0x7F, 0x09, 0x01, (byte) 0xF7 };
        boolean found = recording.messages.stream().anyMatch(m -> Arrays.equals(m, expected));
        assertTrue(found, "GM reset SysEx should have been sent");
    }

    // -------------------------------------------------------------------------
    // A4. initialResetType_hexString_sendsDecodedBytes
    // -------------------------------------------------------------------------
    @Test
    void initialResetType_hexString_sendsDecodedBytes() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        seq.createTrack();

        RecordingMidiProvider recording = new RecordingMidiProvider();
        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(seq, recording, ctx(),
                testPipeline(recording), () -> false, 1000.0, Optional.empty(), clock);
        engine.setInitialResetType(Optional.of("F07E7F09010F")); // 12 hex chars = 6 bytes

        runEngine(engine, new MockTerminalIO());

        byte[] expected = new byte[] { (byte) 0xF0, 0x7E, 0x7F, 0x09, 0x01, 0x0F };
        boolean found = recording.messages.stream().anyMatch(m -> Arrays.equals(m, expected));
        assertTrue(found, "Hex-decoded reset bytes should have been sent");
    }

    // -------------------------------------------------------------------------
    // A5. initialResetType_oddLengthHex_ignored
    // -------------------------------------------------------------------------
    @Test
    void initialResetType_oddLengthHex_ignored() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        seq.createTrack();

        RecordingMidiProvider recording = new RecordingMidiProvider();
        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(seq, recording, ctx(),
                testPipeline(recording), () -> false, 1000.0, Optional.empty(), clock);
        engine.setInitialResetType(Optional.of("F07")); // odd length — should be ignored

        runEngine(engine, new MockTerminalIO());

        // Only the End-of-Track meta may arrive; no SysEx / reset bytes expected
        boolean sawSysex = recording.messages.stream().anyMatch(m -> (m[0] & 0xFF) == 0xF0);
        assertFalse(sawSysex, "Odd-length hex reset should be ignored");
    }

    // -------------------------------------------------------------------------
    // A6. initialResetType_unknownString_ignored
    // -------------------------------------------------------------------------
    @Test
    void initialResetType_unknownString_ignored() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        seq.createTrack();

        RecordingMidiProvider recording = new RecordingMidiProvider();
        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(seq, recording, ctx(),
                testPipeline(recording), () -> false, 1000.0, Optional.empty(), clock);
        engine.setInitialResetType(Optional.of("unknown-format"));

        runEngine(engine, new MockTerminalIO());

        boolean sawSysex = recording.messages.stream().anyMatch(m -> (m[0] & 0xFF) == 0xF0);
        assertFalse(sawSysex, "Unknown reset type should be ignored");
    }

    // -------------------------------------------------------------------------
    // A7. bookmarkCallback_firedOnFireBookmark
    // -------------------------------------------------------------------------
    @Test
    void bookmarkCallback_firedOnFireBookmark()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        List<Boolean> received = new ArrayList<>();
        engine.setBookmarkCallback(received::add);

        engine.fireBookmark(); // false → true
        engine.fireBookmark(); // true → false

        assertEquals(List.of(true, false), received);
    }

    // -------------------------------------------------------------------------
    // A8. setFilterDescription_getFilterDescription_roundTrip
    // -------------------------------------------------------------------------
    @Test
    void setFilterDescription_getFilterDescription_roundTrip()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        engine.setFilterDescription("EQ+Reverb");
        assertEquals("EQ+Reverb", engine.getFilterDescription());
    }

    // -------------------------------------------------------------------------
    // A9. setPortSuffix_getPortSuffix_roundTrip
    // -------------------------------------------------------------------------
    @Test
    void setPortSuffix_getPortSuffix_roundTrip()
    {
        PlaybackEngine engine = new MidiPlaybackEngine(
                mockSequence, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        engine.setPortSuffix("[MT-32]");
        assertEquals("[MT-32]", engine.getPortSuffix());
    }

    // -------------------------------------------------------------------------
    // A10. startup_abortedByRequestStop_returnsEarlyWithNoMidiEvents
    // -------------------------------------------------------------------------
    @Test
    @Timeout(5)
    void startup_abortedByRequestStop_returnsEarlyWithNoMidiEvents() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        // Use the 7-arg constructor (real system clock, NOT FakeClock) so real time passes
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, recording, ctx(), testPipeline(recording), () -> false, 1.0, Optional.empty());

        CompletableFuture<PlaybackStatus> future = new CompletableFuture<>();
        var mockIO2 = new MockTerminalIO(); // no keys injected — engine will wait
        Thread.ofVirtual().start(() -> startEngineAsync(engine, mockIO2, future));

        // Wait a moment inside the 500ms startup window, then stop
        Thread.sleep(50);
        engine.requestStop(PlaybackStatus.NEXT);

        PlaybackStatus result = future.get();
        assertEquals(PlaybackStatus.NEXT, result);
        // No MIDI content messages should have been sent (aborted during startup delay)
        boolean sawNoteOn = recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0x90);
        assertFalse(sawNoteOn, "No NoteOn events should have been sent when stopped during startup delay");
    }

    // -------------------------------------------------------------------------
    // A11. setInitiallyPaused_engineStartsPaused
    // -------------------------------------------------------------------------
    @Test
    void setInitiallyPaused_engineStartsPaused() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0L));

        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1000.0, Optional.empty(), clock);
        engine.setInitiallyPaused();

        assertTrue(engine.isPaused(), "Engine should report paused immediately after setInitiallyPaused()");

        // Unpause so the engine can complete
        engine.togglePause();
        assertFalse(engine.isPaused(), "Engine should be unpaused after togglePause()");
    }

    // -------------------------------------------------------------------------
    // B1. getTickForTime_acrossTempoChange_seeksCorrectly
    // -------------------------------------------------------------------------
    @Test
    void getTickForTime_acrossTempoChange_seeksCorrectly() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        // tick 0: PC ch0 prog=3 (default 120 BPM section)
        track.add(new MidiEvent(new ShortMessage(0xC0, 0, 3, 0), 0L));
        // tick 480 = 0.5s @120BPM: tempo change → 60 BPM (1,000,000 µs/beat)
        MetaMessage tempo60 = new MetaMessage();
        tempo60.setMessage(0x51, new byte[] { 0x0F, 0x42, 0x40 }, 3);
        track.add(new MidiEvent(tempo60, 480L));
        // tick 960 = 0.5s + 1s @60BPM = 1.5s total: CC ch0 vol=80
        track.add(new MidiEvent(new ShortMessage(0xB0, 0, 7, 80), 960L));
        MetaMessage pad = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(pad, 1920L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, recording, ctx(), testPipeline(recording), () -> false,
                1000.0, Optional.of(2_500_000L)); // seek past both tempo sections

        runEngine(engine, new MockTerminalIO());

        assertTrue(recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0xC0),
                "PC should be chased (before tempo change)");
        assertTrue(recording.messages.stream().anyMatch(m -> (m[0] & 0xF0) == 0xB0),
                "CC should be chased (after tempo change)");
    }

    // -------------------------------------------------------------------------
    // B2. seekWhilePaused_thenUnpause_resumesFromNewPosition
    // -------------------------------------------------------------------------
    @Test
    @Timeout(5)
    void seekWhilePaused_thenUnpause_resumesFromNewPosition() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0xC0, 0, 7, 0), 0L));
        MetaMessage pad = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(pad, 500L));

        RecordingMidiProvider recording = new RecordingMidiProvider();
        // Use real clock so the pause loop actually blocks and allows interleaving
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, recording, ctx(), testPipeline(recording), () -> false,
                1000.0, Optional.empty());
        engine.setInitiallyPaused();

        CompletableFuture<PlaybackEngine.PlaybackStatus> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> startEngineAsync(engine, new MockTerminalIO(), future));

        // Wait for engine to enter paused state (after startup delay)
        Thread.sleep(600);
        engine.seekRelative(1_000_000L);
        engine.togglePause(); // resume

        assertEquals(PlaybackEngine.PlaybackStatus.FINISHED, future.get());
    }

    // -------------------------------------------------------------------------
    // B3. rapidConsecutiveSeeks_doNotThrow
    // -------------------------------------------------------------------------
    @Test
    @Timeout(5)
    void rapidConsecutiveSeeks_doNotThrow() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        for (int i = 0; i < 20; i++)
            track.add(new MidiEvent(new ShortMessage(0xB0, 0, 7, i * 5), i * 100L));
        MetaMessage pad = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(pad, 5000L));

        // Use real clock so engine actually runs and seeks are meaningful
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());

        CompletableFuture<PlaybackEngine.PlaybackStatus> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> startEngineAsync(engine, new MockTerminalIO(), future));

        Thread.sleep(200);
        for (int i = 0; i < 10; i++)
            engine.seekRelative(200_000L);
        for (int i = 0; i < 10; i++)
            engine.seekRelative(-200_000L);
        engine.requestStop(PlaybackEngine.PlaybackStatus.FINISHED);

        assertDoesNotThrow(() -> future.get());
    }

    // -------------------------------------------------------------------------
    // B4. tempoChangeMeta_updateCurrentBpm
    // -------------------------------------------------------------------------
    @Test
    void tempoChangeMeta_updateCurrentBpm() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        MetaMessage tempo60 = new MetaMessage();
        tempo60.setMessage(0x51, new byte[] { 0x0F, 0x42, 0x40 }, 3); // 60 BPM
        track.add(new MidiEvent(tempo60, 0L));
        MetaMessage pad = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(pad, 100L));

        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1000.0, Optional.empty(), clock);

        runEngine(engine, mockIO);

        assertEquals(60.0f, engine.getCurrentBpm(), 0.5f,
                "getCurrentBpm() should reflect the tempo meta event");
    }

    // -------------------------------------------------------------------------
    // B5. addPlaybackEventListener_receivesOnTickNotification
    // -------------------------------------------------------------------------
    @Test
    void addPlaybackEventListener_receivesOnTickNotification() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0x90, 0, 60, 64), 0L));
        MetaMessage pad = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(pad, 100L));

        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1000.0, Optional.empty(), clock);

        List<Long> ticks = new ArrayList<>();
        engine.addPlaybackEventListener(new PlaybackEventListener()
        {
            @Override
            public void onPlaybackStateChanged()
            {
            }

            @Override
            public void onTick(long microseconds)
            {
                ticks.add(microseconds);
            }

            @Override
            public void onTempoChanged(float bpm)
            {
            }

            @Override
            public void onChannelActivity(int ch, int vel)
            {
            }
        });

        runEngine(engine, mockIO);

        assertFalse(ticks.isEmpty(), "onTick should have been called at least once");
    }

    // -------------------------------------------------------------------------
    // B6. channelActivityListener_receivesNoteOnEvent
    // -------------------------------------------------------------------------
    @Test
    void channelActivityListener_receivesNoteOnEvent() throws Exception
    {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(0x90, 0, 60, 127), 0L)); // NoteOn ch0 vel=127
        MetaMessage pad = new MetaMessage(0x01, new byte[] {}, 0);
        track.add(new MidiEvent(pad, 100L));

        var clock = new FakeClock();
        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1000.0, Optional.empty(), clock);

        List<int[]> activity = new ArrayList<>();
        engine.addPlaybackEventListener(new PlaybackEventListener()
        {
            @Override
            public void onPlaybackStateChanged()
            {
            }

            @Override
            public void onTick(long microseconds)
            {
            }

            @Override
            public void onTempoChanged(float bpm)
            {
            }

            @Override
            public void onChannelActivity(int ch, int vel)
            {
                activity.add(new int[] { ch, vel });
            }
        });

        runEngine(engine, mockIO);

        assertTrue(activity.stream().anyMatch(a -> a[0] == 0 && a[1] == 127),
                "onChannelActivity should have been called with channel=0, velocity=127");
    }

    // -------------------------------------------------------------------------
    // A12. setHoldAtEnd_true_engineWaitsUntilRequestStop
    // -------------------------------------------------------------------------
    @Test
    @Timeout(5)
    void setHoldAtEnd_true_engineWaitsUntilRequestStop() throws Exception
    {
        // One NoteOn at tick 0; FakeClock so playback completes instantly
        Sequence seq = new Sequence(Sequence.PPQ, 24);
        Track track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 64), 0L));

        PlaybackEngine engine = new MidiPlaybackEngine(
                seq, mockProvider, ctx(), testPipeline(mockProvider), () -> false,
                1.0, Optional.empty());
        engine.setHoldAtEnd(true);

        CompletableFuture<PlaybackStatus> future = new CompletableFuture<>();
        var mockIO2 = new MockTerminalIO();
        Thread.ofVirtual().start(() -> startEngineAsync(engine, mockIO2, future));

        // Wait for engine to reach end-of-track and start holding
        long deadline = System.currentTimeMillis() + 5000;
        while (!engine.isPlaying() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(10);
        }
        // Wait a bit more so the engine reaches holdAtEnd state
        Thread.sleep(600); // at least startup delay (500ms) + time to process events
        assertTrue(engine.isPlaying(), "Engine should still be playing (holdAtEnd=true)");

        // Now request stop to release the hold
        engine.requestStop(PlaybackStatus.FINISHED);
        PlaybackStatus result = future.get();
        assertEquals(PlaybackStatus.FINISHED, result);
    }

    private static void startEngineAsync(PlaybackEngine engine, TerminalIO io,
            CompletableFuture<PlaybackStatus> future)
    {
        try
        {
            PlaybackStatus status = ScopedValue.where(TerminalIO.CONTEXT, io)
                    .call(() -> engine.start(new DumbUI()));
            future.complete(status);
        }
        catch (Exception e)
        {
            future.completeExceptionally(e);
        }
    }
}
