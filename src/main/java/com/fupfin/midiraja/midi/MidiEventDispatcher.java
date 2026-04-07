/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

/**
 * Routes a raw MIDI byte[] message to the appropriate method on a {@link MidiNativeBridge}.
 *
 * <p>
 * Extracted from {@link AbstractSoftSynthProvider} so the dispatch logic can be tested
 * independently of the render thread and event queue.
 */
class MidiEventDispatcher
{
    private final MidiNativeBridge bridge;

    MidiEventDispatcher(MidiNativeBridge bridge)
    {
        this.bridge = bridge;
    }

    void dispatch(byte[] data)
    {
        if (data == null || data.length == 0)
            return;

        int status = data[0] & 0xFF;
        if (status >= 0xF0)
        {
            if (data.length > 1)
                bridge.systemExclusive(data);
            return;
        }

        int command = status & 0xF0;
        int channel = status & 0x0F;
        if (data.length < 2)
            return;

        int data1 = data[1] & 0xFF;
        int data2 = (data.length >= 3) ? (data[2] & 0xFF) : 0;

        switch (command)
        {
            case 0x90 -> bridge.noteOn(channel, data1, data2);
            case 0x80 -> bridge.noteOff(channel, data1);
            case 0xB0 -> bridge.controlChange(channel, data1, data2);
            case 0xC0 -> bridge.patchChange(channel, data1);
            case 0xE0 -> bridge.pitchBend(channel, (data2 << 7) | data1);
        }
    }
}
