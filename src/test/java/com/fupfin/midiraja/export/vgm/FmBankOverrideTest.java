/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FmBankOverrideTest
{
    @AfterEach
    void tearDown()
    {
        FmBankOverride.clear();
    }

    @Test
    void apply_emptySpec_clearsAll()
    {
        FmBankOverride.apply("");
        assertTrue(FmBankOverride.oplBankPath().isEmpty());
        assertTrue(FmBankOverride.opnBankPath().isEmpty());
        assertTrue(FmBankOverride.opmBankPath().isEmpty());
    }

    @Test
    void apply_extensionInference_assignsByType()
    {
        FmBankOverride.apply("a.wopl,b.wopn,c.bin");
        assertTrue(FmBankOverride.oplBankPath().orElseThrow().toString().endsWith("a.wopl"));
        assertTrue(FmBankOverride.opnBankPath().orElseThrow().toString().endsWith("b.wopn"));
        assertTrue(FmBankOverride.opmBankPath().orElseThrow().toString().endsWith("c.bin"));
    }

    @Test
    void apply_prefixedSpec_assignsExplicitType()
    {
        FmBankOverride.apply("opm:foo.dat,opn:bar.dat,opl:baz.dat");
        assertTrue(FmBankOverride.opmBankPath().orElseThrow().toString().endsWith("foo.dat"));
        assertTrue(FmBankOverride.opnBankPath().orElseThrow().toString().endsWith("bar.dat"));
        assertTrue(FmBankOverride.oplBankPath().orElseThrow().toString().endsWith("baz.dat"));
    }

    @Test
    void apply_unknownToken_throws()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FmBankOverride.apply("not-a-bank"));
        assertTrue(ex.getMessage().contains("Cannot infer bank type"));
    }
}
