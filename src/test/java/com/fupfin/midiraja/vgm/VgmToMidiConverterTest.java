/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import org.junit.jupiter.api.Test;

class VgmToMidiConverterTest {

    @Test
    void convert_emptyEvents_returnsValidSequence() {
        var parsed = new VgmParseResult(0x151, 3_579_545L, 0L, List.of(), null);

        var sequence = new VgmToMidiConverter().convert(parsed);

        assertNotNull(sequence);
        assertEquals(Sequence.PPQ, sequence.getDivisionType());
        assertEquals(4410, sequence.getResolution());
    }

    @Test
    void convert_singleSn76489Event_hasTempoTrack() {
        var event = new VgmEvent(0, 0, new byte[]{(byte) 0x90});
        var parsed = new VgmParseResult(0x151, 3_579_545L, 0L, List.of(event), null);

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
}
