/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.vgm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

/** Parses VGM binary files into a structured {@link VgmParseResult}. */
public class VgmParser {

    public VgmParseResult parse(File file) throws IOException {
        byte[] data = readAllBytes(file);
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        validateMagic(buf);

        int version = buf.getInt(0x08);
        long sn76489Clock = Integer.toUnsignedLong(buf.getInt(0x0C));
        long ym2612Clock = (data.length > 0x30) ? Integer.toUnsignedLong(buf.getInt(0x2C)) : 0;
        long ym2151Clock = (data.length > 0x34) ? Integer.toUnsignedLong(buf.getInt(0x30)) : 0;
        long ay8910Clock = (data.length > 0x78) ? Integer.toUnsignedLong(buf.getInt(0x74)) : 0;
        long k051649Clock = (data.length > 0xB0) ? Integer.toUnsignedLong(buf.getInt(0xAC)) : 0;
        // K051649 in Konami MSX cartridges runs at the full cartridge bus clock (= CPU clock).
        // The AY8910 PSG has an internal /2 prescaler, so ay8910Clock = CPU/2.
        // When the VGM header stores k051649Clock=0, reconstruct the SCC clock as 2× ay8910Clock.
        long sccClock = (k051649Clock != 0) ? k051649Clock : ay8910Clock * 2;

        int dataOffset = calculateDataOffset(buf, version, data.length);
        String gd3Title = parseGd3(buf, data.length);
        int loopStart = parseLoopStart(buf, data.length);
        List<VgmEvent> events = parseCommands(data, dataOffset, loopStart);

        return new VgmParseResult(version, sn76489Clock, ym2612Clock, ym2151Clock, ay8910Clock,
                sccClock, events, gd3Title);
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (InputStream in = openStream(file)) {
            return in.readAllBytes();
        }
    }

    private static InputStream openStream(File file) throws IOException {
        if (file.getName().toLowerCase().endsWith(".vgz") || hasGzipMagic(file)) {
            return new GZIPInputStream(new FileInputStream(file));
        }
        return new FileInputStream(file);
    }

    private static boolean hasGzipMagic(File file) throws IOException {
        try (var fis = new FileInputStream(file)) {
            byte[] magic = new byte[2];
            return fis.read(magic) == 2 && (magic[0] & 0xFF) == 0x1F && (magic[1] & 0xFF) == 0x8B;
        }
    }

    private static void validateMagic(ByteBuffer buf) throws IOException {
        if (buf.capacity() < 0x40) throw new IOException("File too small for VGM header");
        int magic = buf.getInt(0);
        if (magic != 0x206D6756) throw new IOException("Not a VGM file (bad magic)");
    }

    private static int calculateDataOffset(ByteBuffer buf, int version, int length) {
        if (version < 0x150 || length <= 0x38) return 0x40;
        int relative = buf.getInt(0x34);
        return (relative == 0) ? 0x40 : 0x34 + relative;
    }

    private static @Nullable String parseGd3(ByteBuffer buf, int length) {
        if (length < 0x18) return null;
        int gd3Relative = buf.getInt(0x14);
        if (gd3Relative == 0) return null;

        int gd3Offset = 0x14 + gd3Relative;
        if (gd3Offset + 12 > length) return null;

        // Check "Gd3 " magic
        if (buf.getInt(gd3Offset) != 0x20336447) return null;

        // Skip version(4) + length(4)
        int stringsStart = gd3Offset + 12;
        if (stringsStart >= length) return null;

        // First null-terminated UTF-16LE string = track title (English)
        return readUtf16String(buf, stringsStart, length);
    }

    private static @Nullable String readUtf16String(ByteBuffer buf, int offset, int limit) {
        var out = new ByteArrayOutputStream();
        for (int i = offset; i + 1 < limit; i += 2) {
            byte lo = buf.get(i);
            byte hi = buf.get(i + 1);
            if (lo == 0 && hi == 0) break;
            out.write(lo);
            out.write(hi);
        }
        if (out.size() == 0) return null;
        return out.toString(StandardCharsets.UTF_16LE);
    }

