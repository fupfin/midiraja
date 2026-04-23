/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sound.midi.*;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.cli.MidiMetaExtractor.MidiMeta;

class MidiMetaExtractorTest
{

    private final MidiMetaExtractor extractor = new MidiMetaExtractor();

    /** Helper: builds a MetaMessage of given type and UTF-8 text. */
    private static MetaMessage meta(int type, String text) throws InvalidMidiDataException
    {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        var m = new MetaMessage();
        m.setMessage(type, data, data.length);
        return m;
    }

    /** Returns a minimal single-track sequence with a pad event at endTick (PPQ=480, 120 BPM). */
    private static Sequence seq(long endTick) throws InvalidMidiDataException
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        var pad = new MetaMessage();
        pad.setMessage(0x01, new byte[] {}, 0);
        t.add(new MidiEvent(pad, endTick));
        return seq;
    }

    @Test
    void durationMatchesGetMicrosecondLength() throws Exception
    {
        Sequence s = seq(960);
        MidiMeta meta = extractor.extractFromSequence(s);
        assertEquals(s.getMicrosecondLength(), meta.durationMicroseconds());
        assertTrue(meta.durationMicroseconds() > 0, "Duration should be positive");
    }

    @Test
    void durationIgnoresTrailingEndOfTrackGap() throws Exception
    {
        Sequence s = new Sequence(Sequence.PPQ, 480);
        Track t = s.createTrack();
        var text = new MetaMessage();
        text.setMessage(0x01, new byte[] {}, 0);
        t.add(new MidiEvent(text, 960L)); // 1.0s at 120 BPM, PPQ=480

        var eot = new MetaMessage();
        eot.setMessage(0x2F, new byte[] {}, 0);
        t.add(new MidiEvent(eot, 268_435_455L)); // malformed trailing EOT gap

        MidiMeta meta = extractor.extractFromSequence(s);
        assertEquals(1_000_000L, meta.durationMicroseconds());
    }

    @Test
    void copyrightExtracted() throws Exception
    {
        Sequence s = seq(480);
        s.getTracks()[0].add(new MidiEvent(meta(0x02, "© 1991 LucasArts"), 0L));
        MidiMeta m = extractor.extractFromSequence(s);
        assertEquals("© 1991 LucasArts", m.copyright());
    }

    @Test
    void titleUsesAllTracksNotJustTrackZero() throws Exception
    {
        Sequence s = new Sequence(Sequence.PPQ, 480);
        s.createTrack(); // track 0 — no title
        Track t1 = s.createTrack();
        t1.add(new MidiEvent(meta(0x03, "My Song"), 0L));
        MidiMeta m = extractor.extractFromSequence(s);
        assertEquals("My Song", m.title());
    }

    @Test
    void titleEmptyStringWhenAbsent() throws Exception
    {
        MidiMeta m = extractor.extractFromSequence(seq(480));
        assertEquals("", m.title());
    }

    @Test
    void lyricsJoinedWithNewline() throws Exception
    {
        Sequence s = seq(960);
        Track t = s.getTracks()[0];
        t.add(new MidiEvent(meta(0x05, "Hel-"), 0L));
        t.add(new MidiEvent(meta(0x05, "lo"), 10L));
        t.add(new MidiEvent(meta(0x05, "World"), 20L));
        MidiMeta m = extractor.extractFromSequence(s);
        assertEquals("Hel-\nlo\nWorld", m.lyrics());
    }

    @Test
    void lyricsTickOrderWithTrackIndexTiebreak() throws Exception
    {
        Sequence s = new Sequence(Sequence.PPQ, 480);
        Track t0 = s.createTrack();
        Track t1 = s.createTrack();
        t0.add(new MidiEvent(meta(0x05, "A"), 100L));
        t1.add(new MidiEvent(meta(0x05, "B"), 100L));
        MidiMeta m = extractor.extractFromSequence(s);
        assertEquals("A\nB", m.lyrics());
    }

    @Test
    void lyricsEmptyWhenAbsent() throws Exception
    {
        MidiMeta m = extractor.extractFromSequence(seq(480));
        assertEquals("", m.lyrics());
    }

    @Test
    void instrumentNamesDedupedCaseInsensitive() throws Exception
    {
        Sequence s = seq(480);
        Track t = s.getTracks()[0];
        t.add(new MidiEvent(meta(0x07, "Piano"), 0L));
        t.add(new MidiEvent(meta(0x07, "piano"), 10L));
        t.add(new MidiEvent(meta(0x07, "Bass"), 20L));
        MidiMeta m = extractor.extractFromSequence(s);
        assertEquals(List.of("Piano", "Bass"), m.instrumentNames());
    }

    @Test
    void instrumentNamesEmptyWhenAbsent() throws Exception
    {
        MidiMeta m = extractor.extractFromSequence(seq(480));
        assertTrue(m.instrumentNames().isEmpty());
    }
}
