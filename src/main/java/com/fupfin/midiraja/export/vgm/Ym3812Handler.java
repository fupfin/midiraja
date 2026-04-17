/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for YM3812 (OPL2 / AdLib) — 9 melodic FM channels + 4 drum round-robin slots.
 *
 * <p>
 * OPL2 is a strict single-bank subset of OPL3: same 2-operator register layout, same WOPL bank
 * format, but only 9 channels with no bank 1. All writes use VGM command 0x5A.
 *
 * <p>
 * Drum slots 9-12 are mapped to OPL2 channels 5-8, round-robin, reusing the same channel
 * range as late melodic slots but with dedicated percussion patches.
 */
final class Ym3812Handler extends AbstractOplHandler
{
    private static final int MELODIC_SLOTS = 9;

    Ym3812Handler()
    {
        super(MELODIC_SLOTS);
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.YM3812;
    }

    @Override
    public int slotCount()
    {
        return melodicSlots; // 9 — drum slots are internal, not visible to CompositeVgmExporter
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        for (int ch = 0; ch < melodicSlots; ch++)
            w.writeOpl2(0xB0 + ch, 0);
    }

    @Override
    void writeOpl(int bank, int reg, int data, VgmWriter w)
    {
        w.writeOpl2(reg, data); // OPL2 is single-bank; bank arg ignored
    }

    @Override
    int channelBankIndex(int slot)
    {
        return channelIndex(slot); // bank=0 always (encoded as upper byte = 0)
    }

    @Override
    int fbConnFlags()
    {
        return 0x00; // OPL2 has no stereo flag
    }

    /**
     * Returns OPL2 channel index for a local slot.
     * Slots 0-8 → ch 0-8; drum slots 9-12 → ch 5-8.
     */
    static int channelIndex(int slot)
    {
        if (slot < MELODIC_SLOTS)
            return slot;
        return slot - MELODIC_SLOTS + 5;
    }
}
