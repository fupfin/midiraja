/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * {@link ChipHandler} for YM2203 (OPN) — 3 melodic FM channels + AY-3-8910 SSG.
 *
 * <p>
 * YM2203 is the original OPN chip used in the NEC PC-88. It has 3 FM channels with the same
 * 4-operator engine as YM2612, but uses a single port (no port switching). VGM command is
 * {@code 0x55} and the clock is 3,993,600 Hz (PC-88 standard). The SSG section
 * (AY-3-8910 compatible, 3 tone + noise, registers 0x00-0x0F) is handled by an embedded
 * {@link Ay8910Handler}.
 *
 * <p>
 * Channel addressing: {@code ch_addr = slot} (0, 1, 2). There is no port 1 on YM2203.
 */
final class Ym2203Handler extends AbstractOpnHandler
{
    private static final int SLOTS = 3;

    private final Ay8910Handler ssg = Ay8910Handler.forYm2203Ssg();

    Ym2203Handler()
    {
        super(SLOTS);
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.YM2203;
    }

    @Override
    public int slotCount()
    {
        return SLOTS + ssg.slotCount(); // 3 FM + 2 SSG melodic channels
    }

    @Override
    public int percussionPriority()
    {
        return 0; // no dedicated percussion hardware
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        // Set LFO frequency as specified by the bank (bit 3 = enable, bits 2-0 = frequency)
        w.writeOpn(0x22, wopnBank().lfoFreq());
        // Channel 3 normal mode (disable CSM / 3-slot special mode)
        w.writeOpn(0x27, 0x00);
        // Key-off all 3 FM channels
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeOpn(0x28, slot);
        // SSG section init (registers 0x00-0x0F)
        ssg.initSilence(w);
    }

    @Override
    public void startNote(int localSlot, int note, int velocity, int program, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.startNote(localSlot - SLOTS, note, velocity, program, w);
            return;
        }
        super.startNote(localSlot, note, velocity, program, w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.silenceSlot(localSlot - SLOTS, w);
            return;
        }
        super.silenceSlot(localSlot, w);
    }

    @Override
    public void updatePitch(int localSlot, int note, int pitchBend, int bendRangeSemitones,
            VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.updatePitch(localSlot - SLOTS, note, pitchBend, bendRangeSemitones, w);
            return;
        }
        super.updatePitch(localSlot, note, pitchBend, bendRangeSemitones, w);
    }

    @Override
    public void updateVolume(int localSlot, int velocity, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.updateVolume(localSlot - SLOTS, velocity, w);
            return;
        }
        super.updateVolume(localSlot, velocity, w);
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeOpn(0x28, slot);
        ssg.finalSilence(w);
    }

    @Override
    void writeFm(int port, int reg, int data, VgmWriter w)
    {
        w.writeOpn(reg, data); // YM2203 is single-port; port arg unused
    }

    @Override
    int fmClock()
    {
        return VgmWriter.YM2203_CLOCK;
    }

    @Override
    int chAddr(int slot)
    {
        return slot; // ch_addr = slot (0, 1, 2)
    }

    @Override
    int portOf(int slot)
    {
        return 0; // single-port chip
    }

    @Override
    int chOf(int slot)
    {
        return slot;
    }
}
