/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.gus;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GusPatchReader {
  private static final byte[] MAGIC_GF1 =
      "GF1PATCH110\0ID#000002\0".getBytes(StandardCharsets.US_ASCII);

  private GusPatchReader() {}

  public static GusPatch read(InputStream in) throws IOException {
    // 1. Read Global Header (239 bytes)
    byte[] headerData = in.readNBytes(239);
    if (headerData.length < 239) {
      throw new IOException("Unexpected EOF reading GUS patch header");
    }

    ByteBuffer header =
        ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN);

    // Verify magic
    for (int i = 0; i < 22; i++) {
      if (headerData[i] != MAGIC_GF1[i]) {
        throw new IOException("Invalid GUS patch magic header");
      }
    }

    // Description (60 bytes at index 22)
    String description = readNullTerminatedString(headerData, 22, 60);

    int instrumentsInFile = header.get(82) & 0xFF;

    List<GusPatch.Instrument> instruments = new ArrayList<>();
    for (int i = 0; i < instrumentsInFile; i++) {
      // 2. Read Instrument Header (63 bytes)
      byte[] instHeaderData = in.readNBytes(63);
      ByteBuffer instBuf =
          ByteBuffer.wrap(instHeaderData).order(ByteOrder.LITTLE_ENDIAN);

      int id = instBuf.getShort(0) & 0xFFFF;
      String name = readNullTerminatedString(instHeaderData, 2, 16);
      int samplesInInst = instBuf.get(22) & 0xFF;

      List<GusPatch.Sample> samples = new ArrayList<>();
      for (int s = 0; s < samplesInInst; s++) {
        // 3. Read Sample Header (96 bytes)
        byte[] sampleHeaderData = in.readNBytes(96);
        ByteBuffer sampleBuf =
            ByteBuffer.wrap(sampleHeaderData).order(ByteOrder.LITTLE_ENDIAN);

        int length = sampleBuf.getInt(8);
        int loopStart = sampleBuf.getInt(12);
        int loopEnd = sampleBuf.getInt(16);
        int sampleRate = sampleBuf.getShort(20) & 0xFFFF;
        int lowFreq = sampleBuf.getInt(22);
        int highFreq = sampleBuf.getInt(26);
        int rootFreq = sampleBuf.getInt(30);
        short pan = (short)(sampleBuf.get(36) & 0xFF);

        byte modes = sampleBuf.get(49);
        boolean is16Bit = (modes & 0x01) != 0;
        boolean isUnsigned = (modes & 0x02) != 0;

        // Note: Actual PCM data follows all sample headers in some formats,
        // but in standard GUS .pat, data for EACH sample follows its header.
        // We'll read the data immediately.
        byte[] pcmRaw = in.readNBytes(length);
        MemorySegment pcmData =
            Arena.ofAuto().allocateFrom(ValueLayout.JAVA_BYTE, pcmRaw);

        samples.add(new GusPatch.Sample(length, loopStart, loopEnd, sampleRate,
                                        lowFreq, highFreq, rootFreq, pan,
                                        is16Bit, isUnsigned, pcmData));
      }
      instruments.add(new GusPatch.Instrument(id, name, samples));
    }

    return new GusPatch(description, instruments);
  }

  private static String readNullTerminatedString(byte[] data, int offset,
                                                 int maxLength) {
    int len = 0;
    while (len < maxLength && (offset + len) < data.length &&
           data[offset + len] != 0) {
      len++;
    }
    return new String(data, offset, len, StandardCharsets.US_ASCII).trim();
  }
}
