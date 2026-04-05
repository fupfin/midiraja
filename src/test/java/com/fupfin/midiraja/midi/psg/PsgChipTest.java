package com.fupfin.midiraja.midi.psg;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PsgChipTest
{

    private PsgChip chip;
    private final int sampleRate = 44100;

    @BeforeEach
    void setUp()
    {
        chip = new PsgChip(sampleRate, 5.0, 25.0);
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
    void testNoiseChannel()
    {
        // Channel 9 is drums, should use noise
        chip.tryAllocateFree(9, 36, 100);
        double output = chip.render();
        assertNotEquals(0.0, output);
    }

    @Test
    void testVoiceStealing()
    {
        // Fill all 3 channels
        for (int i = 0; i < 3; i++)
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
