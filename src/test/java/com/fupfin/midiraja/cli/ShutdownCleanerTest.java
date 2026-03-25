/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiOutProvider;
import com.fupfin.midiraja.midi.MidiPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ShutdownCleanerTest
{
    /** Minimal TerminalIO stub — records whether close() was called. */
    static class FakeTerminalIO implements TerminalIO
    {
        boolean closed = false;

        @Override public boolean isInteractive() { return false; }
        @Override public void init() {}
        @Override public void close() { closed = true; }
        @Override public TerminalKey readKey() { return TerminalKey.NONE; }
        @Override public void print(String str) {}
        @Override public void println(String str) {}
        @Override public int getWidth() { return 80; }
        @Override public int getHeight() { return 24; }
    }

    /** Minimal MidiOutProvider stub — records whether panic() and closePort() were called. */
    static class FakeMidiOutProvider implements MidiOutProvider
    {
        boolean panicCalled = false;
        boolean closePortCalled = false;

        @Override public List<MidiPort> getOutputPorts() { return List.of(); }
        @Override public void openPort(int portIndex) {}
        @Override public void sendMessage(byte[] data) {}
        @Override public void closePort() { closePortCalled = true; }

        @Override
        public void panic()
        {
            panicCalled = true;
            // No sleep — tests should be fast
        }
    }

    @Test
    void portClosed_whenPortWasOpen()
    {
        var io = new FakeTerminalIO();
        var provider = new FakeMidiOutProvider();
        var portClosed = new AtomicBoolean(false);

        new ShutdownCleaner(false, io, provider, portClosed, false).run();

        assertTrue(provider.panicCalled, "panic() should be called");
        assertTrue(provider.closePortCalled, "closePort() should be called");
        assertTrue(portClosed.get(), "portClosed flag should be set to true");
        assertTrue(io.closed, "activeIO.close() should be called");
    }

    @Test
    void portNotClosedAgain_whenAlreadyClosed()
    {
        var io = new FakeTerminalIO();
        var provider = new FakeMidiOutProvider();
        var portClosed = new AtomicBoolean(true); // already closed

        new ShutdownCleaner(false, io, provider, portClosed, false).run();

        assertFalse(provider.panicCalled, "panic() must NOT be called when port was already closed");
        assertFalse(provider.closePortCalled, "closePort() must NOT be called when port was already closed");
        assertTrue(io.closed, "activeIO.close() should still be called");
    }

    @Test
    void ioClose_calledEvenWhenPortAlreadyClosed()
    {
        var io = new FakeTerminalIO();
        var provider = new FakeMidiOutProvider();
        var portClosed = new AtomicBoolean(true);

        new ShutdownCleaner(false, io, provider, portClosed, false).run();

        assertTrue(io.closed, "activeIO.close() should be called regardless of portClosed state");
    }

    @Test
    void closePort_calledEvenWhenPanicThrows()
    {
        var panicThrowsProvider = new FakeMidiOutProvider() {
            @Override public void panic() { throw new RuntimeException("boom"); }
        };
        var io = new FakeTerminalIO();
        var portClosed = new AtomicBoolean(false);

        assertDoesNotThrow(() -> new ShutdownCleaner(false, io, panicThrowsProvider, portClosed, false).run());
        assertTrue(io.closed, "activeIO.close() should be called even when panic() throws");
        assertTrue(panicThrowsProvider.closePortCalled, "closePort() should be called even when panic() throws");
    }
}
