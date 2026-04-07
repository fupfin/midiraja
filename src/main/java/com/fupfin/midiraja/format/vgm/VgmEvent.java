/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

/**
 * A single chip register-write event extracted from a VGM stream.
 *
 * @param sampleOffset
 *            absolute sample position (44100 Hz timebase)
 * @param chip
 *            0=SN76489, 1=YM2612 port0, 2=YM2612 port1
 * @param rawData
 *            register data bytes (1 byte for SN76489, 2 bytes for YM2612)
 */
public record VgmEvent(long sampleOffset, int chip, byte[] rawData)
{
    public VgmEvent
    {
        rawData = rawData.clone();
    }

    @Override
    public byte[] rawData()
    {
        return rawData.clone();
    }
}
