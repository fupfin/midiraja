/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceLocatorTest
{
    @TempDir
    Path tempDir;

    // ── findFile ──────────────────────────────────────────────────────────────

    @Test
    void findFile_returns_absolute_path_when_file_exists() throws Exception
    {
        Path file = tempDir.resolve("sound.sf3");
        Files.createFile(file);

        var locator = new ResourceLocator(List.of(tempDir.toString()));

        var result = locator.findFile("sound.sf3");

        assertTrue(result.isPresent());
        assertTrue(result.get().isAbsolute(), "returned path must be absolute");
        assertEquals(file.toAbsolutePath(), result.get());
    }

    @Test
    void findFile_returns_empty_when_file_not_present()
    {
        var locator = new ResourceLocator(List.of(tempDir.toString()));

        assertTrue(locator.findFile("nonexistent.sf3").isEmpty());
    }

    @Test
    void findFile_returns_first_match_when_multiple_base_dirs_contain_file() throws Exception
    {
        Path dir1 = Files.createDirectory(tempDir.resolve("dir1"));
        Path dir2 = Files.createDirectory(tempDir.resolve("dir2"));
        Files.createFile(dir1.resolve("res.sf3"));
        Files.createFile(dir2.resolve("res.sf3"));

        var locator = new ResourceLocator(List.of(dir1.toString(), dir2.toString()));

        var result = locator.findFile("res.sf3");

        assertTrue(result.isPresent());
        assertEquals(dir1.resolve("res.sf3").toAbsolutePath(), result.get());
    }

    @Test
    void findFile_skips_dirs_without_file_and_finds_later_match() throws Exception
    {
        Path emptyDir = Files.createDirectory(tempDir.resolve("empty"));
        Path dir2 = Files.createDirectory(tempDir.resolve("dir2"));
        Files.createFile(dir2.resolve("res.sf3"));

        var locator = new ResourceLocator(List.of(emptyDir.toString(), dir2.toString()));

        var result = locator.findFile("res.sf3");

        assertTrue(result.isPresent());
        assertEquals(dir2.resolve("res.sf3").toAbsolutePath(), result.get());
    }

    @Test
    void findFile_does_not_match_directory_with_same_name() throws Exception
    {
        Files.createDirectory(tempDir.resolve("sound.sf3")); // directory, not file

        var locator = new ResourceLocator(List.of(tempDir.toString()));

        assertTrue(locator.findFile("sound.sf3").isEmpty(),
                "findFile must not match a directory");
    }

    @Test
    void findFile_resolves_nested_relative_path() throws Exception
    {
        Path sub = Files.createDirectory(tempDir.resolve("soundfonts"));
        Files.createFile(sub.resolve("FluidR3_GM.sf3"));

        var locator = new ResourceLocator(List.of(tempDir.toString()));

        var result = locator.findFile("soundfonts/FluidR3_GM.sf3");

        assertTrue(result.isPresent());
    }

    @Test
    void findFile_empty_base_dirs_returns_empty()
    {
        var locator = new ResourceLocator(List.of());

        assertTrue(locator.findFile("any.sf3").isEmpty());
    }

    // ── findDirectory ─────────────────────────────────────────────────────────

    @Test
    void findDirectory_returns_absolute_path_when_directory_exists() throws Exception
    {
        Path dir = Files.createDirectory(tempDir.resolve("demomidi"));

        var locator = new ResourceLocator(List.of(tempDir.toString()));

        var result = locator.findDirectory("demomidi");

        assertTrue(result.isPresent());
        assertTrue(result.get().isAbsolute());
        assertEquals(dir.toAbsolutePath(), result.get());
    }

    @Test
    void findDirectory_returns_empty_when_directory_not_present()
    {
        var locator = new ResourceLocator(List.of(tempDir.toString()));

        assertTrue(locator.findDirectory("missing").isEmpty());
    }

    @Test
    void findDirectory_does_not_match_file_with_same_name() throws Exception
    {
        Files.createFile(tempDir.resolve("demomidi")); // file, not directory

        var locator = new ResourceLocator(List.of(tempDir.toString()));

        assertTrue(locator.findDirectory("demomidi").isEmpty(),
                "findDirectory must not match a regular file");
    }

    @Test
    void findDirectory_returns_first_match_in_priority_order() throws Exception
    {
        Path base1 = Files.createDirectory(tempDir.resolve("base1"));
        Path base2 = Files.createDirectory(tempDir.resolve("base2"));
        Files.createDirectory(base1.resolve("data"));
        Files.createDirectory(base2.resolve("data"));

        var locator = new ResourceLocator(List.of(base1.toString(), base2.toString()));

        var result = locator.findDirectory("data");

        assertTrue(result.isPresent());
        assertEquals(base1.resolve("data").toAbsolutePath(), result.get());
    }

    // ── withMidraDataFirst (no env var override possible; test fallback path) ─

    @Test
    void withMidraDataFirst_uses_fallback_when_MIDRA_DATA_not_set() throws Exception
    {
        // If MIDRA_DATA is not set in the test environment, the fallback dir is used.
        // We verify that the file created under tempDir is found via the fallback list.
        Files.createFile(tempDir.resolve("test.sf3"));

        // Build via withMidraDataFirst — tempDir is the fallback
        // (MIDRA_DATA may or may not be set in CI; either way tempDir should be found)
        var locator = ResourceLocator.withMidraDataFirst(tempDir.toString());

        var result = locator.findFile("test.sf3");

        assertTrue(result.isPresent(), "fallback path must be searched");
    }
}
