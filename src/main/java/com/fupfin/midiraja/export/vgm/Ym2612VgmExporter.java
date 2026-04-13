/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import com.fupfin.midiraja.midi.OpnMidiNativeBridge;

/**
 * Converts a MIDI {@link Sequence} to YM2612 (OPN2) VGM data via libOPNMIDI's
 * {@code VGMFileDumper} backend (emulator ID 7).
 *
 * <p>
 * Unlike the {@link CompositeVgmExporter} streaming model, this class delegates all FM synthesis
 * and register-write sequencing to libOPNMIDI. It drives the file-based playback API
 * ({@code opn2_openData} / {@code opn2_play}) until the MIDI file is exhausted, then reads the
 * VGM file that libOPNMIDI has written out.
 *
 * <p>
 * <b>Note:</b> {@code opn2_set_vgm_out_path} is a global setting in libOPNMIDI and must be called
 * before {@code opn2_init}. This class is therefore not safe for concurrent use.
 */
public final class Ym2612VgmExporter
{
    private static final int VGM_DUMPER_EMULATOR = 7;
    private static final String WOPN_BANK = "/com/midiraja/midi/opn-gm.wopn";
    private static final int RENDER_BUFFER_FRAMES = 4096;

    private final OpnMidiNativeBridge bridge;

    public Ym2612VgmExporter(OpnMidiNativeBridge bridge)
    {
        this.bridge = bridge;
    }

    /**
     * Converts the given {@link Sequence} to YM2612 VGM bytes.
     *
     * @param sequence
     *            input MIDI sequence
     * @return raw VGM file bytes
     * @throws Exception
     *             if libOPNMIDI initialization, bank loading, or MIDI loading fails
     */
    public byte[] export(Sequence sequence) throws Exception
    {
        byte[] bankData = loadBankData();
        byte[] midiBytes = serializeSequence(sequence);

        Path tempVgm = Files.createTempFile("midiraja_ym2612_", ".vgm");
        try
        {
            bridge.setVgmOutPath(tempVgm.toAbsolutePath().toString());
            bridge.init(44100);
            bridge.switchEmulator(VGM_DUMPER_EMULATOR);
            bridge.loadBankData(bankData);
            bridge.setNumChips(1);
            bridge.openMidiData(midiBytes);

            short[] buf = new short[RENDER_BUFFER_FRAMES * 2];
            while (bridge.playFromFile(buf) > 0)
            {
            }
        }
        finally
        {
            bridge.close();
            // tempVgm is deleted after reading (or on failure, immediately)
        }

        try
        {
            return Files.readAllBytes(tempVgm);
        }
        finally
        {
            Files.deleteIfExists(tempVgm);
        }
    }

    private static byte[] serializeSequence(Sequence sequence) throws IOException
    {
        int type = sequence.getTracks().length > 1 ? 1 : 0;
        var bos = new ByteArrayOutputStream();
        MidiSystem.write(sequence, type, bos);
        return bos.toByteArray();
    }

    private static byte[] loadBankData() throws IOException
    {
        try (var stream = Ym2612VgmExporter.class.getResourceAsStream(WOPN_BANK))
        {
            if (stream == null)
                throw new IOException("WOPN bank not found: " + WOPN_BANK);
            return stream.readAllBytes();
        }
    }
}