    private static int parseLoopStart(ByteBuffer buf, int length) {
        if (length < 0x20) return 0;
        int loopRelative = buf.getInt(0x1C);
        if (loopRelative == 0) return 0;
        return 0x1C + loopRelative;
    }

    private static List<VgmEvent> parseCommands(byte[] data, int offset, int loopStart) {
        var events = new ArrayList<VgmEvent>();
        long sampleOffset = 0;
        int pos = offset;
        boolean loopDone = false;

        while (pos < data.length) {
            int cmd = data[pos++] & 0xFF;
            switch (cmd) {
                case 0x50 -> { // SN76489
                    if (pos >= data.length) break;
                    events.add(new VgmEvent(sampleOffset, 0, new byte[]{data[pos++]}));
                }
                case 0xA0 -> { // AY8910 register write
                    if (pos + 1 >= data.length) break;
                    events.add(new VgmEvent(sampleOffset, 3, new byte[]{data[pos], data[pos + 1]}));
                    pos += 2;
                }
                case 0xD2 -> { // K051649 (SCC) register write
                    if (pos + 2 >= data.length) break;
                    events.add(new VgmEvent(sampleOffset, 4,
                            new byte[]{data[pos], data[pos + 1], data[pos + 2]}));
                    pos += 3;
                }
                case 0x52 -> { // YM2612 port0
                    if (pos + 1 >= data.length) break;
                    events.add(new VgmEvent(sampleOffset, 1, new byte[]{data[pos], data[pos + 1]}));
                    pos += 2;
                }
                case 0x53 -> { // YM2612 port1
                    if (pos + 1 >= data.length) break;
                    events.add(new VgmEvent(sampleOffset, 2, new byte[]{data[pos], data[pos + 1]}));
                    pos += 2;
                }
                case 0x54 -> { // YM2151
                    if (pos + 1 >= data.length) break;
                    events.add(new VgmEvent(sampleOffset, 5, new byte[]{data[pos], data[pos + 1]}));
                    pos += 2;
                }
                case 0x61 -> { // Wait N samples
                    if (pos + 1 >= data.length) break;
                    int n = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
                    sampleOffset += n;
                    pos += 2;
                }
                case 0x62 -> sampleOffset += 735;  // NTSC 1/60s
                case 0x63 -> sampleOffset += 882;  // PAL 1/50s
                case 0x66 -> {  // End of data; loop once if loop point exists
                    if (loopStart > 0 && !loopDone) {
                        loopDone = true;
                        pos = loopStart;
                    } else {
                        return events;
                    }
                }
                default -> {
                    if (cmd >= 0x70 && cmd <= 0x7F) {
                        sampleOffset += (cmd & 0x0F) + 1;
                    } else if (cmd >= 0x80 && cmd <= 0x8F) {
                        sampleOffset += cmd & 0x0F; // YM2612 DAC write + wait n samples
                    } else if (cmd == 0x67) {
                        // PCM data block: 0x67 0x66 <type> <4-byte size> <data>
                        pos++; // compatibility byte 0x66
                        pos++; // type byte
                        if (pos + 4 <= data.length) {
                            int blockSize = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8)
                                    | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
                            pos += 4 + (blockSize & 0x7FFFFFFF);
                        }
                    } else if ((cmd >= 0x30 && cmd <= 0x4E) || (cmd >= 0x51 && cmd <= 0x5F)
                            || (cmd >= 0xA0 && cmd <= 0xBF)) {
                        pos += 2; // 2-operand commands
                    } else if (cmd >= 0xC0 && cmd <= 0xDF) {
                        pos += 3; // 3-operand commands
                    } else if (cmd >= 0xE0 && cmd <= 0xFF) {
                        pos += 4; // 4-operand commands
                    }
                    // Other unknown commands: skip just the command byte (already consumed)
                }
            }
        }
        return events;
    }
}
