/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * OKI MSM6258 4-bit ADPCM encoder.
 *
 * <p>
 * Algorithm matches {@code okim6258.c} from libvgm / MAME:
 * <ul>
 *   <li>Step table: {@code stepval[i] = floor(16 * (11/10)^i)}, i = 0..48
 *   <li>Diff from nibble bits: {@code sv/8 + (b0?sv/4:0) + (b1?sv/2:0) + (b2?sv:0)}, sign = b3
 *   <li>Signal update: {@code signal = ((diff << 8) + (signal * 245)) >> 8}, clamped to [-2048, 2047]
 *   <li>Step update: {@code step += {-1,-1,-1,-1,2,4,6,8}[nibble & 7]}, clamped to [0, 48]
 *   <li>Initial encoder state: signal = -2, step = 0
 *   <li>Output format: packed — two nibbles per byte, low nibble first, then high nibble
 * </ul>
 */
final class OkiAdpcmCodec
{
    /** OKIM6258 sample rate for X68000: 8 MHz clock / 512 divider. */
    static final int SAMPLE_RATE = 15_625;

    private static final int[] STEP_VAL = buildStepTable();
    private static final int[] DIFF_LOOKUP = buildDiffLookup();
    private static final int[] INDEX_SHIFT = { -1, -1, -1, -1, 2, 4, 6, 8 };

    private OkiAdpcmCodec()
    {
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Encodes 12-bit signed PCM samples (range [-2048, 2047]) to OKI 4-bit ADPCM bytes.
     *
     * <p>
     * Output format is <em>packed</em>: two nibbles per byte, low nibble first, then high nibble.
     * This matches the stream format expected by the MSM6258 chip emulator in libvgm/MAME, which
     * alternates between bits 3..0 and bits 7..4 of each byte using an internal {@code nibble_shift}
     * variable. Input values outside [-2048, 2047] are clamped.
     *
     * @param pcm
     *            12-bit signed PCM samples (use range [-2048, 2047])
     * @return ADPCM-encoded bytes; length equals {@code (pcm.length + 1) / 2}
     */
    static byte[] encode(short[] pcm)
    {
        if (pcm.length == 0)
            return new byte[0];
        byte[] out = new byte[(pcm.length + 1) / 2];
        int signal = -2;
        int step = 0;
        int outIdx = 0;
        boolean lowNibble = true;

        for (int i = 0; i < pcm.length; i++)
        {
            int target = Math.clamp((int) pcm[i], -2048, 2047);
            int nibble = bestNibble(signal, step, target);
            if (lowNibble)
                out[outIdx] = (byte) (nibble & 0x0F);
            else
            {
                out[outIdx] |= (byte) ((nibble & 0x0F) << 4);
                outIdx++;
            }
            lowNibble = !lowNibble;

            int diff = DIFF_LOOKUP[step * 16 + nibble];
            signal = Math.clamp(((diff << 8) + (signal * 245)) >> 8, -2048, 2047);
            step = Math.clamp(step + INDEX_SHIFT[nibble & 7], 0, 48);
        }
        return out;
    }

    // ── Private: nibble selection ─────────────────────────────────────────────

    private static int bestNibble(int signal, int step, int target)
    {
        int best = 0;
        int bestErr = Integer.MAX_VALUE;
        for (int n = 0; n < 16; n++)
        {
            int diff = DIFF_LOOKUP[step * 16 + n];
            int next = Math.clamp(((diff << 8) + (signal * 245)) >> 8, -2048, 2047);
            int err = Math.abs(next - target);
            if (err < bestErr)
            {
                bestErr = err;
                best = n;
            }
        }
        return best;
    }

    // ── Private: lookup table builders ───────────────────────────────────────

    /** Builds the 49-entry step-size table: {@code floor(16 * 1.1^i)}. */
    private static int[] buildStepTable()
    {
        int[] table = new int[49];
        for (int i = 0; i < 49; i++)
            table[i] = (int) (16.0 * Math.pow(1.1, i));
        return table;
    }

    /** Builds the 49×16 diff-lookup table from step-size and nibble bits. */
    private static int[] buildDiffLookup()
    {
        int[] table = new int[49 * 16];
        for (int step = 0; step < 49; step++)
        {
            int sv = STEP_VAL[step];
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
