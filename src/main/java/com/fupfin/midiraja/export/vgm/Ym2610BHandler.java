/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * {@link ChipHandler} for YM2610B (OPNB extended) — 6 melodic FM channels + SSG + ADPCM-A.
 *
 * <p>
 * YM2610B is the extended variant of YM2610 used in the Neo Geo MVS. Unlike the standard
 * YM2610 which has only 4 FM channels (ch_addr 1,2 on port 0 and 5,6 on port 1), YM2610B
 * exposes all 6 FM channels using the same layout as YM2608: ch_addr 0,1,2 on port 0 and
 * 4,5,6 on port 1.
 *
 * <p>
 * VGM commands are the same as YM2610: {@code 0x58} (port 0) and {@code 0x59} (port 1).
 * YM2610B is activated in libvgm by setting bit 31 of the YM2610 clock field at VGM header
 * offset 0x4C. The SSG section is handled by an embedded {@link Ay8910Handler}; ADPCM-A
 * percussion uses the same built-in ROM as YM2610.
 */
final class Ym2610BHandler implements ChipHandler
{
    private static final int SLOTS = 6;
    private final Ay8910Handler ssg = Ay8910Handler.forYm2610Ssg();
    /** Hardware slot offsets within a port for operators S1, S3, S2, S4. */
    private static final int[] OP_SLOT_OFFSETS = { 0, 4, 8, 12 };

    private final WopnBankReader wopnBank = loadWopnBank();
    private static final byte[] ADPCM_A_ROM = loadAdpcmRom();

    /**
     * Per-channel ADPCM-A ROM address table: {startLow, startHigh, endLow, endHigh}.
     * Identical to {@link Ym2610Handler} — YM2610B shares the same ADPCM-A ROM.
     */
    private static final int[][] ADPCM_A_ADDRS = {
        { 0x00, 0x00, 0x09, 0x00 }, // ch0 Bass Drum:  0x0000-0x09FF
        { 0x0A, 0x00, 0x13, 0x00 }, // ch1 Snare:      0x0A00-0x13FF
        { 0x14, 0x00, 0x41, 0x00 }, // ch2 Top Cymbal: 0x1400-0x41FF
        { 0x42, 0x00, 0x48, 0x00 }, // ch3 Hi-Hat:     0x4200-0x48FF
        { 0x49, 0x00, 0x55, 0x00 }, // ch4 Tom Tom:    0x4900-0x55FF
        { 0x56, 0x00, 0x5D, 0x00 }, // ch5 Rim Shot:   0x5600-0x5DFF
    };

    /** Maps GM percussion note (0-127) to ADPCM-A channel (0-5), or -1 if unmapped. */
    private static final int[] GM_NOTE_TO_ADPCM_CH = AdpcmARhythmSupport.buildNoteMap();

