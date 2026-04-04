/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.xm;

/**
 * A single instrument slot from a FastTracker 2 XM module.
 *
 * @param name   display name from the instrument header (up to 22 characters)
 * @param volume default volume of the first sample (0–64); 64 if the instrument has no samples
 */
public record XmInstrument(String name, int volume) {}
