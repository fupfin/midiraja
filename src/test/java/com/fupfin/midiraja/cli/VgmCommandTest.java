/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import com.fupfin.midiraja.export.vgm.ChipSpec;
import com.fupfin.midiraja.export.vgm.ChipType;
import com.fupfin.midiraja.export.vgm.RoutingMode;

class VgmCommandTest
{

    // ── File recognition ─────────────────────────────────────────────────────

    @Test
    void vgmFile_isRecognized()
    {
        assertTrue(VgmCommand.isVgmFile(new File("game.vgm")), ".vgm files should be recognized");
        assertTrue(VgmCommand.isVgmFile(new File("song.VGM")), ".vgm file extension matching is case-insensitive");
    }

    @Test
    void vgzFile_isRecognized()
    {
        assertTrue(VgmCommand.isVgmFile(new File("game.vgz")), ".vgz files should be recognized");
        assertTrue(VgmCommand.isVgmFile(new File("song.VGZ")), ".vgz file extension matching is case-insensitive");
    }

    @Test
    void midiFile_isNotRecognized()
    {
        assertFalse(VgmCommand.isVgmFile(new File("song.mid")), ".mid files should not be treated as VGM");
    }

    @Test
    void fileWithoutExtension_isNotRecognized()
    {
        assertFalse(VgmCommand.isVgmFile(new File("game")), "Files without extension should not be recognized");
    }

    // ── Chip spec resolution — presets ───────────────────────────────────────

    @Test
    void noChipSpec_defaultsToMegadriveYm2612Sn76489()
    {
        VgmCommand cmd = new VgmCommand();
        assertEquals(List.of(ChipType.YM2612, ChipType.SN76489), cmd.resolveChipSpec().chips(),
                "Default chip list should be YM2612 + SN76489 (megadrive)");
    }

    @Test
    void system_zxspectrum_returnsDualAy8910()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "zxspectrum";
        assertEquals(List.of(ChipType.AY8910, ChipType.AY8910), cmd.resolveChipSpec().chips());
    }

    @Test
    void system_msx_returnsYm2413PlusAy8910()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "msx";
        assertEquals(List.of(ChipType.YM2413, ChipType.AY8910), cmd.resolveChipSpec().chips());
    }

    @Test
    void system_sb16_returnsOpl3()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "sb16";
        assertEquals(List.of(ChipType.OPL3), cmd.resolveChipSpec().chips());
    }

    @Test
    void system_megadrive_returnsYm2612AndSn76489()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "megadrive";
        assertEquals(List.of(ChipType.YM2612, ChipType.SN76489), cmd.resolveChipSpec().chips());
    }


@Test
    void system_presets_useSequentialRouting()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "msx";
        assertEquals(RoutingMode.SEQUENTIAL, cmd.resolveChipSpec().mode(),
                "Presets should always use SEQUENTIAL routing mode");
    }

    @Test
    void system_caseInsensitive()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "ZXSPECTRUM";
        assertDoesNotThrow(() -> cmd.resolveChipSpec(), "System name lookup should be case-insensitive");

        cmd.chipSpec.system = "MSX";
        assertDoesNotThrow(() -> cmd.resolveChipSpec());

        cmd.chipSpec.system = "SB16";
        assertDoesNotThrow(() -> cmd.resolveChipSpec());
    }

    @Test
    void unknownSystem_throwsIllegalArgument()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "xyz";
        assertThrows(IllegalArgumentException.class, () -> cmd.resolveChipSpec(),
                "Unknown system value should throw IllegalArgumentException");
    }

    @Test
    void emptySystem_throwsIllegalArgument()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.system = "";
        assertThrows(IllegalArgumentException.class, () -> cmd.resolveChipSpec(),
                "Empty system value should throw IllegalArgumentException");
    }

    // ── Chip spec resolution — --chips spec ──────────────────────────────────

    @Test
    void chips_singleAy8910_returnsSingleChip()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.chips = "ay8910";
        assertEquals(List.of(ChipType.AY8910), cmd.resolveChipSpec().chips());
    }

    @Test
    void chips_ay8910PlusYm2413_returnsTwoChipsWithChannelMode()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.chips = "ay8910+ym2413";
        ChipSpec spec = cmd.resolveChipSpec();
        assertEquals(List.of(ChipType.AY8910, ChipType.YM2413), spec.chips());
        assertEquals(RoutingMode.CHANNEL, spec.mode(), "+ separator should produce CHANNEL routing mode");
    }

    @Test
    void chips_sequentialSeparator_returnsSequentialMode()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.chipSpec.chips = "scc>ay8910";
        ChipSpec spec = cmd.resolveChipSpec();
        assertEquals(List.of(ChipType.SCC, ChipType.AY8910), spec.chips());
        assertEquals(RoutingMode.SEQUENTIAL, spec.mode(), "> separator should produce SEQUENTIAL routing mode");
    }

    // ── File list handling ────────────────────────────────────────────────────

    @Test
    void emptyFileList_throwsIllegalArgument()
    {
        VgmCommand cmd = new VgmCommand();
        cmd.setFiles(new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> cmd.validateFiles(),
                "Empty file list should throw IllegalArgumentException");
    }

    @Test
    void singleFile_isValid()
    {
        VgmCommand cmd = new VgmCommand();
        List<File> files = new ArrayList<>();
        files.add(new File("song.vgm"));
        cmd.setFiles(files);
        assertDoesNotThrow(() -> cmd.validateFiles(), "Single file list should be valid");
    }

    @Test
    void multipleFiles_areValid()
    {
        VgmCommand cmd = new VgmCommand();
        List<File> files = new ArrayList<>();
        files.add(new File("song1.vgm"));
        files.add(new File("song2.vgm"));
        files.add(new File("song3.vgz"));
        cmd.setFiles(files);
        assertDoesNotThrow(() -> cmd.validateFiles(), "Multiple files should be valid");
    }

    @Test
    void muteOption_isParsedAndAppliedToConversionState()
    {
        VgmCommand cmd = new VgmCommand();
        new CommandLine(cmd).parseArgs("--mute", "4-6,10", "song.mid");
        cmd.refreshMutedChannels(new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Set.of(3, 4, 5, 9), cmd.mutedChannelsForTest());
    }

    @Test
    void applyMutedChannels_removesOnlyMutedMidiChannels() throws Exception
    {
        var seq = new Sequence(Sequence.PPQ, 480);
        var track = seq.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0)); // ch1
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 61, 100), 1)); // ch2
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 2, 62, 100), 2)); // ch3

        VgmCommand.applyMutedChannels(seq, Set.of(0, 1));

        boolean hasCh0 = false;
        boolean hasCh1 = false;
        boolean hasCh2 = false;
        for (int i = 0; i < track.size(); i++)
        {
            int status = track.get(i).getMessage().getStatus();
            if ((status & 0xF0) != 0x90)
                continue;
            int channel = status & 0x0F;
            hasCh0 |= channel == 0;
            hasCh1 |= channel == 1;
            hasCh2 |= channel == 2;
        }

        assertFalse(hasCh0, "Muted channel 1 should be removed");
        assertFalse(hasCh1, "Muted channel 2 should be removed");
        assertTrue(hasCh2, "Unmuted channel should remain");
    }

}
