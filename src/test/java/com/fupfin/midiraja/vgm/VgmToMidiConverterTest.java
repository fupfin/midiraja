/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;

class VgmToMidiConverterTest {

    @Test
    void convert_emptyEvents_returnsValidSequence() {
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of(), null);

        var sequence = new VgmToMidiConverter().convert(parsed);

        assertNotNull(sequence);
        assertEquals(Sequence.PPQ, sequence.getDivisionType());
        assertEquals(480, sequence.getResolution());
    }

    @Test
    void convert_singleSn76489Event_hasTempoTrack() {
        var event = new VgmEvent(0, 0, new byte[]{(byte) 0x90});
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of(event), null);

        var sequence = new VgmToMidiConverter().convert(parsed);
        var tempoTrack = sequence.getTracks()[0];

        boolean hasTempo = false;
        for (int i = 0; i < tempoTrack.size(); i++) {
            MidiEvent e = tempoTrack.get(i);
            if (e.getMessage() instanceof MetaMessage mm && mm.getType() == 0x51) {
                hasTempo = true;
                break;
            }
        }
        assertTrue(hasTempo, "Track 0 should contain a tempo MetaMessage (type 0x51)");
    }

    @Test
    void convert_percussionChannel_hasProgramChange() {
        // TSF requires an explicit patchChange(ch=9, preset=0, isDrums=1) to activate drum mode.
        // Without Program Change 0 on ch 9, TSF treats it as a melodic channel and plays piano
        // instead of drums. Verify that Program Change 0 is always emitted on MIDI channel 9.
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of(), null);

        var sequence = new VgmToMidiConverter().convert(parsed);

        boolean foundProgramChange = false;
        for (var track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                var msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.PROGRAM_CHANGE
                        && sm.getChannel() == 9
                        && sm.getData1() == 0) {
                    foundProgramChange = true;
                }
            }
        }
        assertTrue(foundProgramChange, "Program Change 0 must be sent on MIDI channel 9 for TSF drum mode");
    }

    @Test
    void mutedChannel_producesNoEvents() {
        // SN76489 tone ch0 → MIDI ch0. A volume latch (vol=0, max volume) followed by a tone
        // should produce NoteOn on ch0. Muting ch0 should discard it, leaving only ProgramChange.
        // Latch byte: bit7=1, ch=0 (bits 6-5=00), vol (bit4=1), vol=0 → 0x90
        var volEvent = new VgmEvent(0, 0, new byte[]{(byte) 0x90});
        // Latch byte: bit7=1, ch=0, tone (bit4=0), low4=0x0A → 0x8A
        var toneLow = new VgmEvent(100, 0, new byte[]{(byte) 0x8A});
        // Data byte: bit7=0, high6=0x01 → tone period = (1<<4)|0x0A = 0x1A = 26
        var toneHigh = new VgmEvent(100, 0, new byte[]{(byte) 0x01});
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                List.of(volEvent, toneLow, toneHigh), null);

        var sequence = new VgmToMidiConverter(Set.of(0)).convert(parsed);

        // Track index 1 corresponds to MIDI ch 0 (track 0 is tempo track)
        var track = sequence.getTracks()[1];
        boolean hasNoteOn = false;
        for (int i = 0; i < track.size(); i++) {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON) {
                hasNoteOn = true;
            }
        }
        assertFalse(hasNoteOn, "Muted channel should have no NoteOn events");

        // Verify the ProgramChange is still present (it's set at init time on the real track)
        boolean hasProgramChange = false;
        for (int i = 0; i < track.size(); i++) {
            var msg = track.get(i).getMessage();
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                hasProgramChange = true;
            }
        }
        assertTrue(hasProgramChange, "Muted channel should still have the initial Program Change");
    }
}
