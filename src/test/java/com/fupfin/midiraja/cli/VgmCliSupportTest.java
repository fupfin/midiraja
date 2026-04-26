/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fupfin.midiraja.export.vgm.ChipType;
import com.fupfin.midiraja.export.vgm.FmBankOverride;
import com.fupfin.midiraja.export.vgm.RoutingMode;

class VgmCliSupportTest
{
    @AfterEach
    void tearDown()
    {
        FmBankOverride.clear();
    }

    @Test
    void resolvePlaybackChipSpec_defaultsToMegadrive()
    {
        var spec = VgmCliSupport.resolvePlaybackChipSpec(null, null);

        assertEquals(RoutingMode.SEQUENTIAL, spec.mode());
        assertEquals(java.util.List.of(ChipType.YM2612, ChipType.SN76489), spec.chips());
    }

    @Test
    void resolvePlaybackChipSpec_prefersChipList()
    {
        var spec = VgmCliSupport.resolvePlaybackChipSpec("msx", "ay8910+ym2413");

        assertEquals(java.util.List.of(ChipType.AY8910, ChipType.YM2413), spec.chips());
        assertEquals(RoutingMode.CHANNEL, spec.mode());
    }

    @Test
    void resolvePlaybackChipSpec_unknownSystem_throws()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VgmCliSupport.resolvePlaybackChipSpec("xyz", null));
        assertTrue(ex.getMessage().contains("Unknown --system value: 'xyz'"));
    }

    @Test
    void resolveExportChipSpec_usesOriginalSystemInErrorMessage()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VgmCliSupport.resolveExportChipSpec("XYZ", null));
        assertTrue(ex.getMessage().contains("Unknown --system value: 'XYZ'"));
    }

    @Test
    void resolveExportChipSpec_requiresChipSpecWhenSystemMissing()
    {
        assertThrows(NullPointerException.class, () -> VgmCliSupport.resolveExportChipSpec(null, null));
    }

    @Test
    void withBankOverride_appliesAndClearsAfterSuccess() throws Exception
    {
        VgmCliSupport.withBankOverride("opl:test-bank.wopl", () ->
        {
            assertTrue(FmBankOverride.oplBankPath().orElseThrow().toString().endsWith("test-bank.wopl"));
            assertTrue(FmBankOverride.opnBankPath().isEmpty());
            assertTrue(FmBankOverride.opmBankPath().isEmpty());
            return null;
        });

        assertTrue(FmBankOverride.oplBankPath().isEmpty());
        assertTrue(FmBankOverride.opnBankPath().isEmpty());
        assertTrue(FmBankOverride.opmBankPath().isEmpty());
    }

    @Test
    void withBankOverride_clearsAfterFailure() throws Exception
    {
        assertThrows(IllegalStateException.class, () -> VgmCliSupport.withBankOverride("opm:test-bank.bin", () ->
        {
            assertTrue(FmBankOverride.opmBankPath().orElseThrow().toString().endsWith("test-bank.bin"));
            throw new IllegalStateException("boom");
        }));

        assertTrue(FmBankOverride.oplBankPath().isEmpty());
        assertTrue(FmBankOverride.opnBankPath().isEmpty());
        assertTrue(FmBankOverride.opmBankPath().isEmpty());
    }
}
