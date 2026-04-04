/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.it;

/**
 * A single instrument slot from an Impulse Tracker module.
 *
 * @param name   display name (up to 26 characters)
 * @param volume global volume (0–64)
 */
public record ItInstrument(String name, int volume) {}
