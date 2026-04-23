/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.vgm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.dsp.SpectrumAnalyzerFilter;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.midi.MidiPort;
import com.fupfin.midiraja.ui.PlaybackEventListener;

class LibvgmPlaybackEngineTest
{
    @Test
    void spectrumMode_trueForDirectVgmWithoutMidiTimeline() throws Exception
    {
        var sequence = new Sequence(Sequence.PPQ, 480);
        var engine = new LibvgmPlaybackEngine(sequence, null, context());
        engine.setSpectrumFilter(new SpectrumAnalyzerFilter((left, right, frames) -> {}));

        assertTrue(engine.isSpectrumMode());
    }

    @Test
    void spectrumMode_falseForMidiDerivedTimelineEvenWithSpectrumFilter() throws Exception
    {
        var sequence = buildTimelineSequence();
        var engine = new LibvgmPlaybackEngine(sequence, null, context());
        engine.setSpectrumFilter(new SpectrumAnalyzerFilter((left, right, frames) -> {}));

        assertFalse(engine.isSpectrumMode());
    }

    @Test
    void updateMidiVisualization_updatesProgramAndFiresChannelActivity() throws Exception
    {
        var sequence = buildTimelineSequence();
        var engine = new LibvgmPlaybackEngine(sequence, null, context());
        var listener = new CaptureListener();
        engine.addPlaybackEventListener(listener);

        engine.updateMidiVisualization(200_000);
        int[] expectedPrograms = new int[16];
        expectedPrograms[0] = 41;
        assertArrayEquals(expectedPrograms, listener.programs(engine));
        assertFalse(listener.fired);

        engine.updateMidiVisualization(300_000);
        assertArrayEquals(expectedPrograms, listener.programs(engine));
        assertTrue(listener.fired);
        assertEquals(0, listener.channel);
        assertEquals(100, listener.velocity);
    }

    private static Sequence buildTimelineSequence() throws Exception
    {
        var sequence = new Sequence(Sequence.PPQ, 480);
        var track = sequence.createTrack();

        var pc = new ShortMessage();
        pc.setMessage(ShortMessage.PROGRAM_CHANGE, 0, 41, 0);
        track.add(new MidiEvent(pc, 120)); // 125ms @ 120BPM

        var noteOn = new ShortMessage();
        noteOn.setMessage(ShortMessage.NOTE_ON, 0, 60, 100);
        track.add(new MidiEvent(noteOn, 240)); // 250ms @ 120BPM

        return sequence;
    }

    private static PlaylistContext context()
    {
        return new PlaylistContext(List.of(new File("song.mid")), 0, new MidiPort(0, "vgm"), null, false, false);
    }

    private static final class CaptureListener implements PlaybackEventListener
    {
        boolean fired = false;
        int channel = -1;
        int velocity = -1;

        @Override
        public void onPlaybackStateChanged()
        {
        }

        @Override
        public void onTick(long currentMicroseconds)
        {
        }

        @Override
        public void onTempoChanged(float bpm)
        {
        }

        @Override
        public void onChannelActivity(int channel, int velocity)
        {
            this.fired = true;
            this.channel = channel;
            this.velocity = velocity;
        }

        int[] programs(LibvgmPlaybackEngine engine)
        {
            return engine.getChannelPrograms().clone();
        }
    }
}
