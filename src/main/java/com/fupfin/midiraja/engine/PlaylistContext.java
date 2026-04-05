/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.engine;

import java.io.File;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fupfin.midiraja.midi.MidiPort;

/**
 * Encapsulates the playback context needed by the UI loop to draw the full-screen terminal
 * dashboard.
 */
public record PlaylistContext(
        List<File> files, int currentIndex, MidiPort targetPort,
        @Nullable String sequenceTitle,
        boolean loop, boolean shuffle)
{
}
