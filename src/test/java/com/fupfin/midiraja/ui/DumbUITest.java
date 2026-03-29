/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */
package com.fupfin.midiraja.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.engine.PlaybackState;
import com.fupfin.midiraja.engine.PlaylistContext;
import com.fupfin.midiraja.io.MockTerminalIO;
import com.fupfin.midiraja.io.TerminalIO;
import com.fupfin.midiraja.midi.MidiPort;

class DumbUITest
{
    private static PlaybackState stopped()
    {
        var ctx = new PlaylistContext(
                List.of(new File("test.mid")), 0,
                new MidiPort(0, "TestPort"), null, false, false);
        return new PlaybackState()
        {
            @Override public PlaylistContext getContext() { return ctx; }
            @Override public javax.sound.midi.Sequence getSequence() { return null; }
            @Override public long getCurrentMicroseconds() { return 0; }
            @Override public long getTotalMicroseconds() { return 60_000_000L; }
            @Override public int[] getChannelPrograms() { return new int[16]; }
            @Override public float getCurrentBpm() { return 120f; }
            @Override public double getCurrentSpeed() { return 1.0; }
            @Override public int getCurrentTranspose() { return 0; }
            @Override public double getVolumeScale() { return 1.0; }
            @Override public boolean isPlaying() { return false; }
            @Override public boolean isPaused() { return false; }
            @Override public boolean isLoopEnabled() { return false; }
            @Override public boolean isShuffleEnabled() { return false; }
            @Override public boolean isBookmarked() { return false; }
            @Override public String getFilterDescription() { return ""; }
            @Override public String getPortSuffix() { return ""; }
            @Override public void addPlaybackEventListener(PlaybackEventListener l) {}
        };
    }

    @Test
    void runRenderLoop_default_printsOutput() throws Exception
    {
        var mockIO = new MockTerminalIO();
        ScopedValue.where(TerminalIO.CONTEXT, mockIO).call(() -> {
            new DumbUI().runRenderLoop(stopped());
            return null;
        });
        assertFalse(mockIO.getOutput().isEmpty(), "Default DumbUI should produce output");
    }

    @Test
    void runRenderLoop_quiet_producesNoOutput() throws Exception
    {
        var mockIO = new MockTerminalIO();
        ScopedValue.where(TerminalIO.CONTEXT, mockIO).call(() -> {
            new DumbUI(true).runRenderLoop(stopped());
            return null;
        });
        assertEquals("", mockIO.getOutput(), "Quiet DumbUI should produce no output");
    }

    @Test
    void runInputLoop_quiet_exitsWhenNotPlaying() throws Exception
    {
        var cmds = new RecordingCommands();
        cmds.stopAfter(0);
        var mockIO = new MockTerminalIO();
        ScopedValue.where(TerminalIO.CONTEXT, mockIO).call(() -> {
            new DumbUI(true).runInputLoop(cmds);
            return null;
        });
        assertEquals("", mockIO.getOutput());
    }
}
