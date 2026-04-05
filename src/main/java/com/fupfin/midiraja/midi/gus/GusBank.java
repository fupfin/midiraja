/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.gus;

import static java.util.Locale.ROOT;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

public class GusBank
{
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final @Nullable Path rootDir;
    private final @Nullable ClassLoader resourceLoader;
    private final String resourceBase;
    private final String patchSetName;

    // Map<BankNumber, Map<ProgramNumber, FileName>>
    private final Map<Integer, Map<Integer, String>> mappings = new HashMap<>();

    /**
     * Creates a bank using the local file system.
     */
    public GusBank(Path rootDir)
    {
        this.rootDir = rootDir;
        this.resourceLoader = null;
        this.resourceBase = "";
        this.patchSetName = rootDir.getFileName() != null ? rootDir.getFileName().toString() : "custom";
    }

    /**
     * Creates a bank using JAR resources.
     */
    public GusBank(ClassLoader loader, String resourceBase)
    {
        this.rootDir = null;
        this.resourceLoader = loader;
        this.resourceBase = resourceBase.endsWith("/") ? resourceBase : resourceBase + "/";
        this.patchSetName = "freepats (embedded)";
    }

    public String getPatchSetName()
    {
        return patchSetName;
    }

    public void loadConfig(String content) throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new StringReader(content)))
        {
            parseReader(reader);
        }
    }

    public void loadConfig(InputStream in) throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII)))
        {
            parseReader(reader);
        }
    }

    @SuppressWarnings("StringSplitter")
    private void parseReader(BufferedReader reader) throws IOException
    {
        String line;
        int currentBank = 0;
        while ((line = reader.readLine()) != null)
        {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }

            String[] parts = WHITESPACE.split(line);
            if (parts[0].equalsIgnoreCase("source") && parts.length > 1)
            {
                loadSource(parts[1]);
            }
            else if (parts[0].equalsIgnoreCase("bank") || parts[0].equalsIgnoreCase("drumset"))
            {
                if (parts.length > 1)
                {
                    try
                    {
                        currentBank = Integer.parseInt(parts[1]);
                        if (parts[0].equalsIgnoreCase("drumset"))
                        {
                            currentBank += 128;
                        }
                    }
                    catch (NumberFormatException e)
                    {
                        // Ignore
                    }
                }
            }
            else if (parts.length >= 2 && parts[0].matches("\\d+"))
            {
                try
                {
                    int program = Integer.parseInt(parts[0]);
                    String patchFile = parts[1];
                    mappings.computeIfAbsent(currentBank, k -> new HashMap<>()).put(program,
                            patchFile);
                }
                catch (NumberFormatException e)
                {
                    // Ignore
                }
            }
        }
    }

    private void loadSource(String filename) throws IOException
    {
        if (rootDir != null)
        {
            Path sourcePath = rootDir.resolve(filename);
            if (Files.exists(sourcePath))
            {
                loadConfig(Files.readString(sourcePath, StandardCharsets.US_ASCII));
            }
        }
        else if (resourceLoader != null)
        {
            try (InputStream in = resourceLoader.getResourceAsStream(resourceBase + filename))
            {
                if (in != null)
                {
                    loadConfig(in);
                }
            }
        }
    }

    public Optional<InputStream> openPatchStream(String patchPath) throws IOException
    {
        // TiMidity allows dropping the .pat extension
        String filename = patchPath.toLowerCase(ROOT).endsWith(".pat")
                ? patchPath
                : patchPath + ".pat";

        if (rootDir != null)
        {
            Path p = rootDir.resolve(filename);
            if (Files.exists(p))
            {
                return Optional.of(Files.newInputStream(p));
            }
        }
        else if (resourceLoader != null)
        {
            InputStream in = resourceLoader.getResourceAsStream(resourceBase + filename);
            return Optional.ofNullable(in);
        }
        return Optional.empty();
    }

    public Optional<String> getPatchMapping(int bank, int program)
    {
        Map<Integer, String> bankMap = mappings.get(bank);
        if (bankMap != null)
        {
            return Optional.ofNullable(bankMap.get(program));
        }
        return Optional.empty();
    }

    public @Nullable Path getRootDir()
    {
        return rootDir;
    }
}
