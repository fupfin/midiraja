/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OkiAdpcmCodecTest
{
    @Test
    void encode_emptyInput_returnsEmpty()
    {
        byte[] result = OkiAdpcmCodec.encode(new short[0]);
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    void encode_singleSample_oneOutputByte()
    {
        // 1 sample → 1 byte (low nibble used, high nibble zero-padded)
        short[] pcm = { 0 };
        byte[] result = OkiAdpcmCodec.encode(pcm);
        assertEquals(1, result.length);
    }

    @Test
    void encode_twoSamples_oneOutputByte()
    {
        // 2 samples packed into 1 byte (low nibble = sample 0, high nibble = sample 1)
        short[] pcm = { 0, 0 };
        byte[] result = OkiAdpcmCodec.encode(pcm);
        assertEquals(1, result.length);
    }

    @Test
    void encode_threeSamples_twoOutputBytes()
    {
        // 3 samples: first byte holds samples 0 and 1; second byte holds sample 2 (high nibble zero-padded)
        short[] pcm = { 0, 0, 0 };
        byte[] result = OkiAdpcmCodec.encode(pcm);
        assertEquals(2, result.length);
    }

    @Test
    void encode_evenSamples_halfOutputBytes()
    {
        // n even samples → n/2 bytes
        short[] pcm = new short[100];
        byte[] result = OkiAdpcmCodec.encode(pcm);
        assertEquals(50, result.length);
    }

    @Test
    void encode_packedLowNibbleFirst()
    {
        // Two non-silent samples: low nibble of output byte = first sample's nibble,
        // high nibble = second sample's nibble. Both nibbles must be independently non-zero
        // for a signal that drives step index upward.
        int n = 100;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++)
            pcm[i] = (short) (1000 * Math.sin(2 * Math.PI * 200.0 * i / OkiAdpcmCodec.SAMPLE_RATE));
        byte[] result = OkiAdpcmCodec.encode(pcm);
        // At least some bytes should have a non-zero high nibble (both nibbles used)
        boolean anyHighNibble = false;
        for (byte b : result)
            if ((b & 0xF0) != 0) { anyHighNibble = true; break; }
        assertTrue(anyHighNibble, "Packed format must use the high nibble of output bytes");
    }

    @Test
    void encode_roundTrip_decodesWithinTolerance()
    {
        // Generate a simple sine wave at 100 Hz, 100 ms at 15625 Hz
        int n = (int) (0.10 * OkiAdpcmCodec.SAMPLE_RATE); // 1563 samples
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++)
            pcm[i] = (short) (1000 * Math.sin(2 * Math.PI * 100.0 * i / OkiAdpcmCodec.SAMPLE_RATE));

        byte[] adpcm = OkiAdpcmCodec.encode(pcm);
        // Packed format: ceil(n / 2) bytes
        assertEquals((n + 1) / 2, adpcm.length);

        // Decode and measure error on steady-state portion (skip first ~10 ms transient)
        short[] decoded = decode(adpcm, n);
        int skip = OkiAdpcmCodec.SAMPLE_RATE / 100; // 1 period = 156 samples
        double sumErr = 0;
        for (int i = skip; i < n; i++)
            sumErr += Math.abs(decoded[i] - pcm[i]);
        double avgErr = sumErr / (n - skip);
        // ADPCM is lossy; allow up to 10% of full scale (204 out of 2047) average error
        assertTrue(avgErr < 204, "Average ADPCM round-trip error too large: " + avgErr);
    }

    @Test
    void generateDrumSamples_returns7Arrays()
    {
        byte[][] samples = OkiAdpcmCodec.generateDrumSamples();
        assertEquals(OkiAdpcmCodec.DRUM_COUNT, samples.length);
    }

    @Test
    void generateDrumSamples_allNonEmpty()
    {
        byte[][] samples = OkiAdpcmCodec.generateDrumSamples();
        for (int i = 0; i < samples.length; i++)
            assertTrue(samples[i].length > 0, "Drum sample " + i + " must not be empty");
    }

    // ── Minimal ADPCM decoder (mirrors OkiAdpcmCodec algorithm) ──────────────
    // Input: packed format — two nibbles per byte, low nibble first, then high nibble

    private static short[] decode(byte[] adpcm, int sampleCount)
    {
        int[] stepVal = buildStepTable();
        int[] diffLookup = buildDiffLookup(stepVal);
        int[] indexShift = { -1, -1, -1, -1, 2, 4, 6, 8 };

        short[] out = new short[sampleCount];
        int signal = -2;
        int step = 0;
        int sampleIdx = 0;
        for (int i = 0; i < adpcm.length && sampleIdx < sampleCount; i++)
        {
            // low nibble first, then high nibble
            for (int shift = 0; shift <= 4 && sampleIdx < sampleCount; shift += 4)
            {
                int nibble = (adpcm[i] >> shift) & 0x0F;
                int diff = diffLookup[step * 16 + nibble];
                signal = Math.clamp(((diff << 8) + (signal * 245)) >> 8, -2048, 2047);
                step = Math.clamp(step + indexShift[nibble & 7], 0, 48);
                out[sampleIdx++] = (short) signal;
            }
        }
        return out;
    }

    private static int[] buildStepTable()
    {
        int[] table = new int[49];
        for (int i = 0; i < 49; i++)
            table[i] = (int) (16.0 * Math.pow(1.1, i));
        return table;
    }

    private static int[] buildDiffLookup(int[] stepVal)
    {
        int[] table = new int[49 * 16];
        for (int step = 0; step < 49; step++)
        {
            int sv = stepVal[step];
            for (int nibble = 0; nibble < 16; nibble++)
            {
                int diff = sv / 8;
                if ((nibble & 1) != 0) diff += sv / 4;
                if ((nibble & 2) != 0) diff += sv / 2;
                if ((nibble & 4) != 0) diff += sv;
                if ((nibble & 8) != 0) diff = -diff;
                table[step * 16 + nibble] = diff;
            }
        }
        return table;
    }
}
