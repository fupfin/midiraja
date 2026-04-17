/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for YM2612 (OPN2) — 5 melodic FM channels + 1 percussion FM channel.
 *
 * <p>
 * Channels 0-2 are on port 0, channels 3-5 are on port 1. Each channel uses a 4-operator
 * FM patch loaded from the WOPN2 GM bank bundled with libOPNMIDI. WOPN operators are stored
 * in hardware slot order [S1, S3, S2, S4] with offsets 0, 4, 8, 12 per channel.
 *
 * <p>
 * Slot 5 (port 1, channel 2) is reserved for percussion. MIDI channel 9 events use WOPN
 * percussion patches and are played at the patch's {@code percussionKeyNumber} tuning note.
 */
final class Ym2612Handler extends AbstractOpnHandler
{
    private static final int SLOTS = 6;
    /** Slot reserved for percussion (port 1, ch 2). Excluded from melodic slotCount. */
    private static final int PERC_SLOT = SLOTS - 1;

    Ym2612Handler()
    {
        super(SLOTS);
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.YM2612;
    }

    @Override
    public int slotCount()
    {
        return SLOTS - 1; // slot 5 reserved for percussion
    }

    @Override
    public int percussionPriority()
    {
        return 2; // FM patch percussion
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Set LFO frequency as specified by the bank (bit 3 = enable, bits 2-0 = frequency)
        w.writeYm2612(0, 0x22, wopnBank().lfoFreq());
        // Channel 3 normal mode (disable CSM / 3-slot special mode)
        w.writeYm2612(0, 0x27, 0x00);
        // Disable DAC so channel 5 is available for FM synthesis
        w.writeYm2612(0, 0x2B, 0x00);
        // Key-off all 6 channels (ch_addr: ch 0-2 → 0-2, ch 3-5 → 4-6)
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeYm2612(0, 0x28, chAddr(slot));
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        if (velocity == 0)
        {
            silenceSlot(PERC_SLOT, w);
            return;
        }
        WopnBankReader.Patch patch;
        try
        {
            patch = wopnBank().percussionPatch(note);
        }
        catch (IllegalArgumentException e)
        {
            return; // no percussion patch for this GM note
        }
        int keyNote = patch.percussionKeyNumber() > 0 ? patch.percussionKeyNumber() : note;
        startNoteWithPatch(PERC_SLOT, patch, keyNote, velocity, w);
    }

    @Override
    void writeFm(int port, int reg, int data, VgmWriter w)
    {
        w.writeYm2612(port, reg, data);
    }

    @Override
    int fmClock()
    {
        return VgmWriter.YM2612_CLOCK;
    }

    @Override
    int chAddr(int slot)
    {
        return slot < 3 ? slot : slot - 3 + 4;
    }

    @Override
    int portOf(int slot)
    {
        return slot < 3 ? 0 : 1;
    }

    @Override
    int chOf(int slot)
    {
        return slot < 3 ? slot : slot - 3;
    }

    // ── Backward-compat stubs for Ym2612HandlerTest ────────────────────────────

    /** @see AbstractOpnHandler#isCarrier */
    static boolean isCarrier(int alg, int opIndex)
    {
        return AbstractOpnHandler.isCarrier(alg, opIndex);
    }

    /** @see AbstractOpnHandler#scaleTl */
    static int scaleTl(int tl, int velocity)
    {
        return AbstractOpnHandler.scaleTl(tl, velocity);
    }
}
