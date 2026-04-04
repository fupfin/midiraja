/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.s3m;

/**
 * A single instrument (PCM sample slot) from a Scream Tracker 3 module.
 *
 * @param name   display name from the instrument header (up to 28 characters)
 * @param volume default volume (0–64)
 */
public record S3mInstrument(String name, int volume) {}
