/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.io.TerminalIO.TerminalKey;

class InputHandlerTest
{
    RecordingCommands engine;

    @BeforeEach
    void setUp()
    {
        engine = new RecordingCommands();
    }

    // -----------------------------------------------------------------------
    // handleCommonInput — one test per key
    // -----------------------------------------------------------------------

    @Test
    void volumeUp_callsAdjustVolumePositive()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.VOLUME_UP);
        assertEquals(List.of("adjustVolume:0.05"), engine.calls);
    }

    @Test
    void volumeDown_callsAdjustVolumeNegative()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.VOLUME_DOWN);
        assertEquals(List.of("adjustVolume:-0.05"), engine.calls);
    }

    @Test
    void nextTrack_callsRequestStopNext()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.NEXT_TRACK);
        assertEquals(List.of("requestStop:NEXT"), engine.calls);
    }

    @Test
    void prevTrack_callsRequestStopPrevious()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.PREV_TRACK);
        assertEquals(List.of("requestStop:PREVIOUS"), engine.calls);
    }

    @Test
    void transposeUp_callsAdjustTransposePositive()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.TRANSPOSE_UP);
        assertEquals(List.of("adjustTranspose:1"), engine.calls);
    }

    @Test
    void transposeDown_callsAdjustTransposeNegative()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.TRANSPOSE_DOWN);
        assertEquals(List.of("adjustTranspose:-1"), engine.calls);
    }

    @Test
    void speedUp_callsAdjustSpeedPositive()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.SPEED_UP);
        assertEquals(List.of("adjustSpeed:0.1"), engine.calls);
    }

    @Test
    void speedDown_callsAdjustSpeedNegative()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.SPEED_DOWN);
        assertEquals(List.of("adjustSpeed:-0.1"), engine.calls);
    }

    @Test
    void seekForward_callsSeekRelativePositive()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.SEEK_FORWARD);
        assertEquals(List.of("seekRelative:10000000"), engine.calls);
    }

    @Test
    void seekBackward_callsSeekRelativeNegative()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.SEEK_BACKWARD);
        assertEquals(List.of("seekRelative:-10000000"), engine.calls);
    }

    @Test
    void pause_callsTogglePause()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.PAUSE);
        assertEquals(List.of("togglePause"), engine.calls);
    }

    @Test
    void bookmark_callsFireBookmark()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.BOOKMARK);
        assertEquals(List.of("fireBookmark"), engine.calls);
    }

    @Test
    void toggleLoop_callsToggleLoop()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.TOGGLE_LOOP);
        assertEquals(List.of("toggleLoop"), engine.calls);
    }

    @Test
    void toggleShuffle_callsToggleShuffle()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.TOGGLE_SHUFFLE);
        assertEquals(List.of("toggleShuffle"), engine.calls);
    }

    @Test
    void quit_callsRequestStopQuitAll()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.QUIT);
        assertEquals(List.of("requestStop:QUIT_ALL"), engine.calls);
    }

    @Test
    void resumeSession_callsRequestStopResumeSession()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.RESUME_SESSION);
        assertEquals(List.of("requestStop:RESUME_SESSION"), engine.calls);
    }

    // -----------------------------------------------------------------------
    // handleMiniInput — suppression tests
    // -----------------------------------------------------------------------

    @Test
    void miniInput_toggleLoop_suppressed()
    {
        InputHandler.handleMiniInput(engine, TerminalKey.TOGGLE_LOOP);
        assertTrue(engine.calls.isEmpty(),
                "TOGGLE_LOOP should be suppressed in mini mode");
    }

    @Test
    void miniInput_toggleShuffle_suppressed()
    {
        InputHandler.handleMiniInput(engine, TerminalKey.TOGGLE_SHUFFLE);
        assertTrue(engine.calls.isEmpty(),
                "TOGGLE_SHUFFLE should be suppressed in mini mode");
    }

    @Test
    void miniInput_otherKeys_stillHandled()
    {
        InputHandler.handleMiniInput(engine, TerminalKey.PAUSE);
        assertEquals(List.of("togglePause"), engine.calls);
    }

    // -----------------------------------------------------------------------
    // Unknown key is a no-op
    // -----------------------------------------------------------------------

    @Test
    void unknownKey_none_doesNothing()
    {
        InputHandler.handleCommonInput(engine, TerminalKey.NONE);
        assertTrue(engine.calls.isEmpty(), "NONE key should produce no calls");
    }
}
