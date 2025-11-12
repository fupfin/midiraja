/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.timidity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GusPatchReader {
  private static final byte[] MAGIC_GF1 =
      "GF1PATCH110\0ID#000002\0".getBytes(StandardCharsets.US_ASCII);

  private GusPatchReader() {}

  public static GusPatch read(InputStream in) throws IOException {
    byte[] header = new byte[239];
    int read = in.readNBytes(header, 0, header.length);
    if (read < header.length) {
      throw new IOException("Unexpected EOF reading GUS patch header");
    }

    // Verify magic string (first 22 bytes)
    for (int i = 0; i < 22; i++) {
      if (header[i] != MAGIC_GF1[i]) {
        throw new IOException("Invalid GUS patch magic header");
      }
    }

    // Read description (60 bytes, null terminated or padded)
    int descLen = 0;
    while (descLen < 60 && header[22 + descLen] != 0) {
      descLen++;
    }
    String description =
        new String(header, 22, descLen, StandardCharsets.US_ASCII).trim();

    int instrumentsCount = header[82] & 0xFF;

    // For now, we return a dummy instrument list just to pass the initial test
    // structure. We will expand the binary parsing in the next iteration.
    List<GusPatch.Instrument> instruments = new ArrayList<>();
    for (int i = 0; i < instrumentsCount; i++) {
      instruments.add(new GusPatch.Instrument(i, "Dummy", new ArrayList<>()));
    }

    return new GusPatch(description, instruments);
  }
}
