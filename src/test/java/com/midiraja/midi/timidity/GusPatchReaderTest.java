/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.timidity;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class GusPatchReaderTest {
  @Test
  void testInvalidMagicHeader() {
    byte[] badData = "BADMAGIC_HEADER_DATA".getBytes(
        java.nio.charset.StandardCharsets.UTF_8);
    try (var in = new ByteArrayInputStream(badData)) {
      assertThrows(IOException.class,
                   ()
                       -> GusPatchReader.read(in),
                   "Should throw exception for invalid magic header");
    } catch (IOException e) {
      fail("Exception thrown closing stream");
    }
  }

  @Test
  void testValidHeaderParsing() throws IOException {
    // Construct a minimal valid GUS .pat header (239 bytes + some extra for
    // instrument)
    byte[] data = new byte[1024];

    // "GF1PATCH110\0ID#000002"
    System.arraycopy("GF1PATCH110\0ID#000002\0".getBytes(
                         java.nio.charset.StandardCharsets.UTF_8),
                     0, data, 0, 22);

    // Description (60 bytes)
    System.arraycopy("Acoustic Grand Piano".getBytes(
                         java.nio.charset.StandardCharsets.UTF_8),
                     0, data, 22, 20);

    // Instruments count (byte at 82)
    data[82] = 1;
    // Voices count (byte at 83)
    data[83] = 1;

    try (var in = new ByteArrayInputStream(data)) {
      GusPatch patch = GusPatchReader.read(in);
      assertNotNull(patch);
      assertEquals("Acoustic Grand Piano", patch.description());
      assertEquals(1, patch.instruments().size());
    }
  }
}
