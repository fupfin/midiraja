/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.io;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * UTF-8 guaranteed standard output and error streams.
 *
 * <p>Use instead of {@link System#out} and {@link System#err} to ensure Unicode output on all
 * platforms (including Windows with non-UTF-8 default code pages).
 */
public final class Console
{
    public static final PrintStream out =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    public static final PrintStream err =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private Console()
    {}
}
