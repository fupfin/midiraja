/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.xm;

import java.util.List;

/**
 * The result of parsing a FastTracker 2 XM module.
 *
 * @param title        module title (may be blank)
 * @param channelCount number of channels (1–32)
 * @param instruments  0-based list; index 0 corresponds to instrument slot 1
 * @param events       linearized note events in chronological order
 */
public record XmParseResult(String title, int channelCount,
                             List<XmInstrument> instruments, List<XmEvent> events) {}
