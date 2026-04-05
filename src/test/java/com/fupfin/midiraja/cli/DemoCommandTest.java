/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DemoCommandTest
{

    @Test
    void tsf_tag_returns_tsf_display_name()
    {
        assertEquals("TinySoundFont (General MIDI SF3)", DemoCommand.extractEngineName("01-tsf-entertainer.mid"));
    }

    @Test
    void gus_tag_returns_gus_display_name()
    {
        assertEquals("GUS Patches (FreePats)", DemoCommand.extractEngineName("02-gus-canon.mid"));
    }

    @Test
    void opl3_tag_returns_opl3_display_name()
    {
        assertEquals("OPL3 FM Synthesis (libADLMIDI)", DemoCommand.extractEngineName("03-opl3-doom.mid"));
    }

    @Test
    void opn2_tag_returns_opn2_display_name()
    {
        assertEquals("OPN2 FM Synthesis (libOPNMIDI)", DemoCommand.extractEngineName("04-opn2-sonic.mid"));
    }

    @Test
    void psg_tag_returns_psg_display_name()
    {
        assertEquals("PSG Chip Synthesis (AY-3-8910)", DemoCommand.extractEngineName("05-psg-tetris.mid"));
    }

    @Test
    void beep_tag_returns_beep_display_name()
    {
        assertEquals("PC Beeper", DemoCommand.extractEngineName("06-beep-mario.mid"));
    }

    @Test
    void munt_tag_returns_munt_display_name()
    {
        assertEquals("MT-32 Emulation (Munt)", DemoCommand.extractEngineName("07-munt-monkey.mid"));
    }

    @Test
    void fluid_tag_returns_fluid_display_name()
    {
        assertEquals("FluidSynth", DemoCommand.extractEngineName("08-fluid-bach.mid"));
    }

    @Test
    void unknown_tag_falls_back_to_tsf_display_name()
    {
        assertEquals("TinySoundFont (General MIDI SF3)", DemoCommand.extractEngineName("09-xyz-unknown.mid"));
    }

    @Test
    void no_tag_falls_back_to_tsf_display_name()
    {
        assertEquals("TinySoundFont (General MIDI SF3)", DemoCommand.extractEngineName("song.mid"));
    }

    @Test
    void tag_matching_is_case_insensitive()
    {
        assertEquals("TinySoundFont (General MIDI SF3)", DemoCommand.extractEngineName("01-TSF-entertainer.mid"));
        assertEquals("OPL3 FM Synthesis (libADLMIDI)", DemoCommand.extractEngineName("03-OPL3-doom.mid"));
        assertEquals("MT-32 Emulation (Munt)", DemoCommand.extractEngineName("07-MUNT-monkey.mid"));
    }

    @Test
    void extractSongTitle_stripsLeadingNumber()
    {
        assertEquals("Moonlight Sonata", DemoCommand.extractSongTitle("01-tsf-moonlight-sonata.mid"));
    }

    @Test
    void extractSongTitle_stripsEngineTag()
    {
        assertEquals("Doom E1m1", DemoCommand.extractSongTitle("03-opl3-doom-e1m1.mid"));
    }

    @Test
    void extractSongTitle_multiWordTitle()
    {
        assertEquals("Flight Of The Bumblebee", DemoCommand.extractSongTitle("05-beep-flight-of-the-bumblebee.mid"));
    }
}
