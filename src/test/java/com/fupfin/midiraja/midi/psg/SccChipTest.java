package com.fupfin.midiraja.midi.psg;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SccChipTest
{

    private SccChip chip;
    private final int sampleRate = 44100;

    @BeforeEach
    void setUp()
    {
        chip = new SccChip(sampleRate, 5.0, false); // smoothScc = false
    }

    @Test
    void testRenderSilence()
    {
        double output = chip.render();
        assertEquals(0.0, output);
    }

    @Test
    void testNoteOnAndRender()
    {
        chip.tryAllocateFree(0, 60, 100);
        double output = chip.render();
        assertNotEquals(0.0, output);
    }

    @Test
    void testSetProgram()
    {
        chip.tryAllocateFree(0, 60, 100);
        // Program 0 is Piano
        chip.setProgram(0, 0);
        double output1 = chip.render();

        // Program 80 is Lead
        chip.setProgram(0, 80);
        double output2 = chip.render();

        // Waveforms should be different
        assertNotEquals(output1, output2);
    }

    @Test
    void testSmoothScc()
    {
        SccChip smoothChip = new SccChip(sampleRate, 0, true);
        smoothChip.tryAllocateFree(0, 60, 100);
        double output = smoothChip.render();
        assertNotEquals(0.0, output);
    }

    @Test
    void testVoiceStealing()
    {
        // Fill all 5 channels
        for (int i = 0; i < 5; i++)
        {
            chip.tryAllocateFree(i, 60 + i, 100);
        }

        // Steal a channel
        boolean stolen = chip.tryStealChannel(0, 72, 127);
        assertTrue(stolen);
    }

    @Test
    void testReset()
    {
        chip.tryAllocateFree(0, 60, 100);
        chip.reset();
        assertEquals(0.0, chip.render());
    }
}