    private static WopnBankReader loadWopnBank()
    {
        Path path = FmBankOverride.opnBankPath()
                .orElse(Path.of("ext/libOPNMIDI/fm_banks/gm.wopn"));
        try
        {
            return WopnBankReader.load(path);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] loadAdpcmRom()
    {
        try (var in = Ym2610BHandler.class.getResourceAsStream("ym2610_adpcm_a.bin"))
        {
            if (in == null)
                throw new IOException("ym2610_adpcm_a.bin not found in classpath");
            return in.readAllBytes();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ChipType chipType()
    {
        return ChipType.YM2610B;
    }

    @Override
    public int slotCount()
    {
        return SLOTS + ssg.slotCount(); // 6 FM + 2 SSG melodic channels
    }

    @Override
    public int percussionPriority()
    {
        return 3; // ADPCM-A native rhythm section
    }

    @Override
    public void initSilence(VgmWriter w)
    {
        var cfg = new AdpcmARhythmSupport.InitConfig(1, 0x00, 0x01, 0x10, 0x18, 0x20, 0x28,
                ADPCM_A_ADDRS);
        AdpcmARhythmSupport.initAdpcmAWithRom(w::writeRomDataBlock, 0x82, ADPCM_A_ROM,
                w::writeYm2610, cfg);

        // FM section init
        w.writeYm2610(0, 0x22, wopnBank.lfoFreq());
        w.writeYm2610(0, 0x27, 0x00);
        // Key-off all 6 FM channels
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeYm2610(0, 0x28, chAddr(slot));
        // SSG section init
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
        WopnBankReader.Patch patch = wopnBank.melodicPatch(program);
        writePatch(localSlot, patch, velocity, w);
        writeFreqKeyOn(localSlot, note + patch.noteOffset(), w);
    }

    @Override
    public void silenceSlot(int localSlot, VgmWriter w)
    {
        if (localSlot >= SLOTS)
        {
            ssg.silenceSlot(localSlot - SLOTS, w);
            return;
        }
        w.writeYm2610(0, 0x28, chAddr(localSlot)); // FM key-off
    }

    @Override
    public void finalSilence(VgmWriter w)
    {
        for (int slot = 0; slot < SLOTS; slot++)
            w.writeYm2610(0, 0x28, chAddr(slot));
        ssg.finalSilence(w);
    }

    @Override
    public void handlePercussion(int note, int velocity, VgmWriter w)
    {
        AdpcmARhythmSupport.triggerPercussion(w::writeYm2610, 1, note, velocity,
                GM_NOTE_TO_ADPCM_CH, 0x08, 0x00);
    }

    private void writePatch(int slot, WopnBankReader.Patch patch, int velocity, VgmWriter w)
    {
        // YM2610B uses full 6-channel layout: port 0 ch 0-2 (slots 0-2), port 1 ch 0-2 (slots 3-5)
        int port = slot < 3 ? 0 : 1;
        int ch = slot < 3 ? slot : slot - 3;
        int alg = patch.fbalg() & 0x07;

        for (int l = 0; l < 4; l++)
        {
            WopnBankReader.Operator op = patch.operators()[l];
            int regOff = ch + OP_SLOT_OFFSETS[l];
            int tl = Ym2612Handler.isCarrier(alg, l) ? Ym2612Handler.scaleTl(op.level(), velocity) : op.level();
            w.writeYm2610(port, 0x30 + regOff, op.dtfm());
            w.writeYm2610(port, 0x40 + regOff, tl);
            w.writeYm2610(port, 0x50 + regOff, op.rsatk());
            w.writeYm2610(port, 0x60 + regOff, op.amdecay1());
            w.writeYm2610(port, 0x70 + regOff, op.decay2());
            w.writeYm2610(port, 0x80 + regOff, op.susrel());
            w.writeYm2610(port, 0x90 + regOff, op.ssgeg());
        }
        w.writeYm2610(port, 0xB0 + ch, patch.fbalg() & 0x3F);
        w.writeYm2610(port, 0xB4 + ch, 0xC0 | (patch.lfosens() & 0x37));
    }

    private void writeFreqKeyOn(int slot, int note, VgmWriter w)
    {
        double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        int block = Math.clamp(note / 12 - 1, 0, 7);
        int fnum = (int) Math.round(freq * 144.0 * (1 << (21 - block)) / VgmWriter.YM2610_CLOCK);
        fnum = Math.clamp(fnum, 0, 0x7FF);

        int port = slot < 3 ? 0 : 1;
        int ch = slot < 3 ? slot : slot - 3;

        w.writeYm2610(port, 0xA4 + ch, (block << 3) | (fnum >> 8));
        w.writeYm2610(port, 0xA0 + ch, fnum & 0xFF);
        // Key-on all four operator slots
        w.writeYm2610(0, 0x28, (0xF << 4) | chAddr(slot));
    }

    /** Returns the key-on ch_addr for a given slot (0-5). YM2610B uses full 6-ch layout. */
    private static int chAddr(int slot)
    {
        return slot < 3 ? slot : slot - 3 + 4;
    }
}
