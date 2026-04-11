/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.FunctionDescriptor;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.midi.AbstractFFMBridge.LibProbeResult;

class FFMLibvgmBridgeTest
{

    // ── Library probe ─────────────────────────────────────────────────────────

    @Test
    void probe_returnsResult()
    {
        LibProbeResult result = FFMLibvgmBridge.probe();
        assertNotNull(result, "probe() should never return null");
    }

    @Test
    void probe_doesNotThrow()
    {
        assertDoesNotThrow(() -> FFMLibvgmBridge.probe(),
                "probe() should not throw even if library is missing");
    }

    @Test
    void probe_resultContainsFoundFlag()
    {
        LibProbeResult result = FFMLibvgmBridge.probe();
        assertNotNull(result.found(), "LibProbeResult.found() should return a boolean value");
        // Note: we cannot guarantee whether the library is found or not,
        // as it depends on the build environment. Just verify the flag is set.
    }

    @Test
    void probe_resultForNotFoundHasNullPath()
    {
        LibProbeResult result = FFMLibvgmBridge.probe();
        if (!result.found())
        {
            assertNull(result.resolvedPath(), "When library not found, resolvedPath should be null");
        }
    }

    @Test
    void probe_resultForFoundHasNonNullPath()
    {
        LibProbeResult result = FFMLibvgmBridge.probe();
        if (result.found())
        {
            assertNotNull(result.resolvedPath(), "When library found, resolvedPath should not be null");
            assertFalse(result.resolvedPath().isEmpty(), "When library found, resolvedPath should not be empty");
        }
    }

    // ── Downcall descriptors ──────────────────────────────────────────────────

    @Test
    void allDowncallDescriptors_isNonEmpty()
    {
        List<FunctionDescriptor> descriptors = FFMLibvgmBridge.allDowncallDescriptors();
        assertNotNull(descriptors, "allDowncallDescriptors() should not return null");
        assertFalse(descriptors.isEmpty(), "Descriptor list should contain at least one entry");
    }

    @Test
    void allDowncallDescriptors_containsMultipleDescriptors()
    {
        List<FunctionDescriptor> descriptors = FFMLibvgmBridge.allDowncallDescriptors();
        assertTrue(descriptors.size() >= 5, "Should have at least 5 function descriptors");
    }

    @Test
    void allDowncallDescriptors_allEntriesNonNull()
    {
        List<FunctionDescriptor> descriptors = FFMLibvgmBridge.allDowncallDescriptors();
        for (FunctionDescriptor desc : descriptors)
        {
            assertNotNull(desc, "All descriptors should be non-null");
        }
    }

    // ── Consistency check ─────────────────────────────────────────────────────

    @Test
    void allDowncallDescriptors_isConsistent()
    {
        // Calling the method multiple times should return equivalent lists
        List<FunctionDescriptor> descriptors1 = FFMLibvgmBridge.allDowncallDescriptors();
        List<FunctionDescriptor> descriptors2 = FFMLibvgmBridge.allDowncallDescriptors();
        assertEquals(descriptors1.size(), descriptors2.size(),
                "Descriptor list size should be consistent across multiple calls");
    }

}
