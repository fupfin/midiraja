/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.tracker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;

/**
 * Focused tests for TrackerToMidiConverter logic not covered by S3m/Xm/ItToMidiConverterTest:
 * volume slide effects (D=4 and A=10), fine-slide nibble filtering, and channel clamping.
 */
class TrackerToMidiConverterTest
{
    private static List<TrackerInstrument> instruments(String... names)
    {
        var list = new java.util.ArrayList<TrackerInstrument>();
        for (String n : names)
            list.add(new TrackerInstrument(n, 64));
        return List.copyOf(list);
    }

    private static boolean hasCC7(javax.sound.midi.Sequence seq)
    {
        for (var track : seq.getTracks())
            for (int i = 0; i < track.size(); i++)
                if (track.get(i).getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.CONTROL_CHANGE
                        && sm.getData1() == 7)
                    return true;
        return false;
    }

    // ── Volume slide effect D (code 4, used by S3M and IT) ────────────────────

    @Test
    void volSlideD_slideUp_emitsCC7()
    {
        // Effect 4, param 0x30 → up=3, down=0
        var events = List.of(
                new TrackerEvent(0, 0, 60, 1, -1, 0, 0), // note on
                new TrackerEvent(480_000, 0, -1, 0, -1, 4, 0x30) // D30 = slide up 3
        );
        var result = new TrackerParseResult("", 4, instruments("lead"), events);
        assertTrue(hasCC7(new TrackerToMidiConverter().convert(result)),
                "D-effect slide up should emit CC7");
    }

    @Test
    void volSlideD_slideDown_emitsCC7()
    {
        // Effect 4, param 0x03 → up=0, down=3
        var events = List.of(
                new TrackerEvent(0, 0, 60, 1, -1, 0, 0),
                new TrackerEvent(480_000, 0, -1, 0, -1, 4, 0x03));
        var result = new TrackerParseResult("", 4, instruments("lead"), events);
        assertTrue(hasCC7(new TrackerToMidiConverter().convert(result)),
                "D-effect slide down should emit CC7");
    }

    @Test
    void volSlideD_fineSlideUp_nibble0xF_skipsSlide()
    {
        // Effect 4, param 0xF3 → up=0xF (fine marker), down=3
        // fine-up means "use down nibble=3 as fine-down" — but our converter skips effectiveUp when ==0xF
        // effectiveUp=0, effectiveDown=3 → should still emit CC7
        var events = List.of(
                new TrackerEvent(0, 0, 60, 1, -1, 0, 0),
                new TrackerEvent(480_000, 0, -1, 0, -1, 4, 0xF3));
        var result = new TrackerParseResult("", 4, instruments("lead"), events);
        assertTrue(hasCC7(new TrackerToMidiConverter().convert(result)),
                "Fine-slide with down nibble should still emit CC7");
    }

    @Test
    void volSlideD_bothNibblesZero_noCC7FromSlide()
    {
        // Effect 4, param 0x00 → both zero; no slide emitted
        // (no volume column either, so no CC7 at all)
        var events = List.of(new TrackerEvent(0, 0, -1, 0, -1, 4, 0x00));
        var result = new TrackerParseResult("", 4, List.of(), events);
        assertFalse(hasCC7(new TrackerToMidiConverter().convert(result)),
                "Zero param volume slide should not emit CC7");
    }

    // ── Volume slide effect A (code 10, used by XM) ───────────────────────────

    @Test
    void volSlideA_slideUp_emitsCC7()
    {
        // Effect 10 (0x0A), param 0x50 → up=5, down=0
        var events = List.of(
                new TrackerEvent(0, 0, 60, 1, -1, 0, 0),
                new TrackerEvent(480_000, 0, -1, 0, -1, 10, 0x50));
        var result = new TrackerParseResult("", 4, instruments("lead"), events);
        assertTrue(hasCC7(new TrackerToMidiConverter().convert(result)),
                "A-effect (XM) slide up should emit CC7");
    }

    @Test
    void volSlideA_slideDown_emitsCC7()
    {
        // Effect 10, param 0x05 → up=0, down=5
        var events = List.of(
                new TrackerEvent(0, 0, 60, 1, -1, 0, 0),
                new TrackerEvent(480_000, 0, -1, 0, -1, 10, 0x05));
        var result = new TrackerParseResult("", 4, instruments("lead"), events);
        assertTrue(hasCC7(new TrackerToMidiConverter().convert(result)),
                "A-effect (XM) slide down should emit CC7");
    }

    // ── Channel clamping ──────────────────────────────────────────────────────

    @Test
    void event_beyondMaxChannel_isIgnored()
    {
        // channelCount=2, event on channel 5 → should produce no NoteOn
        var events = List.of(new TrackerEvent(0, 5, 60, 1, -1, 0, 0));
        var result = new TrackerParseResult("", 2, instruments("lead"), events);
        var seq = new TrackerToMidiConverter().convert(result);

        boolean found = false;
        for (var track : seq.getTracks())
            for (int i = 0; i < track.size(); i++)
                if (track.get(i).getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.NOTE_ON)
                    found = true;
        assertFalse(found, "Event on channel beyond channelCount must be ignored");
    }
}
