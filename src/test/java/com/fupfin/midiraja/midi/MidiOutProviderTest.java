/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MidiOutProviderTest
{
    static class MockMidiProvider implements MidiOutProvider
    {
        List<byte[]> sentMessages = new ArrayList<>();

        @Override
        public List<MidiPort> getOutputPorts()
        {
            return new ArrayList<>();
        }

        @Override
        public void openPort(int portIndex) throws Exception
        {
        }

        @Override
        public void sendMessage(byte[] data) throws Exception
        {
            sentMessages.add(data);
        }

        @Override
        public void closePort()
        {
        }
    }

    @Test
    void testSetVolumeSendsCC7ToAllChannels() throws Exception
    {
        MockMidiProvider provider = new MockMidiProvider();
        provider.setVolume(64);

        assertEquals(16, provider.sentMessages.size(), "Should send 16 volume messages");

        for (int ch = 0; ch < 16; ch++)
        {
            byte[] msg = provider.sentMessages.get(ch);
            assertEquals(3, msg.length);
            assertEquals((byte) (0xB0 | ch), msg[0], "Must be Control Change for channel " + ch);
            assertEquals(7, msg[1], "Must be CC 7 (Main Volume)");
            assertEquals(64, msg[2], "Volume must be set to 64");
        }
    }
}
