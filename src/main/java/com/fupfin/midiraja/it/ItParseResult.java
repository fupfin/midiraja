/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.it;

import java.util.List;

/**
 * The result of parsing an Impulse Tracker (.it) module.
 *
 * @param title        module title (may be blank)
 * @param channelCount number of active channels (up to 64)
 * @param instruments  0-based list; index 0 corresponds to instrument slot 1
 * @param events       linearized note events in chronological order
 */
public record ItParseResult(String title, int channelCount,
                             List<ItInstrument> instruments, List<ItEvent> events) {}
