/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.gus;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GusBankTest {
  @Test
  void testParseGusCfg(@TempDir Path tempDir) throws IOException {
    // Mock a gus.cfg file
    String cfgContent = """
            # This is a comment
            bank 0
            0 acpiano.pat
            1 britepno.pat volume=110
            
            bank 1
            0 synth-bass.pat
            """;

    GusBank bank = new GusBank(tempDir);
    bank.loadConfig(cfgContent);

    // Check mapping
    assertEquals("acpiano.pat", bank.getPatchPath(0, 0).orElseThrow());
    assertEquals("britepno.pat", bank.getPatchPath(0, 1).orElseThrow());
    assertEquals("synth-bass.pat", bank.getPatchPath(1, 0).orElseThrow());

    // Check non-existent
    assertTrue(bank.getPatchPath(0, 99).isEmpty());
  }
}
