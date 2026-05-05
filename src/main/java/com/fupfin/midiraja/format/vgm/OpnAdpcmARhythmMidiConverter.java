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
 * (port 1). Bit 7 set means key-off all ({@code 0xBF} in typical streams); bits 0-5 are
 * per-channel key-on masks.
 */
final class OpnAdpcmARhythmMidiConverter
{
    private static final int DRUM_CH = 9;
    private static final int[] ADPCM_CH_TO_GM_NOTE = { 36, 38, 49, 42, 45, 37 };
    private final int keyReg;
    private int lastKeyMask;

    OpnAdpcmARhythmMidiConverter(int keyReg)
    {
        this.keyReg = keyReg;
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
        int newMask = ((data & 0x80) != 0) ? 0 : (data & 0x3F);

        for (int ch = 0; ch < ADPCM_CH_TO_GM_NOTE.length; ch++)
        {
            int bit = 1 << ch;
            boolean wasOn = (lastKeyMask & bit) != 0;
            boolean isOn = (newMask & bit) != 0;
            int note = ADPCM_CH_TO_GM_NOTE[ch];
            if (!wasOn && isOn)
                addEvent(tracks[DRUM_CH], ShortMessage.NOTE_ON, DRUM_CH, note, 100, tick);
            else if (wasOn && !isOn)
                addEvent(tracks[DRUM_CH], ShortMessage.NOTE_OFF, DRUM_CH, note, 0, tick);
        }
        lastKeyMask = newMask;
        return true;
    }
}

