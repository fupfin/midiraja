/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.s3m;

import java.util.List;

/**
 * The result of parsing a Scream Tracker 3 (.s3m) file.
 *
 * @param title       module title (may be blank)
 * @param channelCount number of active PCM channels (up to 16)
 * @param instruments  0-based list; index 0 corresponds to instrument slot 1
 * @param events       linearized note events in chronological order
 */
public record S3mParseResult(String title, int channelCount,
                              List<S3mInstrument> instruments, List<S3mEvent> events) {}
