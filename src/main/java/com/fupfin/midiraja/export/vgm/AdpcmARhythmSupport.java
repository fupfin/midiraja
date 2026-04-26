/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.util.Arrays;

final class AdpcmARhythmSupport
{
    record InitConfig(int port, int keyOffReg, int masterLevelReg, int startLowBaseReg,
            int startHighBaseReg, int endLowBaseReg, int endHighBaseReg, int[][] addrs) {}

    @FunctionalInterface
    interface RegisterWriter
    {
        void write(int port, int reg, int data);
    }

    @FunctionalInterface
    interface RomDataBlockWriter
    {
        void write(int type, int dataLength, int address, byte[] data);
    }

    private static final int[][] NOTE_GROUPS = {
        { 35, 36 }, // Bass Drum
        { 38, 40 }, // Snare
        { 49, 51, 52, 53, 55, 57, 59 }, // Top Cymbal
        { 42, 44, 46 }, // High Hat
        { 41, 43, 45, 47, 48, 50 }, // Tom Tom
        { 37, 39 }, // Rim Shot / Side Stick / Hand Clap
    };

    private AdpcmARhythmSupport()
    {
    }

    static int[] buildNoteMap()
    {
        int[] m = new int[128];
        Arrays.fill(m, -1);
        for (int ch = 0; ch < NOTE_GROUPS.length; ch++)
        {
            for (int note : NOTE_GROUPS[ch])
                m[note] = ch;
        }
        return m;
    }

    static void initAdpcmAWithRom(RomDataBlockWriter romWriter, int romType, byte[] rom,
            RegisterWriter write, InitConfig cfg)
    {
        romWriter.write(romType, rom.length, 0, rom);
        initAdpcmA(write, cfg);
    }

    static void initAdpcmA(RegisterWriter write, InitConfig cfg)
    {
        write.write(cfg.port(), cfg.keyOffReg(), 0xBF);
        write.write(cfg.port(), cfg.masterLevelReg(), 0x3F);
        writeChannelAddresses(write, cfg);
    }

    static void triggerPercussion(RegisterWriter write, int port, int note, int velocity,
            int[] noteMap, int levelBaseReg, int keyOnReg)
    {
        if (velocity == 0)
            return;
        int ch = note < 128 ? noteMap[note] : -1;
        if (ch < 0)
            return;
        int level = Math.max(1, (velocity * 31) / 127); // 1-31
        write.write(port, levelBaseReg + ch, 0xC0 | level);
        write.write(port, keyOnReg, 1 << ch);
    }

    private static void writeChannelAddresses(RegisterWriter write, InitConfig cfg)
    {
        for (int c = 0; c < cfg.addrs().length; c++)
        {
            int[] a = cfg.addrs()[c];
            write.write(cfg.port(), cfg.startLowBaseReg() + c, a[0]);
            write.write(cfg.port(), cfg.startHighBaseReg() + c, a[1]);
            write.write(cfg.port(), cfg.endLowBaseReg() + c, a[2]);
            write.write(cfg.port(), cfg.endHighBaseReg() + c, a[3]);
        }
    }
}
