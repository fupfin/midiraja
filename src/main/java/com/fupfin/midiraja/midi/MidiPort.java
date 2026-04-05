/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

public record MidiPort(int index, String name)
{
    @Override
    public String toString()
    {
        return String.format("[%d] %s", index, name);
    }
}
