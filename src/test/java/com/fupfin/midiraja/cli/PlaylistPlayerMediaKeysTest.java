/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fupfin.midiraja.engine.PlaybackCommands;
import com.fupfin.midiraja.engine.PlaybackEngine.PlaybackStatus;
import com.fupfin.midiraja.engine.PlaybackPipeline;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.media.MediaKeyIntegration;
import com.fupfin.midiraja.media.NowPlayingInfo;
import com.fupfin.midiraja.ui.DumbUI;
import com.fupfin.midiraja.ui.PlaybackEventListener;

class PlaylistPlayerMediaKeysTest
{
    @TempDir
    Path tmp;

    @Test
    void drainAndUpdate_calledAtTrackStart() throws Exception
    {
        var updates = new CopyOnWriteArrayList<NowPlayingInfo>();
        boolean[] startWasCalled = { false };
        var testKeys = new MediaKeyIntegration()
        {
            @Override
            public void start(PlaybackCommands c)
            {
                startWasCalled[0] = true;
            }

            @Override
            public void drainAndUpdate(NowPlayingInfo info)
            {
                updates.add(info);
            }

            @Override
            public void close()
            {
            }
        };

        // Build a MIDI file with a title meta event
        var seq = new Sequence(Sequence.PPQ, 480);
        var track = seq.createTrack();
        var titleMsg = new javax.sound.midi.MetaMessage();
        byte[] titleBytes = "TestSong".getBytes();
        titleMsg.setMessage(0x03, titleBytes, titleBytes.length);
        track.add(new javax.sound.midi.MidiEvent(titleMsg, 0L));
        var eot = new javax.sound.midi.MetaMessage(0x2F, new byte[] {}, 0);
        track.add(new javax.sound.midi.MidiEvent(eot, 10L));

        var midiFile = tmp.resolve("test.mid").toFile();
        MidiSystem.write(seq, 1, midiFile);

        var provider = new PlaylistPlayerTest.MockMidiProvider();

        PlaylistPlayerMediaKeysTest.MinimalEngineFactory factory = (s, p, ctx, pipeline, shutdown, speed,
                start) -> new MinimalEngine(ctx, pipeline);

        var player = new PlaylistPlayer(factory, null, false, true, true,
                System.err, testKeys, java.util.Set.of());

        var mockIO = new MockTerminalIO();
        player.play(List.of(midiFile), provider,
                new com.fupfin.midiraja.midi.MidiPort(0, "test"), new CommonOptions(),
                new DumbUI(), mockIO, Optional.empty(), List.of());

        assertTrue(startWasCalled[0], "start() should be called");
        assertFalse(updates.isEmpty(), "drainAndUpdate should be called at least once");
        assertEquals("TestSong", updates.get(0).title());
    }

    interface MinimalEngineFactory extends com.fupfin.midiraja.engine.PlaybackEngineFactory
    {
        @Override
        MinimalEngine create(Sequence seq, com.fupfin.midiraja.midi.MidiOutProvider p,
                PlaylistContext ctx, PlaybackPipeline pipeline,
                java.util.function.BooleanSupplier isShuttingDown,
                double speed, Optional<Long> start);
    }

    static class MinimalEngine extends PlaylistPlayerTest.MockPlaybackEngine
    {
        MinimalEngine(PlaylistContext ctx, PlaybackPipeline pipeline)
        {
            super(null, null, ctx, pipeline,
                    () -> false, 1.0, Optional.empty(), PlaybackStatus.FINISHED);
        }

        @Override
        public void addPlaybackEventListener(PlaybackEventListener listener)
        {
        }
    }
}
