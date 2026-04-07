/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.tracker;

/**
 * A single instrument slot shared across tracker formats (S3M, XM, IT).
 *
 * @param name
 *            display name (up to 28 characters depending on format)
 * @param volume
 *            default volume (0–64)
 */
public record TrackerInstrument(String name, int volume)
{
}
