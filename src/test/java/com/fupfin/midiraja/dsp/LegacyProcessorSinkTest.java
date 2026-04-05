/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.dsp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class LegacyProcessorSinkTest
{
    static class CaptureSink implements AudioProcessor
    {
        float[] capturedLeft;
        float[] capturedRight;
        int capturedFrames = 0;
        boolean resetCalled = false;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            capturedLeft = left.clone();
            capturedRight = right.clone();
            capturedFrames = frames;
        }

        @Override
        public void reset()
        {
            resetCalled = true;
        }
    }

    static class TrackingProcessor implements AudioProcessor
    {
        int processCallCount = 0;
        int resetCallCount = 0;
        float[] lastLeft;
        float[] lastRight;
        int lastFrames;

        @Override
        public void process(float[] left, float[] right, int frames)
        {
            processCallCount++;
            lastLeft = left.clone();
            lastRight = right.clone();
            lastFrames = frames;
        }

        @Override
        public void reset()
        {
            resetCallCount++;
        }
    }

    // ── happy-path: processors are called ────────────────────────────────────

    @Test
    void process_emptyProcessorList_callsNextOnly()
    {
        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, Collections.emptyList());

        float[] left = { 0.5f, -0.5f };
        float[] right = { 0.3f, -0.3f };
        legacy.process(left.clone(), right.clone(), 2);

        assertTrue(sink.capturedFrames == 2, "Next sink should receive frames");
        assertArrayEquals(new float[] { 0.5f, -0.5f }, sink.capturedLeft, 0.001f);
    }

    @Test
    void process_singleProcessor_isCalledBeforeNext()
    {
        List<Integer> callOrder = new ArrayList<>();
        AudioProcessor processor = (l, r, f) -> callOrder.add(1);
        AudioProcessor nextSink = (l, r, f) -> callOrder.add(2);

        var legacy = new LegacyProcessorSink(nextSink, List.of(processor));
        legacy.process(new float[2], new float[2], 2);

        assertEquals(List.of(1, 2), callOrder,
                "Legacy processor should be called before next sink");
    }

    @Test
    void process_multipleProcessors_calledInOrder()
    {
        List<Integer> callOrder = new ArrayList<>();
        AudioProcessor p1 = (l, r, f) -> callOrder.add(1);
        AudioProcessor p2 = (l, r, f) -> callOrder.add(2);
        AudioProcessor p3 = (l, r, f) -> callOrder.add(3);
        AudioProcessor nextSink = (l, r, f) -> callOrder.add(4);

        var legacy = new LegacyProcessorSink(nextSink, List.of(p1, p2, p3));
        legacy.process(new float[4], new float[4], 4);

        assertEquals(List.of(1, 2, 3, 4), callOrder,
                "Processors and next should be called in registration order");
    }

    @Test
    void process_allProcessorsReceiveSameArrayReference()
    {
        float[][] receivedLeft = new float[2][];
        int[] idx = { 0 };
        AudioProcessor p1 = (l, r, f) -> receivedLeft[idx[0]++] = l;
        AudioProcessor p2 = (l, r, f) -> receivedLeft[idx[0]++] = l;

        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, List.of(p1, p2));

        float[] left = { 1.0f, 2.0f };
        float[] right = { 3.0f, 4.0f };
        legacy.process(left, right, 2);

        assertSame(receivedLeft[0], receivedLeft[1],
                "All processors share the same array reference (in-place modification allowed)");
    }

    @Test
    void process_frameCountPassedCorrectlyToAllProcessors()
    {
        var tracker = new TrackingProcessor();
        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, List.of(tracker));

        legacy.process(new float[10], new float[10], 7);

        assertEquals(7, tracker.lastFrames);
        assertEquals(7, sink.capturedFrames);
    }

    // ── boundary conditions ───────────────────────────────────────────────────

    @Test
    void process_zeroFrames_noExceptionThrown()
    {
        var sink = new CaptureSink();
        var tracker = new TrackingProcessor();
        var legacy = new LegacyProcessorSink(sink, List.of(tracker));

        assertDoesNotThrow(() -> legacy.process(new float[0], new float[0], 0));
        assertEquals(1, tracker.processCallCount, "Processor should still be called with 0 frames");
        assertEquals(0, sink.capturedFrames);
    }

    @Test
    void process_oneFrame_worksCorrectly()
    {
        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, Collections.emptyList());

        legacy.process(new float[] { 0.9f }, new float[] { -0.9f }, 1);

        assertEquals(1, sink.capturedFrames);
        assertEquals(0.9f, sink.capturedLeft[0], 0.0001f);
        assertEquals(-0.9f, sink.capturedRight[0], 0.0001f);
    }

    // ── reset propagation ────────────────────────────────────────────────────

    @Test
    void reset_callsResetOnAllProcessors()
    {
        var tracker1 = new TrackingProcessor();
        var tracker2 = new TrackingProcessor();
        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, List.of(tracker1, tracker2));

        legacy.reset();

        assertEquals(1, tracker1.resetCallCount, "First processor should receive reset()");
        assertEquals(1, tracker2.resetCallCount, "Second processor should receive reset()");
    }

    @Test
    void reset_propagatesToNextAfterProcessors()
    {
        List<Integer> callOrder = new ArrayList<>();
        var tracker = new TrackingProcessor()
        {
            @Override
            public void reset()
            {
                super.reset();
                callOrder.add(1);
            }
        };
        AudioProcessor nextSink = new AudioProcessor()
        {
            @Override
            public void process(float[] l, float[] r, int f) {}

            @Override
            public void reset()
            {
                callOrder.add(2);
            }
        };

        var legacy = new LegacyProcessorSink(nextSink, List.of(tracker));
        legacy.reset();

        assertEquals(List.of(1, 2), callOrder,
                "reset() should call processors first, then propagate to next");
    }

    @Test
    void reset_emptyProcessorList_propagatesToNext()
    {
        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, Collections.emptyList());

        legacy.reset();

        assertTrue(sink.resetCalled, "reset() should propagate to next even with empty processor list");
    }

    // ── multiple process() calls ─────────────────────────────────────────────

    @Test
    void process_calledMultipleTimes_eachCallInvokesAllProcessors()
    {
        var tracker = new TrackingProcessor();
        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, List.of(tracker));

        legacy.process(new float[2], new float[2], 2);
        legacy.process(new float[2], new float[2], 2);
        legacy.process(new float[2], new float[2], 2);

        assertEquals(3, tracker.processCallCount,
                "Processor should be called once per process() invocation");
    }

    // ── in-place modification by processors ──────────────────────────────────

    @Test
    void process_processorModifiesArray_nextReceivesModifiedValues()
    {
        AudioProcessor doubler = (left, right, frames) ->
        {
            for (int i = 0; i < frames; i++)
            {
                left[i] *= 2.0f;
                right[i] *= 2.0f;
            }
        };
        var sink = new CaptureSink();
        var legacy = new LegacyProcessorSink(sink, List.of(doubler));

        legacy.process(new float[] { 0.5f, 0.25f }, new float[] { 0.1f, 0.2f }, 2);

        assertEquals(1.0f, sink.capturedLeft[0], 0.001f,
                "Next sink should receive values after processor modification");
        assertEquals(0.5f, sink.capturedLeft[1], 0.001f);
    }
}
