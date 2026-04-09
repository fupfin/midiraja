/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.format.vgm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VgmParserTest
{

    @TempDir
    Path tmp;

    @Test
    void parse_minimalVgm_returnsResult() throws Exception
    {
        byte[] data = buildMinimalVgm();
        var file = tmp.resolve("test.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(0x151, result.vgmVersion());
        assertEquals(3_579_545L, result.sn76489Clock());
        assertEquals(0L, result.ym2612Clock());
        assertEquals(1, result.events().size());

        var event = result.events().getFirst();
        assertEquals(0, event.sampleOffset());
        assertEquals(0, event.chip()); // SN76489
    }

    @Test
    void parse_dacWriteCommands_accumulateSampleOffset() throws Exception
    {
        // 0x80-0x8F: YM2612 DAC write + wait (cmd & 0x0F) samples.
        // Sonic 3 title screen uses these extensively (6.83s out of 15.72s total).
        // Without this fix the song played at ~2x speed.
        byte[] data = buildVgmWith0x8nCommands();
        var file = tmp.resolve("dac.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        // 0x80 → wait 0, 0x81 → wait 1, 0x82 → wait 2, 0x8F → wait 15 (total = 18 samples)
        // The SN76489 event after all waits should carry sampleOffset = 18
        assertEquals(1, result.events().size());
        assertEquals(18, result.events().getFirst().sampleOffset());
    }

    private static byte[] buildVgmWith0x8nCommands()
    {
        var buf = ByteBuffer.allocate(0x47).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756); // magic
        buf.putInt(0x04, 0x47 - 0x04);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0x0C); // data at 0x40

        // DAC writes with waits: 0x80(0) + 0x81(1) + 0x82(2) + 0x8F(15) = 18 samples
        buf.put(0x40, (byte) 0x80);
        buf.put(0x41, (byte) 0x81);
        buf.put(0x42, (byte) 0x82);
        buf.put(0x43, (byte) 0x8F);
        buf.put(0x44, (byte) 0x50); // SN76489 event at sampleOffset=18
        buf.put(0x45, (byte) 0x00);
        buf.put(0x46, (byte) 0x66); // end

        return buf.array();
    }

    @Test
    void parse_gd3Title_isExtracted() throws Exception
    {
        byte[] data = buildVgmWithGd3("Sonic");
        var file = tmp.resolve("gd3.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals("Sonic", result.gd3Title());
    }

    @Test
    void parse_vgzFile_isDecompressedAndParsed() throws Exception
    {
        byte[] rawVgm = buildMinimalVgm();
        var baos = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(baos))
        {
            gzip.write(rawVgm);
        }
        var file = tmp.resolve("test.vgz").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(baos.toByteArray());
        }

        var result = new VgmParser().parse(file);

        assertEquals(0x151, result.vgmVersion());
        assertEquals(1, result.events().size());
    }

    @Test
    void parse_waitCommands_accumulateSampleOffset() throws Exception
    {
        // 0x62 = NTSC frame (735 samples), 0x63 = PAL frame (882), 0x7n = (n+1) samples
        byte[] data = buildVgmWithWaitCommands();
        var file = tmp.resolve("wait.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        // 0x62(735) + 0x63(882) + 0x71(2) = 1619 samples
        assertEquals(1, result.events().size());
        assertEquals(1619, result.events().getFirst().sampleOffset());
    }

    @Test
    void parse_invalidMagic_throwsIOException() throws Exception
    {
        var buf = ByteBuffer.allocate(0x43).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0xDEADBEEF); // wrong magic
        var file = tmp.resolve("bad.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(buf.array());
        }

        assertThrows(java.io.IOException.class, () -> new VgmParser().parse(file));
    }

    @Test
    void parse_fileTooSmall_throwsIOException() throws Exception
    {
        var file = tmp.resolve("tiny.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(new byte[10]);
        }

        assertThrows(java.io.IOException.class, () -> new VgmParser().parse(file));
    }

    @Test
    void parse_version100_usesFixedDataOffset() throws Exception
    {
        // v1.00: no 0x34 data-offset field → data always at 0x40
        byte[] data = buildVgmVersion100();
        var file = tmp.resolve("v100.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(0x100, result.vgmVersion());
        assertEquals(1, result.events().size());
    }

    @Test
    void parse_loopPoint_isFollowedOnce() throws Exception
    {
        // Loop: event A → loop-back → event A again → end
        // After one loop, the event list should contain 2 SN events.
        byte[] data = buildVgmWithLoop();
        var file = tmp.resolve("loop.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(2, result.events().size());
    }

    @Test
    void parse_pcmDataBlock_isSkipped() throws Exception
    {
        // 0x67 PCM block should be skipped; SN event after block should be parsed
        byte[] data = buildVgmWithPcmBlock();
        var file = tmp.resolve("pcm.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(1, result.events().size());
        assertEquals(0, result.events().getFirst().chip()); // SN76489
    }

    @Test
    void parse_wait16bit_accumulatesSamples() throws Exception
    {
        // 0x61 nn nn: wait exactly N samples
        byte[] data = buildVgmWith16bitWait(1000);
        var file = tmp.resolve("wait16.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(1, result.events().size());
        assertEquals(1000, result.events().getFirst().sampleOffset());
    }

    @Test
    void parse_allRemainingChipCommands_areRecognized() throws Exception
    {
        // Tests all chip IDs that weren't covered yet
        byte[] data = buildVgmWithAllChips();
        var file = tmp.resolve("allchips.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        // YM2413(13), AY8910(3), SCC(4), YM2151(5), YM2203(6), YM2608(7,8),
        // YM2610(9,10), GB-DMG(11), NES-2A03(17), HuC6280(12), OPL2(14), OPL3(15,16)
        var chips = result.events().stream().mapToInt(e -> e.chip()).distinct().sorted().toArray();
        assertArrayEquals(new int[] { 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17 }, chips);
    }

    private static byte[] buildVgmVersion100()
    {
        // v1.00 has no 0x34 offset field; data starts at 0x40
        var buf = ByteBuffer.allocate(0x43).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, 0x43 - 0x04);
        buf.putInt(0x08, 0x00000100); // v1.00
        buf.putInt(0x0C, 3_579_545);
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66);
        return buf.array();
    }

    private static byte[] buildVgmWithLoop()
    {
        // Layout: header | [loopStart:] SN event | 0x66 (end → jump back once)
        // loopStart = 0x40 (relative: 0x1C + loopRelative = 0x40 → loopRelative = 0x24)
        var buf = ByteBuffer.allocate(0x44).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, 0x44 - 0x04);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x1C, 0x40 - 0x1C); // loopOffset relative to 0x1C → points to 0x40
        buf.putInt(0x34, 0x0C); // data at 0x40

        buf.put(0x40, (byte) 0x50); // SN event (at loop start)
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66); // end → loop once back to 0x40
        buf.put(0x43, (byte) 0x00); // padding
        return buf.array();
    }

    private static byte[] buildVgmWithPcmBlock()
    {
        // 0x67 0x66 <type> <4-byte size> <N bytes PCM> then SN event
        int pcmSize = 4;
        int totalSize = 0x40 + 1 + 1 + 1 + 4 + pcmSize + 2 + 1; // cmd+compat+type+size+data+SN+end
        var buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, totalSize - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0x0C);

        int pos = 0x40;
        buf.put(pos++, (byte) 0x67); // PCM block command
        buf.put(pos++, (byte) 0x66); // compatibility byte
        buf.put(pos++, (byte) 0x00); // type
        // 4-byte little-endian size
        buf.put(pos++, (byte) pcmSize);
        buf.put(pos++, (byte) 0x00);
        buf.put(pos++, (byte) 0x00);
        buf.put(pos++, (byte) 0x00);
        pos += pcmSize; // skip PCM data (zeroes)
        buf.put(pos++, (byte) 0x50); // SN76489 event
        buf.put(pos++, (byte) 0x00);
        buf.put(pos, (byte) 0x66); // end
        return buf.array();
    }

    private static byte[] buildVgmWith16bitWait(int samples)
    {
        var buf = ByteBuffer.allocate(0x45).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, 0x45 - 0x04);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0x0C);

        buf.put(0x40, (byte) 0x61); // 16-bit wait
        buf.put(0x41, (byte) (samples & 0xFF));
        buf.put(0x42, (byte) ((samples >> 8) & 0xFF));
        buf.put(0x43, (byte) 0x50); // SN76489
        buf.put(0x44, (byte) 0x00);
        // no 0x66 needed — parser returns at end of array
        return buf.array();
    }

    private static byte[] buildVgmWithAllChips()
    {
        // Each chip command: cmd byte + 1 or 2 data bytes
        // chip IDs: YM2413=13(0x51), AY8910=3(0xA0), SCC=4(0xD2), YM2151=5(0x54),
        // YM2203=6(0x55), YM2608=7(0x56),8(0x57), YM2610=9(0x58),10(0x59),
        // GB-DMG=11(0xB3), NES=17(0xB4), HuC6280=12(0xB9),
        // OPL2=14(0x5A), OPL3p0=15(0x5E), OPL3p1=16(0x5F)
        byte[] cmds = {
                0x51, 0x00, 0x00, // YM2413
                (byte) 0xA0, 0x00, 0x00, // AY8910
                (byte) 0xD2, 0x00, 0x00, 0x00, // SCC (3 bytes)
                0x54, 0x00, 0x00, // YM2151
                0x55, 0x00, 0x00, // YM2203
                0x56, 0x00, 0x00, // YM2608 port0
                0x57, 0x00, 0x00, // YM2608 port1
                0x58, 0x00, 0x00, // YM2610 port0
                0x59, 0x00, 0x00, // YM2610 port1
                (byte) 0xB3, 0x00, 0x00, // GB-DMG
                (byte) 0xB4, 0x00, 0x00, // NES 2A03
                (byte) 0xB9, 0x00, 0x00, // HuC6280
                0x5A, 0x00, 0x00, // OPL2
                0x5E, 0x00, 0x00, // OPL3 port0
                0x5F, 0x00, 0x00, // OPL3 port1
                0x66 // end
        };
        int totalSize = 0x40 + cmds.length;
        var buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, totalSize - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0x0C);
        for (int i = 0; i < cmds.length; i++)
            buf.put(0x40 + i, cmds[i]);
        return buf.array();
    }

    @Test
    void parse_version161_readsExtendedClocks() throws Exception
    {
        // v1.61 adds gameBoyDmg, nes2A03, huC6280, k051649 clock fields
        byte[] data = buildVgmVersion161WithClocks(4_194_304L, 1_789_772L, 3_579_545L, 3_579_545L);
        var file = tmp.resolve("v161.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(0x161, result.vgmVersion());
        assertEquals(4_194_304L, result.gameBoyDmgClock());
        assertEquals(1_789_772L, result.nes2A03Clock());
        assertEquals(3_579_545L, result.huC6280Clock());
        // k051649Clock != 0 → sccClock = k051649Clock (not ay8910*2)
        assertEquals(3_579_545L, result.sccClock());
    }

    @Test
    void parse_gzipMagicWithVgmExtension_isDecompressed() throws Exception
    {
        // File has gzip magic bytes but .vgm extension → hasGzipMagic() returns true
        byte[] rawVgm = buildMinimalVgm();
        var baos = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(baos))
        {
            gzip.write(rawVgm);
        }
        var file = tmp.resolve("disguised.vgm").toFile(); // .vgm extension, gzip content
        try (var out = new FileOutputStream(file))
        {
            out.write(baos.toByteArray());
        }

        var result = new VgmParser().parse(file);

        assertEquals(0x151, result.vgmVersion());
        assertEquals(1, result.events().size());
    }

    @Test
    void parse_dataOffsetRelativeZero_usesDefault0x40() throws Exception
    {
        // v1.51 with 0x34 = 0 → data starts at 0x40
        byte[] data = buildMinimalVgmWithRelativeZero();
        var file = tmp.resolve("relzero.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(1, result.events().size());
    }

    @Test
    void parse_gd3BadMagic_returnsNullTitle() throws Exception
    {
        byte[] data = buildVgmWithBadGd3Magic();
        var file = tmp.resolve("badgd3.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertNull(result.gd3Title());
    }

    @Test
    void parse_gd3TruncatedBeforeStrings_returnsNullTitle() throws Exception
    {
        byte[] data = buildVgmWithTruncatedGd3();
        var file = tmp.resolve("truncgd3.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertNull(result.gd3Title());
    }

    @Test
    void parse_ym2612Commands_areRecognized() throws Exception
    {
        // YM2612 port0 (0x52) and port1 (0x53) were missing from allchips test
        byte[] data = buildVgmWithYm2612();
        var file = tmp.resolve("ym2612.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        var chips = result.events().stream().mapToInt(e -> e.chip()).distinct().sorted().toArray();
        assertArrayEquals(new int[] { 1, 2 }, chips); // port0=1, port1=2
    }

    @Test
    void parse_defaultSkipCommands_areSkippedWithoutCrash() throws Exception
    {
        // 0x30-0x4E (2-op skip), 0xC0-0xDF (3-op skip), 0xE0-0xFF (4-op skip)
        byte[] data = buildVgmWithSkipCommands();
        var file = tmp.resolve("skip.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(1, result.events().size()); // SN event after all skipped commands
        assertEquals(0, result.events().getFirst().chip());
    }

    @Test
    void parse_gd3LengthTooSmall_gd3IsNull() throws Exception
    {
        // Pass a buffer of length 0x17 to parseGd3 (< 0x18 threshold).
        // Use version 1.00 with a minimal 0x40-byte file but set length sentinel via GD3 pointer.
        // The simpler approach: a VGM with GD3 offset pointing such that (gd3Offset + 12) > length.
        // Already covered by parse_gd3OffsetBeyondEof_returnsNullTitle.
        // Instead, test via the gd3Relative==0 path (no GD3 pointer set) in minimal VGM.
        byte[] data = buildMinimalVgm(); // GD3 offset = 0 → gd3Relative==0 → null
        var file = tmp.resolve("nogd3.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertNull(result.gd3Title());
    }

    @Test
    void parse_gd3OffsetBeyondEof_returnsNullTitle() throws Exception
    {
        // GD3 relative offset points past end of file → gd3Offset + 12 > length
        var buf = ByteBuffer.allocate(0x44).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, 0x44 - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x14, 0x200); // GD3 way past EOF
        buf.putInt(0x34, 0x0C);
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66);
        var file = tmp.resolve("gd3past.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(buf.array());
        }

        var result = new VgmParser().parse(file);

        assertNull(result.gd3Title());
    }

    @Test
    void parse_gd3EmptyTitle_returnsNullTitle() throws Exception
    {
        // GD3 tag with empty UTF-16 string (immediate null terminator) → readUtf16String returns null
        byte[] data = buildVgmWithEmptyGd3Title();
        var file = tmp.resolve("emptygd3.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertNull(result.gd3Title());
    }

    @Test
    void parse_loopRelativeZero_noLoop() throws Exception
    {
        // loopRelative == 0 at 0x1C → parseLoopStart returns 0 → end-of-data terminates immediately
        byte[] data = buildMinimalVgm(); // 0x1C field is 0 (already zero-initialized)
        var file = tmp.resolve("noloop.vgm").toFile();
        try (var out = new FileOutputStream(file))
        {
            out.write(data);
        }

        var result = new VgmParser().parse(file);

        assertEquals(1, result.events().size()); // exactly 1 event, no loop doubling
    }

    private static byte[] buildVgmWithEmptyGd3Title()
    {
        int gd3Offset = 0x44;
        int size = gd3Offset + 12 + 2; // GD3 header + null terminator only
        var buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, size - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x14, gd3Offset - 0x14);
        buf.putInt(0x34, 0x0C);
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66);
        buf.put(0x43, (byte) 0x00);
        buf.putInt(gd3Offset, 0x20336447); // "Gd3 " magic
        buf.putInt(gd3Offset + 4, 0x100);
        buf.putInt(gd3Offset + 8, 2); // 2 bytes = one null char
        // bytes at gd3Offset+12 are already 0x00 0x00 (null terminator)
        return buf.array();
    }

    private static byte[] buildVgmVersion161WithClocks(long gbClock, long nesClock, long hucClock,
            long sccClock)
    {
        // v1.61 header extends to at least 0xB4 for k051649
        int size = 0xB8;
        var buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, size - 4);
        buf.putInt(0x08, 0x00000161); // v1.61
        buf.putInt(0x0C, 3_579_545); // SN76489
        buf.putInt(0x34, 0x0C); // data at 0x40

        // v1.61 clock fields
        buf.putInt(0x80, (int) gbClock);
        buf.putInt(0x84, (int) nesClock);
        buf.putInt(0xA4, (int) hucClock);
        buf.putInt(0xAC, (int) sccClock); // k051649Clock non-zero → sccClock = k051649Clock

        // Commands
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66);
        return buf.array();
    }

    private static byte[] buildMinimalVgmWithRelativeZero()
    {
        var buf = ByteBuffer.allocate(0x43).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, 0x43 - 0x04);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0); // relative=0 → data at 0x40 (default)
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66);
        return buf.array();
    }

    private static byte[] buildVgmWithBadGd3Magic()
    {
        int gd3Offset = 0x44;
        int size = gd3Offset + 16;
        var buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, size - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x14, gd3Offset - 0x14); // GD3 pointer
        buf.putInt(0x34, 0x0C);
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66);
        buf.put(0x43, (byte) 0x00);
        buf.putInt(gd3Offset, 0xDEADBEEF); // bad magic
        return buf.array();
    }

    private static byte[] buildVgmWithTruncatedGd3()
    {
        // GD3 tag with valid magic but stringsStart == length (no room for strings)
        int gd3Offset = 0x44;
        int size = gd3Offset + 12; // exactly 12 bytes for GD3 header, no string data
        var buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, size - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x14, gd3Offset - 0x14);
        buf.putInt(0x34, 0x0C);
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66);
        buf.put(0x43, (byte) 0x00);
        buf.putInt(gd3Offset, 0x20336447); // valid "Gd3 " magic
        buf.putInt(gd3Offset + 4, 0x100); // version
        buf.putInt(gd3Offset + 8, 0); // length=0, stringsStart == size → return null
        return buf.array();
    }

    private static byte[] buildVgmWithYm2612()
    {
        byte[] cmds = {
                0x52, 0x00, 0x00, // YM2612 port0 (chip=1)
                0x53, 0x00, 0x00, // YM2612 port1 (chip=2)
                0x66
        };
        int size = 0x40 + cmds.length;
        var buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, size - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x2C, 7_670_453); // YM2612 clock
        buf.putInt(0x34, 0x0C);
        for (int i = 0; i < cmds.length; i++)
            buf.put(0x40 + i, cmds[i]);
        return buf.array();
    }

    private static byte[] buildVgmWithSkipCommands()
    {
        byte[] cmds = {
                0x30, 0x00, 0x00, // 2-op skip (0x30 range)
                (byte) 0xC0, 0x00, 0x00, 0x00, // 3-op skip
                (byte) 0xE0, 0x00, 0x00, 0x00, 0x00, // 4-op skip
                0x50, 0x00, // SN76489 event
                0x66
        };
        int size = 0x40 + cmds.length;
        var buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, size - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0x0C);
        for (int i = 0; i < cmds.length; i++)
            buf.put(0x40 + i, cmds[i]);
        return buf.array();
    }

    private static byte[] buildVgmWithGd3(String title) throws Exception
    {
        // GD3 tag: magic(4) + version(4) + length(4) + UTF-16LE title + 0x0000 terminator
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_16LE);
        int gd3Size = 12 + titleBytes.length + 2; // header + string + null terminator
        int gd3Offset = 0x44; // placed right after data section
        int totalSize = gd3Offset + gd3Size;

        var buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756); // "Vgm " magic
        buf.putInt(0x04, totalSize - 4);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x14, gd3Offset - 0x14); // GD3 relative offset from 0x14
        buf.putInt(0x34, 0x0C); // data at 0x40

        // Commands at 0x40
        buf.put(0x40, (byte) 0x50);
        buf.put(0x41, (byte) 0x00);
        buf.put(0x42, (byte) 0x66); // end
        // padding byte at 0x43 keeps gd3Offset aligned
        buf.put(0x43, (byte) 0x00);

        // GD3 tag at gd3Offset
        buf.putInt(gd3Offset, 0x20336447); // "Gd3 " magic
        buf.putInt(gd3Offset + 4, 0x00000100); // version
        buf.putInt(gd3Offset + 8, titleBytes.length + 2); // data length
        for (int i = 0; i < titleBytes.length; i++)
            buf.put(gd3Offset + 12 + i, titleBytes[i]);
        // null terminator already zero from allocation

        return buf.array();
    }

    private static byte[] buildVgmWithWaitCommands()
    {
        // 0x62(NTSC=735) + 0x63(PAL=882) + 0x71((1&0x0F)+1=2) + SN event + end
        var buf = ByteBuffer.allocate(0x47).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00, 0x206D6756);
        buf.putInt(0x04, 0x47 - 0x04);
        buf.putInt(0x08, 0x00000151);
        buf.putInt(0x0C, 3_579_545);
        buf.putInt(0x34, 0x0C); // data at 0x40

        buf.put(0x40, (byte) 0x62); // NTSC wait: +735
        buf.put(0x41, (byte) 0x63); // PAL wait: +882
        buf.put(0x42, (byte) 0x71); // short wait: +(1+1)=2
        buf.put(0x43, (byte) 0x50); // SN76489 event at sampleOffset=1619
        buf.put(0x44, (byte) 0x00);
        buf.put(0x45, (byte) 0x66); // end

        return buf.array();
    }

    private static byte[] buildMinimalVgm()
    {
        var buf = ByteBuffer.allocate(0x43).order(ByteOrder.LITTLE_ENDIAN);

        // 0x00: "Vgm " magic
        buf.putInt(0x00, 0x206D6756);
        // 0x04: EOF offset (relative to 0x04)
        buf.putInt(0x04, 0x43 - 0x04);
        // 0x08: version 1.51
        buf.putInt(0x08, 0x00000151);
        // 0x0C: SN76489 clock
        buf.putInt(0x0C, 3_579_545);
        // 0x10: total samples (unused for this test)
        buf.putInt(0x10, 0);
        // 0x14: GD3 offset = 0 (no GD3)
        buf.putInt(0x14, 0);
        // 0x2C: YM2612 clock = 0
        buf.putInt(0x2C, 0);
        // 0x34: data offset = 0x0C (relative to 0x34 → data at 0x40)
        buf.putInt(0x34, 0x0C);

        // Commands at 0x40
        buf.put(0x40, (byte) 0x50); // SN76489 write
        buf.put(0x41, (byte) 0x00); // data byte
        buf.put(0x42, (byte) 0x66); // end of data

        return buf.array();
    }
}
