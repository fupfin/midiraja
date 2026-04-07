/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves data files and directories from a prioritised list of base directories.
 *
 * <p>
 * The canonical search order is: {@code $MIDRA_DATA} (if set) followed by an ordered list of
 * fallback paths. Callers build an instance via {@link #withMidraDataFirst} and then use
 * {@link #findFile} or {@link #findDirectory} to locate resources.
 */
class ResourceLocator
{
    private final List<String> baseDirs;

    ResourceLocator(List<String> baseDirs)
    {
        this.baseDirs = baseDirs;
    }

    /**
     * Builds a locator with {@code $MIDRA_DATA} prepended (when the environment variable is set),
     * followed by the supplied {@code additionalPaths} in order.
     */
    static ResourceLocator withMidraDataFirst(String... additionalPaths)
    {
        List<String> dirs = new ArrayList<>();
        String midraData = System.getenv("MIDRA_DATA");
        if (midraData != null)
            dirs.add(midraData);
        dirs.addAll(List.of(additionalPaths));
        return new ResourceLocator(dirs);
    }

    /**
     * Returns the absolute path of the first regular file found at {@code relativePath} under any
     * base directory, or {@link Optional#empty()} if none exists.
     */
    Optional<Path> findFile(String relativePath)
    {
        return baseDirs.stream()
                .map(base -> Path.of(base, relativePath))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(Path::toAbsolutePath);
    }

    /**
     * Returns the absolute path of the first directory found at {@code relativePath} under any
     * base directory, or {@link Optional#empty()} if none exists.
     */
    Optional<Path> findDirectory(String relativePath)
    {
        return baseDirs.stream()
                .map(base -> Path.of(base, relativePath))
                .filter(Files::isDirectory)
                .findFirst()
                .map(Path::toAbsolutePath);
    }
}
