/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE
 * file in the root directory of this source tree.
 */

package com.midiraja.midi.gus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class GusBank {
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private final Path rootDir;
  // Map<BankNumber, Map<ProgramNumber, FileName>>
  private final Map<Integer, Map<Integer, String>> mappings = new HashMap<>();

  public GusBank(Path rootDir) { this.rootDir = rootDir; }

  @SuppressWarnings("StringSplitter")
  public void loadConfig(String content) throws IOException {
    try (BufferedReader reader =
             new BufferedReader(new StringReader(content))) {
      String line;
      int currentBank = 0;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }

        String[] parts = WHITESPACE.split(line);
        if (parts[0].equalsIgnoreCase("source") && parts.length > 1) {
          // Recursively load sourced file
          Path sourcePath = rootDir.resolve(parts[1]);
          if (java.nio.file.Files.exists(sourcePath)) {
            loadConfig(java.nio.file.Files.readString(
                sourcePath, java.nio.charset.StandardCharsets.US_ASCII));
          }
        } else if (parts[0].equalsIgnoreCase("bank") ||
                   parts[0].equalsIgnoreCase("drumset")) {
          if (parts.length > 1) {
            try {
              currentBank = Integer.parseInt(parts[1]);
              if (parts[0].equalsIgnoreCase("drumset")) {
                currentBank += 128; // Store drumsets at an offset (e.g., 128)
              }
            } catch (NumberFormatException ignored) {
              // Ignore invalid bank numbers
            }
          }
        } else if (parts.length >= 2 && parts[0].matches("\\d+")) {
          try {
            int program = Integer.parseInt(parts[0]);
            String patchFile = parts[1];
            mappings.computeIfAbsent(currentBank, k -> new HashMap<>())
                .put(program, patchFile);
          } catch (NumberFormatException ignored) {
            // Ignore invalid program numbers
          }
        }
      }
    }
  }

  public Optional<String> getPatchPath(int bank, int program) {
    Map<Integer, String> bankMap = mappings.get(bank);
    if (bankMap != null) {
      return Optional.ofNullable(bankMap.get(program));
    }
    return Optional.empty();
  }

  public Path getRootDir() { return rootDir; }
}
