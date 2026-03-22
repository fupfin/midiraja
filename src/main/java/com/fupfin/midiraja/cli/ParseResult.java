/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import java.io.File;
import java.util.List;

/** Result of a playlist parse: the ordered file list and any M3U directives found. */
record ParseResult(List<File> files, PlaylistDirectives directives) {}
