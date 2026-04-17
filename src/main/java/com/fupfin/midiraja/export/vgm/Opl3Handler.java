/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for YMF262 (OPL3) — 14 melodic FM channels + 4 drum round-robin slots.
 *
 * <p>
 * Melodic slots 0-8 → OPL3 bank 0 channels 0-8; slots 9-13 → bank 1 channels 0-4.
 * Drum slots 14-17 → bank 1 channels 5-8. Each channel uses a 2-operator FM patch from the
 * libADLMIDI WOPL bank file.
 */
final class Opl3Handler extends AbstractOplHandler
{
    private static final int MELODIC_SLOTS = 14;

    Opl3Handler()
    {
        super(MELODIC_SLOTS);
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.OPL3;
    }

    @Override
    public int slotCount()
    {
        return melodicSlots; // 14 — drum slots are internal, not visible to CompositeVgmExporter
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        w.writeOpl3Bank1(0x05, 0x01); // Enable OPL3 mode
        for (int ch = 0; ch < 9; ch++)
            w.writeOpl3(0xB0 + ch, 0);
        for (int ch = 0; ch < 9; ch++)
            w.writeOpl3Bank1(0xB0 + ch, 0);
    }

    @Override
    void writeOpl(int bank, int reg, int data, VgmWriter w)
    {
        if (bank == 0)
            w.writeOpl3(reg, data);
        else
            w.writeOpl3Bank1(reg, data);
    }

    @Override
    int channelBankIndex(int slot)
    {
        return bankAndChannelIndex(slot);
    }

    @Override
    int fbConnFlags()
    {
        return 0xC0; // OPL3 stereo: bit 7 = RIGHT, bit 6 = LEFT
    }

    /**
     * Returns bank (upper byte) and OPL3 channel index (lower byte) for a local slot.
     * Slots 0-8 → bank 0, ch 0-8; slots 9-13 → bank 1, ch 0-4; slots 14-17 → bank 1, ch 5-8.
     */
    static int bankAndChannelIndex(int slot)
    {
        if (slot < 9)
            return slot;
        if (slot < MELODIC_SLOTS)
            return (1 << 8) | (slot - 9);
        return (1 << 8) | (slot - MELODIC_SLOTS + 5);
    }
}
