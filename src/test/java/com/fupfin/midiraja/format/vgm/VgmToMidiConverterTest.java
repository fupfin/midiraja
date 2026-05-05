/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;

class VgmToMidiConverterTest
{

    @Test
    void convert_emptyEvents_returnsValidSequence()
    {
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                List.of(), null);

        var sequence = new VgmToMidiConverter().convert(parsed);

        assertNotNull(sequence);
        assertEquals(Sequence.PPQ, sequence.getDivisionType());
        assertEquals(480, sequence.getResolution());
    }

    @Test
    void convert_singleSn76489Event_hasTempoTrack()
    {
        var event = new VgmEvent(0, 0, new byte[] { (byte) 0x90 });
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                List.of(event), null);

        var sequence = new VgmToMidiConverter().convert(parsed);
        var tempoTrack = sequence.getTracks()[0];

        boolean hasTempo = false;
        for (int i = 0; i < tempoTrack.size(); i++)
        {
            MidiEvent e = tempoTrack.get(i);
            if (e.getMessage() instanceof MetaMessage mm && mm.getType() == 0x51)
            {
                hasTempo = true;
                break;
            }
        }
        assertTrue(hasTempo, "Track 0 should contain a tempo MetaMessage (type 0x51)");
    }

    @Test
    void convert_percussionChannel_hasProgramChange()
    {
        // TSF requires an explicit patchChange(ch=9, preset=0, isDrums=1) to activate drum mode.
        // Without Program Change 0 on ch 9, TSF treats it as a melodic channel and plays piano
        // instead of drums. Verify that Program Change 0 is always emitted on MIDI channel 9.
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                List.of(), null);

        var sequence = new VgmToMidiConverter().convert(parsed);

        boolean foundProgramChange = false;
        for (var track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                var msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.PROGRAM_CHANGE
                        && sm.getChannel() == 9
                        && sm.getData1() == 0)
                {
                    foundProgramChange = true;
                }
            }
        }
        assertTrue(foundProgramChange, "Program Change 0 must be sent on MIDI channel 9 for TSF drum mode");
    }

    @Test
    void mutedChannel_doesNotAffectConversion()
    {
        // Conversion no longer applies mute filtering; events are preserved.
        // Mute is handled later in playback/export pipelines.
        var volEvent = new VgmEvent(0, 0, new byte[] { (byte) 0x90 });
        var toneLow = new VgmEvent(100, 0, new byte[] { (byte) 0x8A });
        var toneHigh = new VgmEvent(100, 0, new byte[] { (byte) 0x01 });
        var parsed = new VgmParseResult(0x151, 0L, 3_579_545L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                List.of(volEvent, toneLow, toneHigh), null);

        var baseline = new VgmToMidiConverter(Set.of()).convert(parsed);
        var muted = new VgmToMidiConverter(Set.of(0)).convert(parsed);

        assertEquals(baseline.getTracks().length, muted.getTracks().length);
        for (int ti = 0; ti < baseline.getTracks().length; ti++)
            assertEquals(baseline.getTracks()[ti].size(), muted.getTracks()[ti].size(),
                    "Mute options must not change VGM->MIDI conversion output");
    }

    @Test
    void convert_ym2608AdpcmKeyMask_emitsDrumEvents()
    {
        var keyOn = new VgmEvent(0, VgmToMidiConverter.CHIP_YM2608_PORT0, new byte[] { 0x10, 0x01 });
        var keyOff = new VgmEvent(100, VgmToMidiConverter.CHIP_YM2608_PORT0, new byte[] { 0x10, (byte) 0xBF });
        var parsed = new VgmParseResult(0x151, 0L, 0L, 0L, 0L, 0L, 7_987_200L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                List.of(keyOn, keyOff), null);

        var sequence = new VgmToMidiConverter().convert(parsed);

        boolean noteOn = false;
        boolean noteOff = false;
        for (var track : sequence.getTracks())
        {
            for (int i = 0; i < track.size(); i++)
            {
                var msg = track.get(i).getMessage();
                if (msg instanceof ShortMessage sm && sm.getChannel() == 9 && sm.getData1() == 36)
                {
                    noteOn |= sm.getCommand() == ShortMessage.NOTE_ON;
                    noteOff |= sm.getCommand() == ShortMessage.NOTE_OFF;
                }
            }
        }

        assertTrue(noteOn, "YM2608 ADPCM-A key-on should map to a GM drum NOTE_ON");
        assertTrue(noteOff, "YM2608 ADPCM-A key-off mask should map to a GM drum NOTE_OFF");
    }
}
