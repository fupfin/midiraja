/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import static com.fupfin.midiraja.format.vgm.FmMidiUtil.addEvent;

import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts OPN ADPCM-A key-mask register writes into GM drum note events.
 *
 * <p>
 * YM2608 uses register {@code 0x10} (port 0), YM2610/YM2610B use register {@code 0x00}
 * (port 1). Bits 0-5 select the voices. Bit 7 selects key-off when set and key-on otherwise.
 */
final class OpnAdpcmARhythmMidiConverter
{
    private static final int DRUM_CH = 9;
    private static final int[] ADPCM_CH_TO_GM_NOTE = { 36, 38, 49, 42, 45, 37 };
    private final int keyReg;
    private final DrumOutput output;
    private int activeMask;

    OpnAdpcmARhythmMidiConverter(int keyReg, DrumOutput output)
    {
        this.keyReg = keyReg;
        this.output = output;
    }

    /**
     * @return true when the event was consumed as ADPCM-A rhythm control
     */
    boolean convert(VgmEvent event, Track[] tracks, long tick)
    {
        int addr = event.rawData()[0] & 0xFF;
        if (addr != keyReg)
            return false;

        int data = event.rawData()[1] & 0xFF;
        boolean keyOff = (data & 0x80) != 0;
        int selected = data & 0x3F;
        for (int ch = 0; ch < ADPCM_CH_TO_GM_NOTE.length; ch++)
        {
            int bit = 1 << ch;
            if ((selected & bit) == 0)
                continue;

            if ((activeMask & bit) != 0)
                output.release(ch, tracks[DRUM_CH], tick);
            if (!keyOff)
                output.hit(ch, tracks[DRUM_CH], tick);
        }
        activeMask = keyOff ? activeMask & ~selected : activeMask | selected;
        return true;
    }

    /** Tracks ownership across OPN chips sharing the GM percussion channel. */
    static final class DrumOutput
    {
        private final int[] owners = new int[ADPCM_CH_TO_GM_NOTE.length];
        private final int[] pendingNoteOffs = new int[ADPCM_CH_TO_GM_NOTE.length];

        void hit(int voice, Track track, long tick)
        {
            owners[voice]++;
            pendingNoteOffs[voice]++;
            addEvent(track, ShortMessage.NOTE_ON, DRUM_CH, ADPCM_CH_TO_GM_NOTE[voice], 100, tick);
        }

        void release(int voice, Track track, long tick)
        {
            // MIDI cannot address one of two instances of the same channel/note pair.
            // Defer releases while another chip still owns that drum.
            if (--owners[voice] != 0)
                return;

            for (int i = 0; i < pendingNoteOffs[voice]; i++)
                addEvent(track, ShortMessage.NOTE_OFF, DRUM_CH, ADPCM_CH_TO_GM_NOTE[voice], 0, tick);
            pendingNoteOffs[voice] = 0;
        }
    }
}
