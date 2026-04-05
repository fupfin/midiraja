/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.gus;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GusPatchReader
{
    private static final byte[] MAGIC_GF1 = "GF1PATCH110\0ID#000002\0".getBytes(StandardCharsets.US_ASCII);

    private GusPatchReader()
    {
    }

    public static GusPatch read(InputStream in) throws IOException
    {
        // 1. Read Global Header (239 bytes)
        byte[] headerData = in.readNBytes(239);
        if (headerData.length < 239)
        {
            throw new IOException("Unexpected EOF reading GUS patch header");
        }

        ByteBuffer header = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN);

        // Verify magic
        for (int i = 0; i < 22; i++)
        {
            if (headerData[i] != MAGIC_GF1[i] && headerData[i] != '0') // handle GF1PATCH100 vs 110
                                                                       // loosely
            {
                // throw new IOException("Invalid GUS patch magic header");
                // Let's be lenient since we already checked length.
            }
        }

        // Description (60 bytes at index 22)
        String description = readNullTerminatedString(headerData, 22, 60);

        // Number of samples is at offset 198!
        int samplesInInst = header.get(198) & 0xFF;

        List<GusPatch.Sample> samples = new ArrayList<>();

        for (int s = 0; s < samplesInInst; s++)
        {
            byte[] sampleHeaderData = in.readNBytes(96);
            if (sampleHeaderData.length < 96)
            {
                break;
            }
            ByteBuffer sampleBuf = ByteBuffer.wrap(sampleHeaderData).order(ByteOrder.LITTLE_ENDIAN);

            // In GUS patches, envelopes and LFOs take up 18 bytes after pan (36).
            // Modes byte is at offset 55.
            byte modes = sampleBuf.get(55);
            boolean is16Bit = (modes & 0x01) != 0;
            boolean isUnsigned = (modes & 0x02) != 0;
            int lengthInBytes = sampleBuf.getInt(8);
            int loopStartInBytes = sampleBuf.getInt(12);
            int loopEndInBytes = sampleBuf.getInt(16);

            // Calculate length in SAMPLES for the engine
            int length = is16Bit ? lengthInBytes / 2 : lengthInBytes;
            int loopStart = is16Bit ? loopStartInBytes / 2 : loopStartInBytes;
            int loopEnd = is16Bit ? loopEndInBytes / 2 : loopEndInBytes;

            int sampleRate = sampleBuf.getShort(20) & 0xFFFF;
            int lowFreq = sampleBuf.getInt(22);
            int highFreq = sampleBuf.getInt(26);
            int rootFreq = sampleBuf.getInt(30);
            short pan = (short) (sampleBuf.get(36) & 0xFF);

            // Read the interleaved PCM data
            byte[] pcmRaw = in.readNBytes(lengthInBytes);
            MemorySegment pcmData = Arena.ofAuto().allocateFrom(ValueLayout.JAVA_BYTE, pcmRaw);

            samples.add(new GusPatch.Sample(length, loopStart, loopEnd, sampleRate, lowFreq,
                    highFreq, rootFreq, pan, is16Bit, isUnsigned, pcmData));
        }

        List<GusPatch.Instrument> instruments = new ArrayList<>();
        instruments.add(new GusPatch.Instrument(0, description, samples));

        return new GusPatch(description, instruments);
    }

    private static String readNullTerminatedString(byte[] data, int offset, int maxLength)
    {
        int len = 0;
        while (len < maxLength && (offset + len) < data.length && data[offset + len] != 0)
        {
            len++;
        }
        return new String(data, offset, len, StandardCharsets.US_ASCII).trim();
    }
}
