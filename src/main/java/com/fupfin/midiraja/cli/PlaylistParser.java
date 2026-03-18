/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;


import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a flat playlist from a mix of .mid files, directories, and .m3u playlists. M3U files may
 * contain {@code #MIDRA:} directives that update the {@link CommonOptions} (volume, speed, shuffle,
 * loop, recursive) as a side effect.
 */
public class PlaylistParser
{
    private final PrintStream err;
    private final boolean verbose;

    public PlaylistParser(PrintStream err, boolean verbose)
    {
        this.err = err;
        this.verbose = verbose;
    }

    /**
     * Expands {@code rawFiles} into an ordered list of MIDI files. Directories are scanned
     * according to {@code common.recursive}. M3U directives may mutate {@code common.shuffle},
     * {@code common.loop}, {@code common.recursive}, {@code common.volume}, and
     * {@code common.speed}.
     */
    public List<File> parse(List<File> rawFiles, CommonOptions common)
    {
        List<File> playlist = new ArrayList<>();
        for (File f : rawFiles)
        {
            f = normalize(f);
            String nameLower = f.getName().toLowerCase(Locale.ROOT);
            if (Files.isDirectory(f.toPath()))
            {
                parseDirectory(f, playlist, common.recursive);
            }
            else if (nameLower.endsWith(".m3u") || nameLower.endsWith(".m3u8")
                    || nameLower.endsWith(".txt"))
            {
                parsePlaylistFile(f, playlist, common);
            }
            else
            {
                playlist.add(f);
            }
        }
        return playlist;
    }

    @SuppressWarnings({"StringSplitter", "EmptyCatch"})
    private void parsePlaylistFile(File playlistFile, List<File> playlist, CommonOptions common)
    {
        try
        {
            List<String> lines = Files.readAllLines(playlistFile.toPath());
            File parentDir = playlistFile.getParentFile();

            for (String rawLine : lines)
            {
                String line = rawLine.trim();

                // Parse Custom M3U Directives: #MIDRA: --option
                if (line.toUpperCase(Locale.ROOT).startsWith("#MIDRA:"))
                {
                    String directive = line.substring(7).trim();

                    // Parse all tokens in one pass; use exact equality to avoid
                    // substring false-positives (e.g. -s inside --speed, -r inside --reset).
                    String[] tokens = directive.split("\\s+");
                    for (int i = 0; i < tokens.length; i++)
                    {
                        String token = tokens[i];

                        // Boolean flags
                        if (token.equals("--shuffle") || token.equals("-s"))
                        {
                            common.shuffle = true;
                            logVerbose("Applied directive from playlist: --shuffle");
                        }
                        else if (token.equals("--loop") || token.equals("-r"))
                        {
                            common.loop = true;
                            logVerbose("Applied directive from playlist: --loop");
                        }
                        else if (token.equals("--recursive") || token.equals("-R"))
                        {
                            common.recursive = true;
                            logVerbose("Applied directive from playlist: --recursive");
                        }

                        // Key-value directives
                        if (token.startsWith("--volume=") || token.startsWith("-v="))
                        {
                            try
                            {
                                common.volume =
                                        Integer.parseInt(token.substring(token.indexOf('=') + 1));
                                logVerbose("Applied directive from playlist: " + token);
                            }
                            catch (NumberFormatException ignored)
                            {
                            }
                        }
                        else if ((token.equals("--volume") || token.equals("-v"))
                                && i + 1 < tokens.length)
                        {
                            try
                            {
                                common.volume = Integer.parseInt(tokens[++i]);
                                logVerbose("Applied directive from playlist: " + token + " "
                                        + common.volume);
                            }
                            catch (NumberFormatException ignored)
                            {
                            }
                        }

                        if (token.startsWith("--speed=") || token.startsWith("-x="))
                        {
                            try
                            {
                                common.speed =
                                        Double.parseDouble(token.substring(token.indexOf('=') + 1));
                                logVerbose("Applied directive from playlist: " + token);
                            }
                            catch (NumberFormatException ignored)
                            {
                            }
                        }
                        else if ((token.equals("--speed") || token.equals("-x"))
                                && i + 1 < tokens.length)
                        {
                            try
                            {
                                common.speed = Double.parseDouble(tokens[++i]);
                                logVerbose("Applied directive from playlist: " + token + " "
                                        + common.speed);
                            }
                            catch (NumberFormatException ignored)
                            {
                            }
                        }
                    }
                    continue;
                }

                if (line.isEmpty() || line.startsWith("#"))
                {
                    continue;
                }

                File track = new File(line);
                if (!track.isAbsolute() && parentDir != null)
                {
                    track = new File(parentDir, line);
                }

                if (Files.isDirectory(track.toPath()))
                {
                    parseDirectory(track, playlist, common.recursive);
                }
                else if (track.exists())
                {
                    playlist.add(track);
                }
                else
                {
                    logVerbose("Playlist track not found: " + track.getAbsolutePath());
                }
            }
            logVerbose("Loaded playlist: " + playlistFile.getName() + " (" + lines.size()
                    + " lines parsed)");
        }
        catch (Exception e)
        {
            System.err.println("[Error in " + getClass().getSimpleName() + "] " + e.getMessage());
            err.println("Error reading playlist file '" + playlistFile.getName() + "': "
                    + e.getMessage());
            if (verbose) e.printStackTrace(err);
        }
    }

    void parseDirectory(File dir, List<File> playlist, boolean recursive)
    {
        try
        {
            int maxDepth = recursive ? Integer.MAX_VALUE : 1;
            try (var stream = Files.walk(dir.toPath(), maxDepth))
            {
                stream.filter(Files::isRegularFile)
                        .sorted()
                        .map(Path::toFile)
                        .filter(f -> {
                            String name = f.getName().toLowerCase(Locale.ROOT);
                            return name.endsWith(".mid") || name.endsWith(".midi");
                        }).forEach(playlist::add);
            }
        }
        catch (Exception e)
        {
            System.err.println("[Error in " + getClass().getSimpleName() + "] " + e.getMessage());
            err.println("Error reading directory '" + dir.getName() + "': " + e.getMessage());
            if (verbose) e.printStackTrace(err);
        }
    }

    /**
     * Strips trailing {@code "} characters from a file path. On Windows, PowerShell wraps
     * arguments in double-quotes when they end with {@code \}, which the Windows command-line
     * parser then converts to a trailing literal {@code "} in the received string.
     */
    public static File normalize(File f)
    {
        String path = f.getPath();
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '"')
        {
            end--;
        }
        return end == path.length() ? f : new File(path.substring(0, end));
    }

    private void logVerbose(String message)
    {
        if (verbose)
        {
            err.println("[VERBOSE] " + message);
        }
    }
}
