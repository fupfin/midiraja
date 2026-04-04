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
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

/**
 * Builds a flat playlist from a mix of .mid files, directories, and .m3u playlists. M3U files may
 * contain {@code #MIDRA:} directives that are returned via {@link ParseResult#directives()}.
 */
public class PlaylistParser
{
    private static final java.util.logging.Logger log =
            java.util.logging.Logger.getLogger(PlaylistParser.class.getName());

    private final PrintStream err;
    private final boolean verbose;

    public PlaylistParser(PrintStream err, boolean verbose)
    {
        this.err = err;
        this.verbose = verbose;
    }

    /**
     * Expands {@code rawFiles} into an ordered list of MIDI files. Directories are scanned
     * according to {@code common.recursive}. M3U {@code #MIDRA:} directives are collected and
     * returned via {@link ParseResult#directives()}; {@code common} is never modified by this
     * method.
     */
    public ParseResult parse(List<File> rawFiles, CommonOptions common)
    {
        List<File> playlist = new ArrayList<>();
        var acc = new DirectiveAccumulator(common.recursive);
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
                parsePlaylistFile(f, playlist, acc);
            }
            else
            {
                playlist.add(f);
            }
        }
        return new ParseResult(playlist, acc.build());
    }

    @SuppressWarnings({"StringSplitter", "EmptyCatch"})
    private void parsePlaylistFile(File playlistFile, List<File> playlist, DirectiveAccumulator acc)
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
                            acc.shuffle = true;
                            logVerbose("Applied directive from playlist: --shuffle");
                        }
                        else if (token.equals("--loop") || token.equals("-r"))
                        {
                            acc.loop = true;
                            logVerbose("Applied directive from playlist: --loop");
                        }
                        else if (token.equals("--recursive") || token.equals("-R"))
                        {
                            acc.directiveRecursive = true;
                            acc.effectiveRecursive = true;
                            logVerbose("Applied directive from playlist: --recursive");
                        }

                        // Key-value directives
                        int[] vIdx = {i};
                        String rawVolume = parseKeyValue(tokens, i, vIdx, "--volume", "-v");
                        if (rawVolume != null)
                        {
                            try
                            {
                                acc.volume = Integer.parseInt(rawVolume);
                                logVerbose("Applied directive from playlist: --volume " + acc.volume);
                            }
                            catch (NumberFormatException e)
                            {
                            }
                            i = vIdx[0];
                        }

                        int[] xIdx = {i};
                        String rawSpeed = parseKeyValue(tokens, i, xIdx, "--speed", "-x");
                        if (rawSpeed != null)
                        {
                            try
                            {
                                acc.speed = Double.parseDouble(rawSpeed);
                                logVerbose("Applied directive from playlist: --speed " + acc.speed);
                            }
                            catch (NumberFormatException e)
                            {
                            }
                            i = xIdx[0];
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
                    parseDirectory(track, playlist, acc.effectiveRecursive);
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
            log.warning("Error reading playlist file '" + playlistFile.getName() + "': " + e.getMessage());
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
                            return name.endsWith(".mid") || name.endsWith(".midi")
                                    || name.endsWith(".vgm") || name.endsWith(".vgz")
                                    || name.endsWith(".mod");
                        }).forEach(playlist::add);
            }
        }
        catch (Exception e)
        {
            log.warning("Error reading directory '" + dir.getName() + "': " + e.getMessage());
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

    /**
     * Tries to extract the value for a key-value directive in either {@code --key=val} or
     * {@code --key val} form.
     *
     * @param tokens   full token array
     * @param i        current index
     * @param indexRef single-element array; updated to the new index when {@code --key val} form
     *                 consumes the next token (otherwise left unchanged)
     * @param longKey  long form, e.g. {@code "--volume"}
     * @param shortKey short form, e.g. {@code "-v"}
     * @return the raw string value, or {@code null} if the token does not match either form
     */
    private static @Nullable String parseKeyValue(String[] tokens, int i, int[] indexRef,
            String longKey, String shortKey)
    {
        String token = tokens[i];
        if (token.startsWith(longKey + "=") || token.startsWith(shortKey + "="))
            return token.substring(token.indexOf('=') + 1);
        if ((token.equals(longKey) || token.equals(shortKey)) && i + 1 < tokens.length)
        {
            indexRef[0] = i + 1;
            return tokens[i + 1];
        }
        return null;
    }

    private void logVerbose(String message)
    {
        if (verbose)
        {
            err.println("[VERBOSE] " + message);
        }
    }

    private static final class DirectiveAccumulator
    {
        boolean shuffle;
        boolean loop;
        /** Set to {@code true} only when a {@code --recursive} directive was parsed. */
        boolean directiveRecursive;
        /**
         * Working recursive flag for mid-parse directory scanning inside an M3U.
         * Seeded from {@code common.recursive} at parse start; updated to {@code true}
         * by the {@code --recursive} directive so that subsequent directory entries in
         * the same M3U are scanned recursively.
         */
        boolean effectiveRecursive;
        int     volume = -1;         // -1 = no directive
        double  speed  = Double.NaN; // NaN = no directive

        DirectiveAccumulator(boolean initialRecursive)
        {
            this.effectiveRecursive = initialRecursive;
        }

        PlaylistDirectives build()
        {
            if (volume == -1 && Double.isNaN(speed) && !shuffle && !loop && !directiveRecursive)
                return PlaylistDirectives.NONE;
            return new PlaylistDirectives(
                    volume == -1 ? OptionalInt.empty() : OptionalInt.of(volume),
                    Double.isNaN(speed) ? OptionalDouble.empty() : OptionalDouble.of(speed),
                    shuffle, loop, directiveRecursive);
        }
    }
}
