/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.gus;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class GusPatchReaderTest
{
    @Test
    void testInvalidMagicHeader()
    {
        byte[] badData = "BADMAGIC_HEADER_DATA".getBytes(StandardCharsets.UTF_8);
        try (var in = new ByteArrayInputStream(badData))
        {
            assertThrows(IOException.class, () -> GusPatchReader.read(in),
                    "Should throw exception for invalid magic header");
        }
        catch (IOException e)
        {
            fail("Exception thrown closing stream");
        }
    }

    @Test
    void testValidFullParsing() throws IOException
    {
        ByteBuffer buf = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);

        // 1. Global Header (239 bytes)
        buf.put("GF1PATCH110\0ID#000002\0".getBytes(StandardCharsets.US_ASCII)); // 22 bytes
        buf.position(22);
        buf.put("Test Instrument Description".getBytes(StandardCharsets.US_ASCII));

        // Samples count is at offset 198
        buf.position(198);
        buf.put((byte) 1); // 1 sample
        buf.position(239);

        // 2. Sample Header (96 bytes per sample)
        buf.put("SMPL1".getBytes(StandardCharsets.US_ASCII)); // Name (7 bytes)
        buf.position(buf.position() + 2); // padding
        buf.put((byte) 0); // fractions
        buf.putInt(10); // Length
        buf.putInt(2); // Loop start
        buf.putInt(8); // Loop end
        buf.putShort((short) 44100); // Rate
        buf.putInt(100); // Low freq
        buf.putInt(20000); // High freq
        buf.putInt(440); // Root freq
        buf.putShort((short) 0); // Tune
        buf.put((byte) 64); // Pan (center)
        buf.position(buf.position() + 18); // Envelopes (12) + LFOs (6) = 18 bytes
        buf.put((byte) 0); // modes at offset 55
        buf.position(239 + 96);

        // 3. PCM Data (10 bytes)
        buf.put(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });

        try (var in = new ByteArrayInputStream(buf.array(), 0, buf.position()))
        {
            GusPatch patch = GusPatchReader.read(in);
            assertNotNull(patch);
            assertEquals("Test Instrument Description", patch.description());
            assertEquals(1, patch.instruments().size());

            var inst = patch.instruments().get(0);
            assertEquals("Test Instrument Description", inst.name());
            assertEquals(1, inst.samples().size());

            var sample = inst.samples().get(0);
            assertEquals(10, sample.length());
            assertEquals(44100, sample.sampleRate());
            assertEquals(440, sample.rootFrequency());
            assertEquals(10, sample.pcmData().byteSize());
            assertFalse(sample.is16Bit());
        }
    }
}
